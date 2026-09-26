package io.github.asagnc.sta.data.world

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Sta 的观测层。
 *
 * 和 `StaDatabase`（用户资产：会话、方案、清单、provider 配置）分开成两个库，理由是两者的
 * 生命周期不同：用户数据要长期保留、要跟随备份；这里存的是运行副产物（工具失败教训、
 * 子智能体调研结论），可清可重建。
 *
 * 分开的第二个好处是**不需要迁移链**：本库 schema 变动时直接 [dropAllTables] 重建，
 * 不需要写 `MIGRATION_x_y`，也就不可能出现"迁移写错静默清掉用户数据"。
 */
@Entity(
    tableName = "world_knowledge",
    indices = [
        Index(value = ["kind", "signature", "created_at"]),
        Index(value = ["expires_at"]),
    ],
)
internal data class WorldKnowledgeEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    /** 知识种类。新种类只增加取值，不改表结构。 */
    @ColumnInfo(name = "kind") val kind: String,
    /** 去重与检索键：同类同签名视为同一条知识。 */
    @ColumnInfo(name = "signature") val signature: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    /** 过期时刻；0 表示不过期。 */
    @ColumnInfo(name = "expires_at") val expiresAt: Long,
    @ColumnInfo(name = "origin_run") val originRun: String,
    @ColumnInfo(name = "origin_session") val originSession: String,
    @ColumnInfo(name = "origin_agent") val originAgent: String,
    /** 结论正文，注入时用的精炼文本。 */
    @ColumnInfo(name = "summary") val summary: String,
    /** 证据引用，每行一条（`路径:行号` 或 `命令 → 输出片段`）。 */
    @ColumnInfo(name = "evidence") val evidence: String,
    @ColumnInfo(name = "uncertainty") val uncertainty: String,
    /** 该 kind 特有的字段，JSON 对象；空串表示没有。 */
    @ColumnInfo(name = "payload") val payload: String,
    /** 依赖文件及其指纹，JSON 数组；读回时据此判断结论是否仍然成立。 */
    @ColumnInfo(name = "dependencies") val dependencies: String,
    @ColumnInfo(name = "sensitive") val sensitive: Boolean,
    /**
     * 被取用的次数：痕迹「有用」的唯一依据。
     *
     * 由取用方在检索命中时累加，不靠任何外部判定——谁被取用得多，谁就上浮。
     */
    @ColumnInfo(name = "helpful") val helpful: Int = 0,
    /** 被取用后判定为误导的次数（依赖已变、结论已不成立），排序时会压制它。 */
    @ColumnInfo(name = "harmful") val harmful: Int = 0,
    /**
     * 被曝光但没有被取用的次数。
     *
     * 注入里出现过、而整个 run 结束都没人去取，这说明它对当前任务没有价值。
     * 没有这个计数，「看过但没用」这条最普遍的信号就完全丢失了。
     */
    @ColumnInfo(name = "exposed") val exposed: Int = 0,
    /**
     * 该条观测所属的空间坐标（工作区根路径，形如 `/data/local/tmp/sta/Sta-src`）。
     *
     * 「空间」这个维度不是装饰：同一句结论在不同工作区里含义不同，只按关键词检索会让
     * 两个项目的同名文件互相污染。空串表示「坐标未知」，排序时按最远处理而不丢弃
     * ——未知坐标的历史结论仍可能有价值，只是不作为优先项。
     */
    @ColumnInfo(name = "scope") val scope: String = "",
)

@Dao
internal interface WorldKnowledgeDao {
    /** 同 id 覆盖写：同一条知识被重新观测到时更新而不是堆积。 */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorldKnowledgeEntity)

    /** 按签名取最近一条，用于"同类问题先查历史"。 */
    @Query(
        "SELECT * FROM world_knowledge WHERE kind = :kind AND signature = :signature " +
            "ORDER BY created_at DESC LIMIT 1",
    )
    suspend fun latestBySignature(kind: String, signature: String): WorldKnowledgeEntity?

    /** 取未过期的条目，最近优先。 */
    @Query(
        "SELECT * FROM world_knowledge WHERE expires_at = 0 OR expires_at > :now " +
            "ORDER BY created_at DESC LIMIT :limit",
    )
    suspend fun recent(now: Long, limit: Int): List<WorldKnowledgeEntity>

    /**
     * 按关键词检索未过期的条目，最近优先。
     *
     * 关键词同时匹配结论、证据与不确定项：模型给的查询词可能出现在任一处，
     * 只搜结论会漏掉「证据里提到过这个文件」这类高价值命中。
     */
    @Query(
        "SELECT * FROM world_knowledge WHERE (expires_at = 0 OR expires_at > :now) " +
            "AND (summary LIKE :pattern OR evidence LIKE :pattern OR uncertainty LIKE :pattern) " +
            "ORDER BY created_at DESC LIMIT :limit",
    )
    suspend fun search(now: Long, pattern: String, limit: Int): List<WorldKnowledgeEntity>

    /** 按种类取未过期条目，最近优先。 */
    @Query(
        "SELECT * FROM world_knowledge WHERE kind = :kind AND (expires_at = 0 OR expires_at > :now) " +
            "ORDER BY created_at DESC LIMIT :limit",
    )
    suspend fun recentByKind(kind: String, now: Long, limit: Int): List<WorldKnowledgeEntity>

    /** 某类未过期条目的条数，供注入端报数（只回一个整数，不搬运正文）。 */
    @Query(
        "SELECT COUNT(*) FROM world_knowledge WHERE kind = :kind AND (expires_at = 0 OR expires_at > :now)",
    )
    suspend fun countByKind(kind: String, now: Long): Int

    /** 某类未过期条目中最近一条的创建时刻；没有条目时返回 0。 */
    @Query(
        "SELECT COALESCE(MAX(created_at), 0) FROM world_knowledge " +
            "WHERE kind = :kind AND (expires_at = 0 OR expires_at > :now)",
    )
    suspend fun newestAtByKind(kind: String, now: Long): Long

    /** 按 id 批量删除。 */
    @Query("DELETE FROM world_knowledge WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /** 记一次取用：检索命中的条目累加 helpful。 */
    @Query("UPDATE world_knowledge SET helpful = helpful + 1 WHERE id IN (:ids)")
    suspend fun markHelpful(ids: List<String>)

    /** 记一次误导：被取用但结论已不成立时累加 harmful。 */
    @Query("UPDATE world_knowledge SET harmful = harmful + 1 WHERE id IN (:ids)")
    suspend fun markHarmful(ids: List<String>)

    /** 记一次曝光：注入里出现过但整轮未被取用。 */
    @Query("UPDATE world_knowledge SET exposed = exposed + 1 WHERE id in (:ids)")
    suspend fun markExposed(ids: List<String>)

    /**
     * 取最近的条目（不限种类、不过滤过期）。
     *
     * 供建图时重建既有观测的坐标用：图描述的是「历史上这些观测之间的关系」，过期的
     * 结论仍然参与建边——否则一条结论到期后它的所有边会凭空消失，聚类结果会跟着抖动。
     * 过期只影响「要不要注入给模型」，不影响「世界上有没有发生过」。
     */
    @Query("SELECT * FROM world_knowledge ORDER BY created_at DESC LIMIT :limit")
    suspend fun recentAny(limit: Int): List<WorldKnowledgeEntity>

    /**
     * 该 id 是否仍然存在。
     *
     * 写入路径需要它：调用方拿到实际落库 id 后会用它当图上的节点端点，若这一条刚好
     * 在裁剪中被删掉，端点在库里就不存在，图会留下悬空引用。
     */
    @Query("SELECT COUNT(*) FROM world_knowledge WHERE id = :id")
    suspend fun exists(id: String): Int

    /** 清理已过期条目，返回删除条数。 */
    @Query("DELETE FROM world_knowledge WHERE expires_at != 0 AND expires_at <= :now")
    suspend fun deleteExpired(now: Long): Int

    /**
     * 把坐标未知的历史条目回填成当前工作区根。
     *
     * 只补空串，不覆盖已有值：已有值可能是真实的多工作区坐标（将来支持多工作区时），
     * 无条件覆盖会把它们抹平成同一个点。
     *
     * 为什么必须回填而不是放着：scope 空串在检索时按"最远"处理，于是所有历史结论
     * 都会被当成"来自别的空间"而排在后面——用户升级后会发现"最新写入的观测查不到"，
     * 而那是回填缺失，不是检索逻辑错。
     */
    @Query("UPDATE world_knowledge SET scope = :scope WHERE scope = ''")
    suspend fun backfillScope(scope: String): Int

    /** 按条数裁剪：只保留最近的 [keep] 条，避免无限增长。 */
    @Query(
        "DELETE FROM world_knowledge WHERE id NOT IN " +
            "(SELECT id FROM world_knowledge ORDER BY created_at DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)
}

/**
 * 子智能体的一次委派记录，形如一棵树。
 *
 * 字段取自 OpenTelemetry 的 span 模型（name / parent span id / 起止时间 / attributes）：
 * 一次 run 对应一棵树（[traceId]），主循环派出的委派挂在根下，委派内部再派生的节点用
 * [parentId] 挂在它下面。这样「这条结论是哪一轮、谁派的、下面还做了什么」都能回溯，
 * 而不是只能按 run 平铺着查。
 *
 * 正文的存放位置按 SQLite 官方实测的分界线（100KB）决定：小于该值时正文直接入库读取更快，
 * 超过则落文件、库里只留路径，避免大文本拖慢整张表的查询与分页。
 */
@Entity(
    tableName = "world_trace",
    indices = [
        Index(value = ["trace_id", "parent_id"]),
        Index(value = ["run_id"]),
        Index(value = ["started_at"]),
    ],
)
internal data class WorldTraceEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    /** 父节点 id；空串表示这是一次 run 的根。 */
    @ColumnInfo(name = "parent_id") val parentId: String,
    /** 一次 run 对应一棵树。 */
    @ColumnInfo(name = "trace_id") val traceId: String,
    @ColumnInfo(name = "run_id") val runId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    /** 发起者：主循环，或某个子智能体角色名。 */
    @ColumnInfo(name = "agent") val agent: String,
    /** 本次委派的角色名。 */
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "started_at") val startedAt: Long,
    @ColumnInfo(name = "ended_at") val endedAt: Long,
    /** 结束状态：ok / failed；失败时同时记错误码。 */
    @ColumnInfo(name = "status") val status: String,
    /** 子智能体的摘要全文，也就是主循环看到的那一份。 */
    @ColumnInfo(name = "summary") val summary: String,
    /** 摘要解析出的结论（三段格式的第一段）；解析不出时退化为全文。 */
    @ColumnInfo(name = "conclusion") val conclusion: String,
    /** 证据行，每行一条，与摘要里的原样一致。 */
    @ColumnInfo(name = "evidence") val evidence: String,
    /** 不确定项，每行一条。 */
    @ColumnInfo(name = "uncertainty") val uncertainty: String,
    /** 正文（完整消息流）；超阈值时为空，改由 [contentPath] 指向文件。 */
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "content_path") val contentPath: String,
    @ColumnInfo(name = "content_bytes") val contentBytes: Long,
    /**
     * 该次委派所属的空间坐标（工作区根路径）。与 [WorldKnowledgeEntity.scope] 同一口径，
     * 使「结论」与「现场」能在同一个空间里被一起检索出来。
     */
    @ColumnInfo(name = "scope") val scope: String = "",
)

@Dao
internal interface WorldTraceDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: WorldTraceEntity)

    /** 取整棵树，按开始时间排列，父必然排在子之前。 */
    @Query("SELECT * FROM world_trace WHERE trace_id = :traceId ORDER BY started_at ASC")
    suspend fun tree(traceId: String): List<WorldTraceEntity>

    /** 取某个节点的直接子节点。 */
    @Query("SELECT * FROM world_trace WHERE parent_id = :parentId ORDER BY started_at ASC")
    suspend fun children(parentId: String): List<WorldTraceEntity>

    /** 最近若干次委派，用于回答「上一次排查到底看了什么」。 */
    @Query("SELECT * FROM world_trace ORDER BY started_at DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<WorldTraceEntity>

    /** 超出保留条数的旧记录；正文文件由调用方连带删除。 */
    @Query(
        "SELECT id, content_path FROM world_trace WHERE id NOT IN " +
            "(SELECT id FROM world_trace ORDER BY started_at DESC LIMIT :keep)",
    )
    suspend fun overflow(keep: Int): List<TraceOverflowRow>

    @Query("DELETE FROM world_trace WHERE id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    /**
     * 把坐标未知的历史委派记录回填成当前工作区根。
     *
     * 与知识条目的回填成对存在：只补一张表会造成"结论有坐标、现场没有"，而两者在
     * 第二阶段的检索里要按同一口径比较空间距离，一边缺坐标就会让排序结果失去意义。
     */
    @Query("UPDATE world_trace SET scope = :scope WHERE scope = ''")
    suspend fun backfillScope(scope: String): Int
}

/** 裁剪时需要连带删除的正文文件路径。 */
internal data class TraceOverflowRow(
    @ColumnInfo(name = "id") val id: String,
    @ColumnInfo(name = "content_path") val contentPath: String,
)


@Database(
    entities = [
        WorldKnowledgeEntity::class,
        WorldTraceEntity::class,
    ],
    version = WorldDatabase.VERSION,
    exportSchema = false,
)
internal abstract class WorldDatabase : RoomDatabase() {
    abstract fun knowledgeDao(): WorldKnowledgeDao

    abstract fun traceDao(): WorldTraceDao

    companion object {
        /**
         * schema 版本。
         *
         * 2：新增 world_trace（子智能体委派树）。
         * 3：world_trace 增加 conclusion / evidence / uncertainty，摘要按三段格式拆开存，
         *    证据行与不确定项因此可查询，不必把摘要当自由文本重新解析。
         * 4：新增空间维度——knowledge / trace 增加 scope 坐标；新增 world_entity（实体）
         *    与 world_edge（分层的边）两张表。
         * 5：删除 world_entity 与 world_edge——建图没有任何读取方，属实只写不读。
         * 6：world_knowledge 增加 helpful / harmful / exposed 三个计数——痕迹的价值由
         *    取用与曝光自己长出来，不再由关键词判据或 TTL 代替。
         */
        const val VERSION = 6

        const val FILE_NAME = "world.db"

        /**
         * 4 → 5 的迁移。
         *
         * 必须显式写出来：本库对未知版本走 `fallbackToDestructiveMigration`，那条路径会
         * 重建整库，而 world_trace 存的是历次委派的现场，属于要保留的记录，不能因为
         * 删两张只写不读的表就一起丢掉。
         */
        val MIGRATION_4_5: Migration = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("DROP TABLE IF EXISTS world_entity")
                db.execSQL("DROP TABLE IF EXISTS world_edge")
            }
        }

        /**
         * 5 → 6 的迁移：加三个计数列。
         *
         * 新列带默认值 0，因此已有条目全部从「零取用」开始——旧数据本来就没有取用记录，
         * 这正是它的真实状态，不需要回填。
         */
        val MIGRATION_5_6: Migration = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE world_knowledge ADD COLUMN helpful INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE world_knowledge ADD COLUMN harmful INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE world_knowledge ADD COLUMN exposed INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
