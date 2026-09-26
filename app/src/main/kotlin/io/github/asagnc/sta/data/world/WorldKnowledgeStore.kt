package io.github.asagnc.sta.data.world

import android.content.Context
import androidx.room.withTransaction
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 观测层的读写入口。
 *
 * 所有异常都吞掉并降级为"没有数据"：本库记录的是运行副产物，读不到或写不进都不该让
 * 正在跑的 run 失败——这与它要替代的失败学习文件存储是同一条原则。
 */
internal object WorldKnowledgeStore {

    /** 条目数上限，超出后按时间裁掉最旧的。 */
    const val KEEP_ENTRIES = 500

    /**
     * 条目不再按时间过期。
     *
     * 原先的 7 天有效期是外部替世界定下的「多久算旧」：一条长期有用但近期没人碰的结论会
     * 被时间删掉，而真正没用的条目反而能靠不断重新写入而活着。痕迹是否继续参与注入，
     * 改由取用与曝光的计数决定（见 [WorldScore.usageFactor]），不再看日历。
     * 0 即「不过期」，已落库的条目也照此处理。
     */
    const val DEFAULT_TTL_MS = 0L

    /** 观测库文件，供排查与清理使用。 */
    fun fileFor(context: Context): File = WorldDatabaseProvider.fileFor(context)

    /**
     * 写入一条观测，返回**实际落库的条目 id**（写失败或不需要写时为空串）。
     *
     * 返回值不是可有可无的便利：同签名且内容未变时不写新行，此时实际存在的是**旧行的
     * id**，与调用方自己算出来的签名并不相同。图里的边必须以这个 id 为端点，否则
     * 「共享文件」会指向一个数据库里根本不存在的节点，聚类时那部分连接会静默丢失。
     *
     * 同签名且内容未变时不写（见 [WorldKnowledgeLogic.shouldWrite]）；内容变了则覆盖更新，
     * 避免同一条知识堆积多个版本。
     */
    fun write(
        context: Context?,
        entry: Entry,
    ): String {
        if (context == null) return ""
        return try {
            // 固定走 IO 调度器：本类的调用点分布在工具执行、事件回收等多条路径上，
            // 不能让某一条恰好落在主线程时把磁盘 IO 带到主线程上。
            runBlocking(Dispatchers.IO) {
                val db = WorldDatabaseProvider.get(context)
                // 查重、写入、过期清理、裁剪放进同一个事务：否则并发写入时
                // “查到不存在→两个都写”会各插一条，去重就失效了。
                db.withTransaction {
                    val dao = db.knowledgeDao()
                    val existing = dao.latestBySignature(entry.kind, entry.signature)
                    // 内容没变时沿用旧行 id：这条观测已经存在，边应当连到它身上。
                    val targetId = existing?.id ?: UUID.randomUUID().toString()
                    if (WorldKnowledgeLogic.shouldWrite(entry.kind, entry.signature, entry.summary, existing)) {
                        dao.upsert(entry.toEntity(targetId))
                    }
                    dao.deleteExpired(System.currentTimeMillis())
                    dao.trim(KEEP_ENTRIES)
                    // 顺手回收修复之前入库的不可复用条目：它们读不出来（见 [search] 与
                    // `AgentRecallFormat`），占着的却是保留名额。
                    purgeUnusableWithin(dao)
                    // 已有旧行且内容未变时，旧行仍在（未被 trim 掉的），照常返回它的 id；
                    // 但若刚才的 trim 把它裁掉了，返回空串以免图上留下悬空引用。
                    if (dao.exists(targetId) > 0) targetId else ""
                }
            }
        } catch (error: Throwable) {
            // 记录观测不影响本轮运行，但降级要留痕。
            WorldHealth.recordDegradation("knowledge.write(${entry.kind})", error)
            ""
        }
    }

    /**
     * 按签名取历史观测，并逐条做新鲜度校验。
     *
     * 返回的文本已经带好"这条结论是否仍然成立"的标注——不做这一步，历史结论会变成
     * 幻觉温床：模型看不出它引用的文件早已改过。
     */
    fun recall(
        context: Context?,
        kind: String,
        signature: String,
        readContent: (String) -> String?,
        nowMs: Long = System.currentTimeMillis(),
    ): Recalled? {
        if (context == null || signature.isBlank()) return null
        return try {
            val entity = runBlocking(Dispatchers.IO) {
                WorldDatabaseProvider.get(context).knowledgeDao().latestBySignature(kind, signature)
            } ?: return null
            if (entity.expiresAt != 0L && entity.expiresAt <= nowMs) return null
            val dependencies = WorldKnowledgeLogic.decodeDependencies(entity.dependencies)
            val freshness = WorldKnowledgeLogic.checkFreshness(dependencies, readContent)
            Recalled(
                id = entity.id,
                kind = entity.kind,
                scope = entity.scope,
                summary = entity.summary,
                evidence = entity.evidence,
                uncertainty = entity.uncertainty,
                createdAt = entity.createdAt,
                freshness = freshness,
                sensitive = entity.sensitive,
            )
        } catch (error: Throwable) {
            // 读失败必须留痕：返回 null 会被上层理解成「没有这条历史」，
            // 而真实原因可能是库坏了——两者对模型的含义完全不同。
            WorldHealth.recordDegradation("knowledge.recall($kind)", error)
            null
        }
    }

    /** 供测试重置单例。 */
    internal fun closeForTests() {
        WorldDatabaseProvider.closeForTests()
    }

    /**
     * run 启动时做一次幂等维护：回填坐标、回收形态不可复用的存量条目。
     *
     * 两件事都放在这里而不是只靠写入路径：写入在某些场景下不会发生（一个只读的 run
     * 不会产生任何条目），而那些早于判据上线就已入库的坏数据会一直留在库里，既占保留
     * 名额，也占检索窗口。启动点每次都会经过，因此不依赖下一次写入。
     *
     * 幂等性：坐标回填只更新 `scope = ''` 的行，清除只删判据不认的条目，两者第二次
     * 调用都影响 0 行，可以无脑重复执行，不需要「已处理」标记位。
     */
    fun maintain(context: Context?, scope: String) {
        if (context == null) return
        try {
            runBlocking(Dispatchers.IO) {
                val db = WorldDatabaseProvider.get(context)
                db.withTransaction {
                    if (scope.isNotBlank()) {
                        db.knowledgeDao().backfillScope(scope)
                        db.traceDao().backfillScope(scope)
                    }
                    purgeUnusableWithin(db.knowledgeDao())
                }
            }
        } catch (error: Throwable) {
            WorldHealth.recordDegradation("knowledge.maintain", error)
        }
    }

    /**
     * 观测库的可注入概览：可复用结论与失败教训各多少条、最近一条结论多久之前。
     *
     * 注入端只报这几个数，不搬运结论正文——「世界里有这些东西」是模型需要知道的，
     * 具体内容交给它自己按需检索。只发两条 COUNT，不实体化任何条目。
     */
    data class Stats(
        val findings: Int,
        val failures: Int,
        val newestFindingAtMs: Long,
    ) {
        val isEmpty: Boolean get() = findings == 0 && failures == 0
    }

    fun stats(context: Context?, nowMs: Long = System.currentTimeMillis()): Stats {
        if (context == null) return Stats(0, 0, 0L)
        return try {
            runBlocking(Dispatchers.IO) {
                val dao = WorldDatabaseProvider.get(context).knowledgeDao()
                Stats(
                    findings = dao.countByKind(KIND_FINDING, nowMs),
                    failures = dao.countByKind(KIND_FAILURE, nowMs),
                    newestFindingAtMs = dao.newestAtByKind(KIND_FINDING, nowMs),
                )
            }
        } catch (error: Throwable) {
            // 报不出数时按「没有数据」处理，但必须留痕：否则「世界是空的」会被当成真的空。
            WorldHealth.recordDegradation("knowledge.stats", error)
            Stats(0, 0, 0L)
        }
    }

    /**
     * 记一次取用：本次检索命中的条目累加 helpful。
     *
     * 这是「痕迹有没有用」的唯一依据，不由外部判定也不靠关键词猜：被取用的上浮，
     * 没人取用的自然沉底。计分是运行副产物，写失败只留痕，不影响检索结果本身。
     */
    fun markHelpful(context: Context?, ids: List<String>) {
        if (context == null || ids.isEmpty()) return
        try {
            runBlocking(Dispatchers.IO) {
                WorldDatabaseProvider.get(context).knowledgeDao().markHelpful(ids)
            }
        } catch (error: Throwable) {
            WorldHealth.recordDegradation("knowledge.markHelpful", error)
        }
    }

    /**
     * 清除形态不可复用的存量条目，返回删除条数。
     *
     * 写入侧只拦得住新数据，而修复之前入库的条目（工具输出流水账、交白卷）会一直留在
     * 库里占着保留名额与检索窗口。这里据同一套判据回收，由 [write] 在它已有的写事务里
     * 顺带调用，不必等下一次 App 启动。
     *
     * 判据只有形态，不含长度：长度不是价值判据，长结论仍可能是有用的（见
     * [WorldKnowledgeLogic.reusableSummary]）。
     */
    private suspend fun purgeUnusableWithin(dao: WorldKnowledgeDao): Int {
        val unusable = dao.recentAny(PURGE_SCAN_LIMIT)
            .filterNot { WorldKnowledgeLogic.isUsableStoredSummary(it.summary) }
            .map { it.id }
        if (unusable.isEmpty()) return 0
        dao.deleteByIds(unusable)
        return unusable.size
    }

    /** 一次清除扫描的条目上限；照保留上限取即可，库里不会多于这个量级。 */
    private const val PURGE_SCAN_LIMIT = KEEP_ENTRIES

    /** 观测库里失败教训的种类标记，与 [AgentModelClient] 写入时一致。 */
    const val KIND_FAILURE = "failure"

    /** 写入时用的条目；字段与 [WorldKnowledgeEntity] 一一对应。 */
    data class Entry(
        val kind: String,
        val signature: String,
        val summary: String,
        val evidence: String = "",
        val uncertainty: String = "",
        val originRun: String = "",
        val originSession: String = "",
        val originAgent: String = "",
        val payload: String = "",
        val dependencies: List<WorldKnowledgeLogic.Dependency> = emptyList(),
        val sensitive: Boolean = false,
        /**
         * 该条观测的空间坐标（工作区根路径）。
         *
         * 空串表示「坐标未知」：调用方（如失败学习这类不依附工作区的条目）可能拿不到
         * 工作区根，此时按最远处理而不是拒绝写入——不知道坐标的观测仍然发生过。
         */
        val scope: String = "",
        val createdAt: Long = System.currentTimeMillis(),
        val ttlMs: Long = DEFAULT_TTL_MS,
    ) {
        fun toEntity(id: String): WorldKnowledgeEntity = WorldKnowledgeEntity(
            id = id,
            kind = kind,
            signature = signature,
            createdAt = createdAt,
            expiresAt = if (ttlMs <= 0) 0 else createdAt + ttlMs,
            originRun = originRun,
            originSession = originSession,
            originAgent = originAgent,
            summary = summary,
            evidence = evidence,
            uncertainty = uncertainty,
            payload = payload,
            dependencies = WorldKnowledgeLogic.encodeDependencies(dependencies),
            sensitive = sensitive,
            scope = scope,
        )
    }

    /** 读回的一条观测，附带新鲜度判定。 */
    data class Recalled(
        /** 条目 id：排序后要按它把结果映射回原文，也是取用计分的锚点。 */
        val id: String,
        /** 条目种类：参与重要度打分。 */
        val kind: String,
        /** 空间坐标：参与空间距离打分。 */
        val scope: String,
        val summary: String,
        val evidence: String,
        val uncertainty: String,
        val createdAt: Long,
        val freshness: WorldKnowledgeLogic.Freshness,
        val sensitive: Boolean,
        /** 取用/误导/曝光计数，参与取用率因子。默认 0 供单测直接构造。 */
        val helpful: Int = 0,
        val harmful: Int = 0,
        val exposed: Int = 0,
    )

    /**
     * 按关键词检索历史结论，并按四项打分排序后返回。
     *
     * 检索同时覆盖结论、证据与不确定项，并逐条做新鲜度校验：读回来的是「可以拿去做判断的
     * 结论」，不是一段未经核实的旧文本——依赖已变更的条目会被标注出来（见 [formatRecall]），
     * 而不是静默当作事实。
     *
     * 关键词用 `LIKE %词%` 而不是全文索引：本库是运行副观察，规模在几百条量级，
     * 全文索引带来的维护成本（额外的影子表与同步逻辑）大于收益。
     *
     * **先取宽候选窗再排序**：SQL 只按时间倒序，若直接按 [limit] 截断，排序就只能在
     * 「最近的若干条」里做，而真正相关的那条可能排在第 20 位——等于排序没起作用。
     * 多取的代价只是几十行的实体化，收益是排序真的作用在全部命中上。
     */
    fun search(
        context: Context?,
        query: String,
        limit: Int = DEFAULT_SEARCH_LIMIT,
        readContent: (String) -> String? = { null },
        nowMs: Long = System.currentTimeMillis(),
        currentScope: String = "",
    ): List<Recalled> {
        if (context == null || query.isBlank()) return emptyList()
        return try {
            val pattern = "%${escapeLike(query.trim())}%"
            val window = (limit * CANDIDATE_MULTIPLIER).coerceAtMost(MAX_CANDIDATE_WINDOW)
            val candidates = runBlocking(Dispatchers.IO) {
                WorldDatabaseProvider.get(context).knowledgeDao()
                    .search(nowMs, pattern, window)
            }
                // 形态不可复用的存量条目不出现在检索结果里：它没有可复用的知识，返回它
                // 只会让模型把一段工具输出当成结论。写入侧已拦新数据，这里兜住存量。
                .filter { WorldKnowledgeLogic.isUsableStoredSummary(it.summary) }
                .map { entity -> entity.toRecalled(readContent) }
            rank(candidates, query, currentScope, nowMs).take(limit)
        } catch (error: Throwable) {
            // 返回空列表与「真的没查到」无法区分，必须留痕。
            WorldHealth.recordDegradation("knowledge.search", error)
            emptyList()
        }
    }

    /**
     * 按四项打分重排候选：recency / importance / relevance / space。
     *
     * 公式与取值依据见 [WorldScore]。只有一条候选时直接返回——单条候选下 min-max 归一
     * 会把每一项都归成 1，排序结果与不排序相同，省掉一次无意义的计算。
     */
    private fun rank(
        candidates: List<Recalled>,
        query: String,
        currentScope: String,
        nowMs: Long,
    ): List<Recalled> {
        if (candidates.size <= 1) return candidates
        val terms = WorldScore.termsOf(query)
        val scored = WorldScore.rank(
            candidates = candidates.map { entry ->
                WorldScore.Candidate(
                    id = entry.id,
                    scope = entry.scope,
                    // 用创建时间当「上次访问时间」：本库目前不记录访问，而创建时间是
                    // 唯一可靠的时间信号。记录访问需要在每次检索时回写，那是写放大，
                    // 收益只是让 recency 更精确一点，不划算。
                    lastAccessMs = entry.createdAt,
                    importance = WorldScore.importanceOf(
                        kind = entry.kind,
                        hasEvidence = entry.evidence.isNotBlank(),
                        hasUncertainty = entry.uncertainty.isNotBlank(),
                    ) * WorldScore.usageFactor(entry.helpful, entry.harmful, entry.exposed),
                    relevance = WorldScore.relevanceFor(
                        text = "${entry.summary}\n${entry.evidence}\n${entry.uncertainty}",
                        terms = terms,
                    ),
                )
            },
            currentScope = currentScope,
            nowMs = nowMs,
        )
        // 按 id 映射回原文：打分只携带 id，避免把整条文本在排序里搬来搬去。
        val byId = candidates.associateBy { it.id }
        return scored.mapNotNull { byId[it.id] }
    }

    /**
     * 取最近的历史结论，供 run 启动时做一次轻量注入。
     *
     * 只取少量（见 [DEFAULT_INJECT_LIMIT]）：按 Anthropic 的 context engineering 结论，
     * 记忆的正确用法是「just in time 按需取」而非「预先全量加载」，启动注入只负责让模型
     * 知道「世界里有这些东西」，细节留给它自己用工具查。
     */
    fun recentFindings(
        context: Context?,
        limit: Int = DEFAULT_INJECT_LIMIT,
        readContent: (String) -> String? = { null },
        nowMs: Long = System.currentTimeMillis(),
    ): List<Recalled> {
        if (context == null) return emptyList()
        return try {
            runBlocking(Dispatchers.IO) {
                WorldDatabaseProvider.get(context).knowledgeDao()
                    // 多取几倍再过滤：读取侧要滤掉写入侧尚未设防时落库的原始工具输出，
                    // 按 limit 取就会滤剩不足。存量比例不高，三倍足够覆盖。
                    .recentByKind(KIND_FINDING, nowMs, limit * INJECT_OVERFETCH_RATIO)
            }
                // 该条已过期却仍被判为结论形态的存量条目，不注入——它对任何后续任务
                // 都没有可复用知识，还会占掉真结论的名额。
                .filterNot { WorldKnowledgeLogic.isRawToolOutputConclusion(it.summary) }
                .take(limit)
                .map { entity -> entity.toRecalled(readContent) }
        } catch (error: Throwable) {
            // 启动注入拿不到历史结论时表现为「以前什么都没做过」，同样需要留痕。
            WorldHealth.recordDegradation("knowledge.recentFindings", error)
            emptyList()
        }
    }

    /** 注入前多取几倍候选，抵消读取侧过滤掉存量垃圾条目带来的空缺。 */
    private const val INJECT_OVERFETCH_RATIO = 3

    private fun WorldKnowledgeEntity.toRecalled(readContent: (String) -> String?): Recalled {
        val dependencies = WorldKnowledgeLogic.decodeDependencies(dependencies)
        return Recalled(
            id = id,
            kind = kind,
            scope = scope,
            summary = summary,
            evidence = evidence,
            uncertainty = uncertainty,
            createdAt = createdAt,
            freshness = WorldKnowledgeLogic.checkFreshness(dependencies, readContent),
            sensitive = sensitive,
            helpful = helpful,
            harmful = harmful,
            exposed = exposed,
        )
    }

    /** `LIKE` 的通配符要转义，否则查询词里的 `%` 会变成通配。 */
    private fun escapeLike(value: String): String =
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    /** 一次检索最多返回多少条。 */
    const val DEFAULT_SEARCH_LIMIT = 8

    /**
     * 候选窗放大倍数：多取几倍候选交给排序，最后再截到 [DEFAULT_SEARCH_LIMIT]。
     *
     * 直接按 limit 截断会让排序失去意义——SQL 只能按时间倒序，真正相关的那条若排在
     * 第 20 位就永远进不了视野。取 4 倍是在「排序有效」与「多实体化的成本」之间的平衡。
     */
    private const val CANDIDATE_MULTIPLIER = 4

    /** 候选窗上限：再多也不值得为一次检索全部读出来。 */
    private const val MAX_CANDIDATE_WINDOW = 64

    /** 启动时最多注入多少条历史结论。 */
    const val DEFAULT_INJECT_LIMIT = 5

    /** 观测库里「子智能体结论」的种类标记，与 [WorldTraceStore] 写入时一致。 */
    const val KIND_FINDING = "finding"
}
