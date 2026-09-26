package io.github.asagnc.sta.data.world

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import java.io.File

/**
 * 观测库的构建与持有。
 *
 * 单独成一个对象，而不是挂在某个 store 上：库里有两类数据（知识条目与委派轨迹），
 * 它们的读写入口各自独立，谁都不该依赖对方来拿数据库句柄。
 *
 * 库是单例且懒建：首次访问时才创建文件，没用到世界模型时不产生磁盘占用。
 */
internal object WorldDatabaseProvider {

    @Volatile
    private var instance: WorldDatabase? = null

    fun get(context: Context): WorldDatabase =
        instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                WorldDatabase::class.java,
                WorldDatabase.FILE_NAME,
            )
                // 本库是运行观测，版本不匹配时重建即可：不写迁移，也就不可能迁移出错。
                .fallbackToDestructiveMigration(dropAllTables = true)
                .addMigrations(WorldDatabase.MIGRATION_4_5, WorldDatabase.MIGRATION_5_6)
                // WAL：读不阻塞写、写不阻塞读。本库的写入来自工具失败与委派回收（分散在
                // 多条路径上），读取来自回注与排查，两者会并发发生。
                .setJournalMode(RoomDatabase.JournalMode.WRITE_AHEAD_LOGGING)
                .build()
                .also { instance = it }
        }

    /** 观测库文件，供排查与清理使用。 */
    fun fileFor(context: Context): File = context.getDatabasePath(WorldDatabase.FILE_NAME)

    /** 供测试重置单例。 */
    fun closeForTests() {
        synchronized(this) {
            instance?.close()
            instance = null
        }
    }
}
