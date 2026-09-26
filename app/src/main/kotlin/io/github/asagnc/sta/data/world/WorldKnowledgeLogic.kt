package io.github.asagnc.sta.data.world

import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/**
 * 知识条目的纯逻辑部分：序列化、去重判据、新鲜度校验。
 *
 * 单独成文件而不是塞进 [WorldKnowledgeStore]，是为了让这些判据能在纯 JVM 单测里覆盖
 * ——它们决定"读回来的历史结论还算不算数"，错了会静默污染后续推理，必须有测试守着。
 */
internal object WorldKnowledgeLogic {

    /** 依赖文件在入库时的指纹。 */
    data class Dependency(
        val path: String,
        val fingerprint: String,
    )

    /** 新鲜度校验结果。 */
    enum class Freshness {
        /** 依赖没变，结论仍然成立。 */
        FRESH,

        /** 依赖已变更，结论可能失效。 */
        STALE,

        /** 依赖文件已不存在。 */
        MISSING,
    }

    /**
     * 「查不到」型的结论标记：结论一开头就声明自己没查出来。
     *
     * 这类结论没有可复用的知识，却会被当作历史结论每轮注入——实测一条 229 字、开头
     * 就写「我没有任何联网检索能力……无法回答」的报告，每轮都在吃掉注意力预算，
     * 而它对任何后续任务都没有价值。
     *
     * 只收「自述能力缺失」这一类措辞，刻意不收两个看起来更直接的词：
     * 「无法给出结论」在有产出的报告里被用作覆盖范围的声明（实测「未读完的 58 个文件
     * 无法给出结论」紧跟在一组确证发现之后），收它会误杀整条真结论；
     * 「没有联网工具」/「无联网工具」是泛化说法，边界用例正是靠它出现在第 113 个字符处
     * 验证「开头之外的提及不该判空集」，收它会把那条边界一起推翻。
     *
     * 用关键词而不是语义判定：宁可漏判（照旧入库），也不要误杀正常结论。
     */
    private val INCONCLUSIVE_MARKERS = listOf(
        "无法回答",
        "无法核实",
        "未能确认",
        "未能核实",
        "无法访问",
        "无法联网",
        "不具备联网",
        "联网检索能力",
        "没有可用的联网工具",
        "没有可用的浏览器",
        "没有可用的网络",
        "本地无网络",
    )

    /**
     * 结论开头多少个字符内命中标记才算空集。
     *
     * 取 120 而不是更短的窗口：这类报告常在交白卷之前先列一遍自己有哪些工具，
     * 那串工具名本身就占掉近百字符（实测「无法回答」出现在第 97 个字符）。
     */
    private const val INCONCLUSIVE_HEAD_CHARS = 120

    /**
     * 极短结论的判定长度：整段话就这么长且带否定词时，它除了一句「没查出来」没有别的信息。
     *
     * 这一条是为了覆盖不出现于标记表里的简短措辞（如「无法得出结论。」），而不必为此
     * 把「无法给出结论」也列进去——那个词在有产出的报告里被用作覆盖范围的声明
     * （实测「未读完的 58 个文件无法给出结论」紧跟在一组确证发现之后），当成空集标记会误杀。
     */
    private const val BARE_DISCLAIMER_CHARS = 30

    private val BARE_DISCLAIMER_NEGATIONS = listOf("无法", "未能", "不能", "没有", "不具备")

    /** 这条结论是不是「查不到」型的空集结论（不该入库）。 */
    fun isInconclusiveConclusion(conclusion: String): Boolean {
        val flat = flatten(conclusion)
        if (flat.isEmpty()) return true
        if (flat.length <= BARE_DISCLAIMER_CHARS &&
            BARE_DISCLAIMER_NEGATIONS.any { flat.contains(it) }
        ) {
            return true
        }
        val head = flat.take(INCONCLUSIVE_HEAD_CHARS)
        return INCONCLUSIVE_MARKERS.any { head.contains(it) }
    }

    /**
     * 把一条委派结论整理成可入库的 summary；不可复用时返回 null。
     *
     * 只做形态筛除，**不按长度截断**。截断换来的是存储上少几个字符，代价是丢掉结论里的
     * 细节——而被丢掉的往往正是后来才显得关键的那部分（Agentic Context Engineering 把
     * 这种损失命名为 brevity bias，列为语境演化要避免的首要失败模式）。体量由使用侧处理：
     * 注入只渲染标题（见 [titleOf]），检索结果的展示由 [oneLine] 封顶。
     */
    fun reusableSummary(conclusion: String): String? {
        val flat = flatten(conclusion)
        if (flat.isEmpty()) return null
        if (isInconclusiveConclusion(flat)) return null
        if (isRawToolOutputConclusion(flat)) return null
        return flat
    }

    /**
     * 存量条目是否仍算可复用。
     *
     * 读回来的条目要再过一遍写入侧那套判据（不含长度——长度不是价值判据）：形态不可复用
     * 的条目仍在库里，它们不该出现在注入或检索结果里，只应由清除路径回收。
     */
    fun isUsableStoredSummary(summary: String): Boolean {
        val flat = flatten(summary)
        if (flat.isEmpty()) return false
        if (isInconclusiveConclusion(flat)) return false
        return !isRawToolOutputConclusion(flat)
    }

    /** 注入时给单条结论的标题长度上限。 */
    const val MAX_TITLE_CHARS = 60

    /** 句末标点：标题在第一个句末处收住，避免把一个从句当作完整标题。 */
    private const val TITLE_TERMINATORS = "。！？"

    /**
     * 结论的短标题。
     *
     * 注入只用它，完整 summary 仍存库、由 `world_recall` 按需取回：这样「世界里有这些
     * 结论」对模型可见，而每轮注入的体量不随结论长短变化。只按句末标点切分，
     * 不按英文句点——工具名与文件路径里都有 `.`，按它切会切出半截词。
     */
    fun titleOf(summary: String): String {
        val flat = flatten(summary)
        if (flat.length <= MAX_TITLE_CHARS) return flat
        val head = flat.take(MAX_TITLE_CHARS)
        val end = head.indexOfFirst { it in TITLE_TERMINATORS }
        // 终止符落在首字符时不予采信：那更可能是标题前的一个残余标点。
        return if (end >= 1) head.take(end + 1) else head.take(MAX_TITLE_CHARS - 1) + "…"
    }

    /**
     * 原始工具输出冒充结论的判定。
     *
     * 执行方偶发把工具结果原样当结论交回，实测形如
     * `命令1: ok=true exit_code=0 stdout前400字符=...`。这类文本没有可复用知识，却因为
     * 写入时间最新而在启动注入时占掉名额，把真结论挤出去——比「查不到」型空集更差：
     * 空集至少告诉了模型「这条路不通」，而它只是一段目录列表。
     *
     * 只看开头：真结论也会引用命令输出（如「调用链是 runCommandRaw → ...」），
     * 但不会一上来就是 `命令N:` 加退出码的流水账，所以两个特征同时命中才算。
     * 与空集判定同一种取向：宁可漏判（照旧入库），也不要误杀正常结论。
     */
    fun isRawToolOutputConclusion(conclusion: String): Boolean {
        val flat = flatten(conclusion)
        return RAW_TOOL_OUTPUT_PREFIX.containsMatchIn(flat) && flat.contains(RAW_TOOL_OUTPUT_MARKER)
    }

    /** `命令1:` / `命令 1：` 这类结果流水账的开头。 */
    private val RAW_TOOL_OUTPUT_PREFIX = Regex("^命令\\s*\\d+\\s*[:：]")

    /** 退出码字段：与开头特征同时命中才判为原始输出。 */
    private const val RAW_TOOL_OUTPUT_MARKER = "exit_code="

    /**
     * 把依赖列表编码成 JSON 数组。
     *
     * 空列表编码成空串而不是 `[]`：数据库里空串代表"没有依赖"，读回时不用区分
     * "空数组"和"没有"两种情况。
     */
    fun encodeDependencies(dependencies: List<Dependency>): String {
        if (dependencies.isEmpty()) return ""
        val array = JSONArray()
        dependencies.forEach { dependency ->
            array.put(
                JSONObject()
                    .put("path", dependency.path)
                    .put("fingerprint", dependency.fingerprint),
            )
        }
        return array.toString()
    }

    /** 解码依赖列表。解不开的条目跳过而不是整段丢弃——单条坏数据不该让整个结论失效。 */
    fun decodeDependencies(encoded: String): List<Dependency> {
        if (encoded.isBlank()) return emptyList()
        val array = runCatching { JSONArray(encoded) }.getOrNull() ?: return emptyList()
        val result = mutableListOf<Dependency>()
        for (index in 0 until array.length()) {
            val item = array.optJSONObject(index) ?: continue
            val path = item.optString("path")
            if (path.isBlank()) continue
            result.add(Dependency(path = path, fingerprint = item.optString("fingerprint")))
        }
        return result
    }

    /**
     * 文件指纹：内容长度 + SHA-256 前 16 位十六进制。
     *
     * 不用 `mtime` 而用内容：`git checkout`、格式化、复制都会改 mtime 但内容不变，
     * 那会误判为"结论失效"；反过来 mtime 精度也可能让真改动看不出来。
     */
    fun fingerprint(content: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(content.toByteArray())
        val hex = digest.take(8).joinToString("") { byte -> "%02x".format(byte) }
        return "${content.length}:$hex"
    }

    /**
     * 判断一条历史结论现在还算不算数。
     *
     * 依赖为空时恒为 [Freshness.FRESH]——没有依赖的结论（例如"这个 provider 不支持某字段"）
     * 不受文件变更影响。
     */
    fun checkFreshness(
        dependencies: List<Dependency>,
        readContent: (String) -> String?,
    ): Freshness {
        if (dependencies.isEmpty()) return Freshness.FRESH
        dependencies.forEach { dependency ->
            val content = readContent(dependency.path) ?: return Freshness.MISSING
            if (fingerprint(content) != dependency.fingerprint) return Freshness.STALE
        }
        return Freshness.FRESH
    }

    /**
     * 把证据里的文件路径变成依赖指纹。
     *
     * 这一步是「世界可信」的关键：没有依赖指纹，历史结论只是一条不可验证的笔记；
     * 有了它，回读时能判断「这条结论引用的文件是否已改」，过期结论不会被当作事实注入。
     *
     * 读不到的文件直接跳过，而不是记一条空指纹——空指纹会让新鲜度校验永远判为 STALE，
     * 那等于把这条结论永久作废，比不记更糟。
     */
    fun dependenciesFor(
        paths: List<String>,
        readContent: (String) -> String?,
    ): List<Dependency> = paths.mapNotNull { path ->
        val content = readContent(path) ?: return@mapNotNull null
        Dependency(path = path, fingerprint = fingerprint(content))
    }

    /**
     * 归一证据里的文件路径，使其可用于读取。
     *
     * 子智能体按提示可能写 `/workspace/x`（Linux 侧）或 `/data/local/tmp/sta/x`（Android 侧），
     * 两者是同一个文件。相对路径按工作区根拼。返回 null 表示不是可读的绝对/相对文件引用。
     */
    fun normalizePath(path: String, workspaceRoot: String): String? {
        val trimmed = path.trim().trim('`')
        if (trimmed.isEmpty()) return null
        val android = trimmed.replace(LINUX_WORKSPACE_PREFIX, ANDROID_WORKSPACE_PREFIX)
        return when {
            android.startsWith(ANDROID_WORKSPACE_PREFIX) -> android
            android.startsWith("/") -> android
            else -> "$workspaceRoot/$android"
        }
    }

    private const val LINUX_WORKSPACE_PREFIX = "/workspace"
    private const val ANDROID_WORKSPACE_PREFIX = "/data/local/tmp/sta"

    /**
     * 把读回的历史结论格式化成可注入的文本行。
     *
     * 三个设计点都来自 Anthropic 的 context engineering 结论：
     * - **时间戳当相关性代理**：原文明确说 timestamps can be a proxy for relevance，
     *   所以每条都带「多久之前」，让模型自己判断可信度。
     * - **新鲜度必须显式标注**：依赖文件已变更的结论不能被当作事实，要写明「依赖已变更」，
     *   否则历史结论会变成幻觉温床。
     * - **敏感条目不注入正文**：只报存在，让模型自己决定要不要查。
     */
    fun formatRecall(
        entries: List<WorldKnowledgeStore.Recalled>,
        nowMs: Long,
        currentScope: String = "",
    ): String {
        if (entries.isEmpty()) return ""
        return entries.mapNotNull { entry ->
            if (entry.sensitive) return@mapNotNull null
            val age = humanAge(nowMs - entry.createdAt)
            val marker = when (entry.freshness) {
                Freshness.FRESH -> ""
                Freshness.STALE -> "，依赖的文件已变更，结论可能失效"
                Freshness.MISSING -> "，依赖的文件已不存在，结论可能失效"
            }
            // 只在跨空间时标注来源：同空间的条目不需要额外说明（它们就是"这里的"），
            // 而给每一条都加前缀会稀释真正需要引起注意的信息。这与「标注而非过滤」的
            // 取法一致——异空间的结论仍然给出，只是明写出来源，让模型自己判断。
            val origin = originLabel(entry.scope, currentScope)
            buildString {
                append("- （").append(age).append("前")
                if (marker.isNotEmpty()) append(marker)
                if (origin.isNotEmpty()) append("，来自 ").append(origin)
                append("）").append(oneLine(entry.summary))
                if (entry.evidence.isNotBlank()) {
                    append("｜证据：").append(oneLine(entry.evidence))
                }
                if (entry.uncertainty.isNotBlank()) {
                    append("｜不确定：").append(oneLine(entry.uncertainty))
                }
            }
        }.joinToString("\n")
    }

    /**
     * 异空间来源的简短标注；同空间或坐标未知时返回空串。
     *
     * 只取路径末段而不是完整路径：注入的是给模型看的上下文，`/data/local/tmp/sta/Sta-src`
     * 这种前缀在这里不携带信息（所有条目都共享它），末段才是区分点。
     */
    fun originLabel(scope: String, currentScope: String): String {
        if (scope.isBlank() || currentScope.isBlank()) return ""
        if (scope == currentScope) return ""
        return scope.trimEnd('/').substringAfterLast('/').ifBlank { scope }
    }

    /** 把时间差说成人话：模型不需要精确到毫秒的年龄。 */
    fun humanAge(elapsedMs: Long): String {
        val minutes = elapsedMs / 60_000
        return when {
            minutes < 1 -> "刚刚"
            minutes < 60 -> "$minutes 分钟"
            minutes < 60 * 24 -> "${minutes / 60} 小时"
            else -> "${minutes / (60 * 24)} 天"
        }
    }

    /**
     * 压成一行，不截断。
     *
     * 供判据与存储使用：这些位置需要看到完整文本，截断会让判据看错尾巴上的内容，
     * 也会把结论的关键细节丢在入库之前。需要控制展示体量的地方用 [oneLine]。
     */
    fun flatten(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    /**
     * 压成一行并限长，供展示使用。
     *
     * 只用在把内容拼给模型看的位置（检索结果的渲染）：那里必须给体量封顶，
     * 否则一次检索就能把上下文吃掉。存储与判据不走这里。
     */
    private fun oneLine(value: String): String =
        flatten(value).take(MAX_RECALL_CHARS)

    /** 单条结论在检索结果里展示时的字符上限。 */
    const val MAX_RECALL_CHARS = 300

    /**
     * 判断一条新观测是否值得落库。
     *
     * 同签名已存在且内容未变时不重复写——重复观测不带来新信息，只会挤占查询窗口。
     */
    fun shouldWrite(
        kind: String,
        signature: String,
        summary: String,
        existing: WorldKnowledgeEntity?,
    ): Boolean {
        if (existing == null) return true
        if (existing.kind != kind || existing.signature != signature) return true
        return existing.summary != summary
    }
}
