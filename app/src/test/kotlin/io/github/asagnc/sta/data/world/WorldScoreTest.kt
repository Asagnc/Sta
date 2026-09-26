package io.github.asagnc.sta.data.world

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 检索打分测试。
 *
 * 打分公式直接决定「模型先看到哪条历史结论」，错在这里不会报错、只会让检索结果变差，
 * 所以每一项都单独钉住。
 */
class WorldScoreTest {

    private val now = 1_700_000_000_000L
    private val hour = 3_600_000L

    private fun candidate(
        id: String,
        scope: String = "/ws/proj",
        ageHours: Double = 0.0,
        importance: Double = 0.5,
        relevance: Double = 0.5,
    ) = WorldScore.Candidate(
        id = id,
        scope = scope,
        lastAccessMs = now - (ageHours * hour).toLong(),
        importance = importance,
        relevance = relevance,
    )

    // ---- recency ----

    @Test
    fun `recency 用原文的 0_995 衰减因子`() {
        // 原文（Generative Agents）：recency 为指数衰减，decay factor 0.995，按小时计。
        assertEquals(1.0, WorldScore.recency(now, now), 1e-9)
        assertEquals(0.995, WorldScore.recency(now - hour, now), 1e-9)
        assertEquals(0.995 * 0.995, WorldScore.recency(now - 2 * hour, now), 1e-9)
    }

    @Test
    fun `recency 随时间单调下降`() {
        val fresh = WorldScore.recency(now - hour, now)
        val older = WorldScore.recency(now - 10 * hour, now)
        val oldest = WorldScore.recency(now - 100 * hour, now)
        assertTrue(fresh > older)
        assertTrue(older > oldest)
    }

    @Test
    fun `时间在未来时按 0 小时处理`() {
        // 时钟回拨会造出「未来」的条目。若允许 >1 的分数，这条会永久霸占首位，
        // 而那是数据问题、不是它更相关。
        assertEquals(1.0, WorldScore.recency(now + 10 * hour, now), 1e-9)
    }

    // ---- 空间分 ----

    @Test
    fun `同一空间得满分`() {
        assertEquals(1.0, WorldScore.spaceScore("/ws/proj/agent", "/ws/proj/agent"), 1e-9)
    }

    @Test
    fun `越近的路径分越高`() {
        val current = "/ws/proj/agent/model"
        val sibling = WorldScore.spaceScore("/ws/proj/agent/runtime", current)
        val cousin = WorldScore.spaceScore("/ws/proj/tools", current)
        val stranger = WorldScore.spaceScore("/other/proj", current)
        assertTrue("同层兄弟应比远房亲属更近", sibling > cousin)
        assertTrue("共享根目录应比完全不同更近", cousin > stranger)
    }

    @Test
    fun `坐标未知按最低分处理而不是排除`() {
        // 不知道坐标的观测仍然发生过，只是不作为优先项。
        assertEquals(0.0, WorldScore.spaceScore("", "/ws/proj"), 1e-9)
        assertEquals(0.0, WorldScore.spaceScore("/ws/proj", ""), 1e-9)
    }

    @Test
    fun `路径末尾分隔符不影响空间分`() {
        assertEquals(
            WorldScore.spaceScore("/ws/proj/agent", "/ws/proj/agent"),
            WorldScore.spaceScore("/ws/proj/agent/", "/ws/proj/agent"),
            1e-9,
        )
    }

    @Test
    fun `空间分不受路径绝对长度影响`() {
        // 判据是公共前缀 / 较深者深度，而不是前缀的绝对长度。
        val deepSame = WorldScore.spaceScore("/a/b/c/d/e", "/a/b/c/d/e")
        val shallowSame = WorldScore.spaceScore("/a", "/a")
        assertEquals(1.0, deepSame, 1e-9)
        assertEquals(1.0, shallowSame, 1e-9)
    }

    // ---- 归一化 ----

    @Test
    fun `min-max 归一化到 0 到 1`() {
        val normalized = WorldScore.normalize(listOf(1.0, 2.0, 3.0))
        assertEquals(0.0, normalized[0], 1e-9)
        assertEquals(0.5, normalized[1], 1e-9)
        assertEquals(1.0, normalized[2], 1e-9)
    }

    @Test
    fun `全部相同值归一为 1 而不是 0`() {
        // 归成 0 会让这一项在总分为零时被"抹掉"，归成 1 则保持各项等权；
        // 两种做法排序等价，但后者让分数可读。
        val normalized = WorldScore.normalize(listOf(0.7, 0.7, 0.7))
        assertTrue(normalized.all { it == 1.0 })
    }

    @Test
    fun `空列表归一返回空`() {
        assertTrue(WorldScore.normalize(emptyList()).isEmpty())
    }

    // ---- 整体排序 ----

    @Test
    fun `同空间较新较重要者排前`() {
        val ranked = WorldScore.rank(
            candidates = listOf(
                candidate("old", ageHours = 100.0, importance = 0.2, relevance = 0.2),
                candidate("new", ageHours = 0.1, importance = 0.9, relevance = 0.9),
            ),
            currentScope = "/ws/proj",
            nowMs = now,
        )
        assertEquals("new", ranked.first().id)
    }

    @Test
    fun `同空间条目优先于异空间条目`() {
        // 其余三项完全一致时，只有空间分能拉开差距。这正是"位置是语义信号"的体现。
        val ranked = WorldScore.rank(
            candidates = listOf(
                candidate("elsewhere", scope = "/totally/other"),
                candidate("here", scope = "/ws/proj"),
            ),
            currentScope = "/ws/proj",
            nowMs = now,
        )
        assertEquals("here", ranked.first().id)
    }

    @Test
    fun `异空间条目仍然返回而不是被过滤`() {
        // 做成打分项而不是硬过滤：当前空间没结果时，别处的历史结论往往仍有参考价值。
        val ranked = WorldScore.rank(
            candidates = listOf(candidate("elsewhere", scope = "/totally/other")),
            currentScope = "/ws/proj",
            nowMs = now,
        )
        assertEquals(1, ranked.size)
        assertEquals("elsewhere", ranked.first().id)
    }

    @Test
    fun `空候选返回空`() {
        assertTrue(WorldScore.rank(emptyList(), "/ws", now).isEmpty())
    }

    @Test
    fun `排序稳定同分按 id`() {
        // 同分时用 id 兜底，避免每次检索顺序抖动导致注入内容不稳定。
        val ranked = WorldScore.rank(
            candidates = listOf(
                candidate("b", scope = "/x"),
                candidate("a", scope = "/x"),
            ),
            currentScope = "/x",
            nowMs = now,
        )
        assertEquals(listOf("a", "b"), ranked.map { it.id })
    }

    // ---- 相关度与重要度 ----

    @Test
    fun `相关度是命中比而非命中个数`() {
        // 用命中个数会让长文本天然得分高（它的词多），而长文本并不因此更相关。
        assertEquals(1.0, WorldScore.relevanceOf(hits = 3, queryTerms = 3), 1e-9)
        assertEquals(0.5, WorldScore.relevanceOf(hits = 3, queryTerms = 6), 1e-9)
        assertEquals(0.0, WorldScore.relevanceOf(hits = 0, queryTerms = 3), 1e-9)
    }

    @Test
    fun `查询词为空时相关度为零`() {
        assertEquals(0.0, WorldScore.relevanceOf(hits = 0, queryTerms = 0), 1e-9)
    }

    @Test
    fun `失败教训的重要度高于普通发现`() {
        val failure = WorldScore.importanceOf("failure", hasEvidence = false, hasUncertainty = false)
        val finding = WorldScore.importanceOf("finding", hasEvidence = false, hasUncertainty = false)
        assertTrue(failure > finding)
    }

    @Test
    fun `带证据与不确定项会加权`() {
        val plain = WorldScore.importanceOf("finding", false, false)
        val withBoth = WorldScore.importanceOf("finding", true, true)
        assertTrue(withBoth > plain)
    }

    @Test
    fun `重要度不超过 1`() {
        val value = WorldScore.importanceOf("failure", hasEvidence = true, hasUncertainty = true)
        assertTrue(value <= 1.0)
    }

    // ---- 取用率因子 ----

    @Test
    fun `取用次数必须能抬高条目`() {
        // 分母含 helpful 时 (1+h)/(1+h+bad) 在 bad=0 处恒等于 1，取用记录等于无效。
        val fresh = WorldScore.usageFactor(helpful = 0, harmful = 0, exposed = 0)
        val used = WorldScore.usageFactor(helpful = 5, harmful = 0, exposed = 0)
        assertEquals(1.0, fresh, 1e-9)
        assertTrue("被取用过的条目必须高于全新条目", used > fresh)
    }

    @Test
    fun `曝光次数必须能压低条目`() {
        val usedOnly = WorldScore.usageFactor(helpful = 5, harmful = 0, exposed = 0)
        val alsoExposed = WorldScore.usageFactor(helpful = 5, harmful = 0, exposed = 10)
        assertTrue("看过却没人取用应当压低它", alsoExposed < usedOnly)
    }

    @Test
    fun `误导比单纯曝光更有害`() {
        val exposed = WorldScore.usageFactor(helpful = 5, harmful = 0, exposed = 10)
        val harmful = WorldScore.usageFactor(helpful = 5, harmful = 10, exposed = 0)
        assertEquals(harmful, exposed, 1e-9)
    }

    @Test
    fun `取用多且无负面信号的条目排在前面`() {
        val good = WorldScore.usageFactor(helpful = 9, harmful = 0, exposed = 1)
        val bad = WorldScore.usageFactor(helpful = 1, harmful = 0, exposed = 9)
        assertTrue(good > bad)
    }

    @Test
    fun `计数为负时不产生负分或除零`() {
        assertEquals(1.0, WorldScore.usageFactor(-3, -1, -5), 1e-9)
    }

    // ---- 查询词切分 ----

    @Test
    fun `英文查询按分隔符切词`() {
        val terms = WorldScore.termsOf("AgentLoop WorldScore")
        assertEquals(listOf("agentloop", "worldscore"), terms)
    }

    @Test
    fun `中文查询按二元切分`() {
        val terms = WorldScore.termsOf("空间维度")
        // 与实体抽取的切分口径一致，两处若不一致，
        // 「写入时认为相关的」与「检索时认为相关的」会是两套标准。
        assertTrue(terms.contains("空间"))
        assertTrue(terms.contains("间维"))
        assertTrue(terms.contains("维度"))
    }

    @Test
    fun `中英混排查询都能切出词`() {
        val terms = WorldScore.termsOf("world_recall 的 limit 参数")
        assertTrue(terms.any { it.contains("world") })
        assertTrue(terms.contains("参数"))
    }

    @Test
    fun `中文标点也作为分隔符`() {
        val terms = WorldScore.termsOf("空间，维度")
        assertTrue(terms.contains("空间"))
        assertTrue(terms.contains("维度"))
    }

    @Test
    fun `空查询返回空词表`() {
        assertTrue(WorldScore.termsOf("").isEmpty())
        assertTrue(WorldScore.termsOf("   ").isEmpty())
    }

    @Test
    fun `查询词去重`() {
        val terms = WorldScore.termsOf("abc abc abc")
        assertEquals(1, terms.size)
    }

    // ---- 文本相关度 ----

    @Test
    fun `相关度按命中比例计算`() {
        val terms = listOf("alpha", "beta", "gamma")
        assertEquals(1.0, WorldScore.relevanceFor("alpha beta gamma", terms), 1e-9)
        assertTrue(WorldScore.relevanceFor("alpha only", terms) < 1.0)
        assertEquals(0.0, WorldScore.relevanceFor("nothing here", terms), 1e-9)
    }

    @Test
    fun `相关度匹配不区分大小写`() {
        val terms = WorldScore.termsOf("AgentLoop")
        assertEquals(1.0, WorldScore.relevanceFor("agentloop 在运行时", terms), 1e-9)
    }

    @Test
    fun `相关度用包含匹配而非整词匹配`() {
        // 中文没有词边界，而英文侧整词匹配会让查 AgentLoop 时漏掉提到
        // AgentLoopTest 的条目——那显然相关。
        val terms = WorldScore.termsOf("AgentLoop")
        assertTrue(WorldScore.relevanceFor("见 AgentLoopTest 的用例", terms) > 0.0)
    }

    @Test
    fun `空文本相关度为零`() {
        assertEquals(0.0, WorldScore.relevanceFor("", WorldScore.termsOf("abc")), 1e-9)
    }

    // ---- 空间来源标注 ----

    @Test
    fun `同空间不标注来源`() {
        assertEquals("", WorldKnowledgeLogic.originLabel("/ws/proj", "/ws/proj"))
    }

    @Test
    fun `异空间只标路径末段`() {
        // 完整路径的前缀在这里不携带信息（所有条目都共享它），末段才是区分点。
        assertEquals(
            "other",
            WorldKnowledgeLogic.originLabel("/ws/proj/other", "/ws/proj/current"),
        )
    }

    @Test
    fun `坐标未知不标注来源`() {
        assertEquals("", WorldKnowledgeLogic.originLabel("", "/ws/proj"))
        assertEquals("", WorldKnowledgeLogic.originLabel("/ws/proj", ""))
    }
}