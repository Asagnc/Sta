package io.github.asagnc.sta.data.world

import kotlin.math.pow

/**
 * 观测条目的检索打分（纯逻辑，可 JVM 单测）。
 *
 * 公式严格取自 Generative Agents（arXiv:2304.03442）的记忆检索：
 *
 * ```
 * score = α_recency · recency + α_importance · importance + α_relevance · relevance
 * ```
 *
 * 原文的三个做法原样保留：
 * - 三项各自 **min-max 归一化到 [0,1]** 后再相加
 * - 权重 α **全为 1**（原文即如此，没有调参）
 * - recency 是**指数衰减**，decay factor **0.995**，按「距今小时数」计
 *
 * 在此基础上**加了第四项 space**——这是 Sta 特有的，因为原文的 agent 没有工作区概念。
 * 加它的依据是 Anthropic 的 *Effective context engineering*：folder hierarchies 提供
 * 重要信号，同名文件在 `tests/` 与 `src/core_logic/` 语义不同。也就是说**位置本身
 * 就是相关性的一部分**，不只是一条可以事后过滤的元数据。
 *
 * 为什么不把空间做成「硬过滤」而做成打分项：硬过滤会在「当前空间没有结果」时返回空，
 * 而别处的历史结论往往仍有参考价值（同一个项目跨目录、同一类问题的通用解法）。做成
 * 打分项则两者都能取到，只是排序不同——这正是我们要的。
 */
internal object WorldScore {

    /** recency 的指数衰减因子，取自原文。 */
    const val RECENCY_DECAY = 0.995

    /**
     * 单项权重，原文全为 1。
     *
     * 保持 1 而不是自行调参：原文的取值是在完整实现上验证过的，而我们的第四项 space
     * 与前三项同名同量级，用同样的权重才不至于让某一项单独主导排序。
     */
    const val ALPHA_RECENCY = 1.0
    const val ALPHA_IMPORTANCE = 1.0
    const val ALPHA_RELEVANCE = 1.0
    const val ALPHA_SPACE = 1.0

    /**
     * 一条条目的原始分数项（尚未归一化）。
     *
     * 归一化必须**在整批候选上一起做**，所以这里只算原始值，由 [rank] 统一处理。
     */
    data class Raw(
        val id: String,
        val recency: Double,
        val importance: Double,
        val relevance: Double,
        val space: Double,
    )

    /**
     * 对一批候选打分并排序，返回按分数降序的 id 列表。
     *
     * [scopeOf] 给出候选所属空间，[currentScope] 是本次检索所在空间。空间分的含义：
     * 同一空间得满分，不同空间得 0 分，中间由 [spaceScore] 按路径层级插值。
     */
    fun rank(
        candidates: List<Candidate>,
        currentScope: String,
        nowMs: Long,
    ): List<Scored> {
        if (candidates.isEmpty()) return emptyList()
        val raws = candidates.map { candidate ->
            Raw(
                id = candidate.id,
                recency = recency(candidate.lastAccessMs, nowMs),
                importance = candidate.importance,
                relevance = candidate.relevance,
                space = spaceScore(candidate.scope, currentScope),
            )
        }
        // 三项一起做 min-max 归一：单独归一会让「只有一条候选」时该项恒为满分，
        // 权重也就失去了意义。
        val recencyNorm = normalize(raws.map { it.recency })
        val importanceNorm = normalize(raws.map { it.importance })
        val relevanceNorm = normalize(raws.map { it.relevance })
        val spaceNorm = normalize(raws.map { it.space })
        return raws.mapIndexed { index, raw ->
            Scored(
                id = raw.id,
                score = ALPHA_RECENCY * recencyNorm[index] +
                    ALPHA_IMPORTANCE * importanceNorm[index] +
                    ALPHA_RELEVANCE * relevanceNorm[index] +
                    ALPHA_SPACE * spaceNorm[index],
            )
        }.sortedWith(compareByDescending<Scored> { it.score }.thenBy { it.id })
    }

    /** 参与打分的候选。 */
    data class Candidate(
        val id: String,
        /** 所属空间（工作区根路径）；空串表示坐标未知。 */
        val scope: String,
        /** 上次被访问的时间；从未访问过时传创建时间。 */
        val lastAccessMs: Long,
        /** 重要度 0..1，由调用方给出。 */
        val importance: Double,
        /** 关键词/符号命中度 0..1，由调用方给出。 */
        val relevance: Double,
    )

    /** 打分结果。 */
    data class Scored(val id: String, val score: Double)

    /**
     * recency：`0.995 ^ 距今小时数`（原文 decay factor）。
     *
     * 时间在未来（时钟回拨）时按 0 小时处理而不是产生大于 1 的分数——否则一条时间戳
     * 异常的条目会永久霸占首位，而那是数据问题、不是它更相关。
     */
    fun recency(lastAccessMs: Long, nowMs: Long): Double {
        val elapsedHours = ((nowMs - lastAccessMs).coerceAtLeast(0L)) / 3_600_000.0
        return RECENCY_DECAY.pow(elapsedHours)
    }

    /**
     * 空间分：两点在作用域树上越近，分越高。
     *
     * 判据是**公共路径前缀**而不是字符串相等：`/ws/proj/agent/model` 与
     * `/ws/proj/agent/runtime` 同属 agnet 层，比与 `/ws/proj/tools` 更近。这与
     * Anthropic 说的「位置是语义信号」一致——距离要按层级量，不能按是否相同量。
     *
     * 坐标未知（空串）按最低分处理而不是直接排除：不知道坐标的观测仍然发生过，
     * 只是不作为优先项。
     */
    fun spaceScore(scopeA: String, scopeB: String): Double {
        if (scopeA.isBlank() || scopeB.isBlank()) return 0.0
        if (scopeA == scopeB) return 1.0
        val segmentsA = segmentsOf(scopeA)
        val segmentsB = segmentsOf(scopeB)
        var shared = 0
        while (shared < segmentsA.size && shared < segmentsB.size &&
            segmentsA[shared] == segmentsB[shared]
        ) {
            shared++
        }
        if (shared == 0) return 0.0
        // 公共前缀长度 / 较深者深度：同在一个子目录下时得分接近 1，只共享根目录时
        // 得分接近 0，不会因为路径长短本身影响结果。
        val depth = maxOf(segmentsA.size, segmentsB.size)
        return shared.toDouble() / depth
    }

    /** 路径切段：去掉空段与末尾分隔符，使 `/a/b/` 与 `/a/b` 等价。 */
    private fun segmentsOf(path: String): List<String> =
        path.split('/').filter { it.isNotBlank() }

    /**
     * min-max 归一化到 [0,1]。
     *
     * 全部相同（含只有一个候选）时统一返回 **1.0 而不是 0**：此时这项无法区分候选，
     * 归成 0 会让这一项在总分为零的情况下被「抹掉」，而归成 1 则保持各项等权。
     * 两种做法对排序结果等价（同加常数不改变相对顺序），但后者让分数可读。
     */
    fun normalize(values: List<Double>): List<Double> {
        if (values.isEmpty()) return emptyList()
        val min = values.min()
        val max = values.max()
        val span = max - min
        if (span <= 0.0) return values.map { 1.0 }
        return values.map { (it - min) / span }
    }

    /**
     * 关键命中度：命中词数与查询词数的比值，天然落在 [0,1]。
     *
     * 用比值而不是命中个数：后者会让长文本天然得分高（它的词多），而长文本并不因此
     * 更相关。这与建边时用 Jaccard 而非共享个数的理由相同。
     */
    fun relevanceOf(hits: Int, queryTerms: Int): Double {
        if (queryTerms <= 0) return 0.0
        return (hits.toDouble() / queryTerms).coerceIn(0.0, 1.0)
    }

    /**
     * 重要度：按条目种类与来源给出 0..1 的基数。
     *
     * 取值依据「哪类结论更可能影响后续决策」而不是随便定：失败教训高于普通发现，
     * 因为它直接改变做法；带证据与不确定项的结论再加权，因为前者更可信、后者更克制。
     *
     * [kind] 用字符串而非枚举：种类标记由写入方（[WorldKnowledgeStore.KIND_FINDING]、
     * `AgentModelClient` 的 `KIND_FAILURE`）以常量形式给出，这里只按值匹配，不引入
     * 一个新的类型层次。
     */
    fun importanceOf(kind: String, hasEvidence: Boolean, hasUncertainty: Boolean): Double {
        val base = when (kind) {
            KIND_FAILURE -> 0.9
            KIND_DECISION -> 0.8
            KIND_FINDING -> 0.6
            else -> 0.5
        }
        var value = base
        // 带证据的结论更可信；明确写出不确定项的结论更有用（它告诉模型哪里不能当真）。
        if (hasEvidence) value += 0.05
        if (hasUncertainty) value += 0.05
        return value.coerceIn(0.0, 1.0)
    }

    /**
     * 取用率因子：`(1 + helpful) / (1 + harmful + exposed)`。
     *
     * 无任何阈值、也不依赖时间：分子分母都是这项痕迹自己的历史，因此排序完全由条目之间
     * 的相对取用情况决定，不需要外部定下「多少次算没用」或「多少天算过期」。
     *
     * 分母刻意不含 helpful——含了它会得出 `(1+h)/(1+h+bad)`，而 `bad = 0` 时那个式子
     * 恒等于 1，无论被取用多少次，取用记录等于没有。分母只统计「被记下的负面信号」
     * （误导与看过没用），于是：全新条目得 1.0，用过 5 次得 6.0（上浮），
     * 用过 5 次却曝光 10 次得 0.55（沉底）。加 1 是平滑项，避免除零，
     * 也让首次曝光不至于把一条痕迹直接判死。
     */
    fun usageFactor(helpful: Int, harmful: Int, exposed: Int): Double {
        val h = helpful.coerceAtLeast(0)
        val bad = harmful.coerceAtLeast(0) + exposed.coerceAtLeast(0)
        return (h + 1).toDouble() / (bad + 1).toDouble()
    }

    /**
     * 把查询串切成检索词。
     *
     * 与实体抽取的切分口径一致（英文按分隔符、中文按二元），
     * 理由是两处若不一致，「写入时认为相关的」与「检索时认为相关的」就会是两套标准。
     */
    fun termsOf(query: String): List<String> {
        if (query.isBlank()) return emptyList()
        val terms = linkedSetOf<String>()
        query.split(WHITESPACE).forEach { piece ->
            val word = piece.trim().lowercase()
            if (word.isEmpty()) return@forEach
            if (word.any { it.code in CJK_RANGE }) {
                // 中文片段：长度 ≥2 时切二元组，单字直接作为词。
                if (word.length == 1) {
                    terms.add(word)
                } else {
                    for (index in 0..word.length - 2) {
                        terms.add(word.substring(index, index + 2))
                    }
                }
            } else {
                terms.add(word)
            }
        }
        return terms.toList()
    }

    /**
     * 一段文本对查询词的相关度。
     *
     * 命中判定用 `contains` 而非整词匹配：中文没有词边界，而英文侧用整词匹配会让
     * 查 `AgentLoop` 时漏掉提到 `AgentLoopTest` 的条目——那显然相关。宁可略宽，因为
     * 相关度只是四项之一，排序还有时间、重要度、空间三项兜底。
     */
    fun relevanceFor(text: String, terms: List<String>): Double {
        if (terms.isEmpty() || text.isBlank()) return 0.0
        val haystack = text.lowercase()
        val hits = terms.count { haystack.contains(it) }
        return relevanceOf(hits, terms.size)
    }

    /** 查询串的分隔符：空白与中英文标点都要切，否则「a，b」会被当成一个词。 */
    private val WHITESPACE = Regex("[\\s,，、;；:：/\\\\|]+")

    /** 中日韩统一表意文字范围，用于识别中文片段。 */
    private val CJK_RANGE = 0x4e00..0x9fa5

    /** 失败教训：直接改变做法，排在普通发现之前。 */
    private const val KIND_FAILURE = "failure"

    /** 决策：定下了做法，后续都受它约束。 */
    private const val KIND_DECISION = "decision"

    /** 普通发现，与 [WorldKnowledgeStore.KIND_FINDING] 同一取值。 */
    private const val KIND_FINDING = "finding"
}