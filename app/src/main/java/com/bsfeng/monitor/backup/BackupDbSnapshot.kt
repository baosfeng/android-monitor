package com.bsfeng.monitor.backup

import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteDatabaseLockedException
import java.io.File

/** 单库快照：主库文件 + 冻结时仍非空的 -wal（null = 不需要带）+ 各表行数。 */
internal class DbSnapshot(
    val mainFile: File,
    val walFile: File?,
    val counts: List<BackupTableCount>,
)

/**
 * 在「写闸」内把一个库复制成一份自洽快照。
 *
 * 为什么要冻结：SQLite 的库不是普通文件能原子取走的整体——主库 + `-wal` 只有在一个提交都进不来时
 * 才构成 SQLite 认可的一组合。旧实现依次用 FileInputStream 读 db → -wal → -shm，全程不加锁，
 * 而 RecordReceiver / UsageTracker / 互传任务历史同时在写：包内随时可能变成「旧主库 + 新 wal」
 * 或「新主库 + 被 checkpoint 清空的 wal」，还原后静默丢最近若干条，用户只看到数据莫名变少。
 *
 * 冻结手段是**同一个连接上的写事务**（`BEGIN EXCLUSIVE`）：SQLite 一次只允许一个写事务，
 * 从 beginTransaction 到 endTransaction 之间不会有任何提交落到盘上。该事务不写任何数据，
 * 结束时也不 setTransactionSuccessful → 回滚，库内容零改动。
 *
 * `-shm` 不进包：它是 WAL 的内存索引，还原端本来就会删掉重建（见 [RestoreFileOperator]）。
 *
 * 一致性优先于可用性：拿不到写闸就**让备份失败**，不产出「看起来正常、还原后少数据」的包。
 */
internal class BackupDbSnapshotter(
    private val openDb: () -> SQLiteDatabase,
    private val tables: List<String>,
    private val maxFreezeAttempts: Int = MAX_FREEZE_ATTEMPTS,
    /** 冻结窗口内执行的复制动作；单测注入它来钉住「复制必须发生在事务内」。 */
    private val copy: (SQLiteDatabase, File, File) -> Unit = ::copyDatabaseFiles,
) {

    /**
     * @param mainTarget 主库快照落点
     * @param walTarget `-wal` 落点（源没有 wal 时不会产生该文件）
     */
    fun snapshot(mainTarget: File, walTarget: File): DbSnapshot {
        mainTarget.parentFile?.mkdirs()
        walTarget.parentFile?.mkdirs()

        var lastError: Throwable? = null
        repeat(maxFreezeAttempts) {
            // 句柄由调用方的单例持有，本方法不 close：close 会让 App 后续写入重新打开库
            val db = openDb()
            try {
                // 先 checkpoint：唯一目的是把 -wal 收小、压包体积。
                // 它失败也不影响正确性——冻结后复制的是「主库 + 非空 wal」，两者合起来就是完整状态。
                runCatching { db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).close() }
                db.beginTransaction()
                return try {
                    val counts = tables.map { BackupTableCount(it, countRows(db, it)) }
                    copy(db, mainTarget, walTarget)
                    DbSnapshot(mainTarget, walTarget.takeIf { it.isFile && it.length() > 0 }, counts)
                } finally {
                    // 没有 setTransactionSuccessful：回滚这个空事务，库内容一行不改
                    db.endTransaction()
                }
            } catch (e: SQLiteDatabaseLockedException) {
                // 另一个写者还占着库：丢掉半成品再试一次
                lastError = e
                mainTarget.delete()
                walTarget.delete()
            } catch (t: Throwable) {
                // 复制到一半失败：删掉半成品，让上层看到「这次备份失败」而不是一份残缺文件
                mainTarget.delete()
                walTarget.delete()
                throw t
            }
        }
        mainTarget.delete()
        walTarget.delete()
        throw IllegalStateException(
            "备份时数据库被写入占住，$maxFreezeAttempts 次都没拿到写闸；本次备份已中止",
            lastError,
        )
    }

    private fun countRows(db: SQLiteDatabase, table: String): Long =
        runCatching {
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        }.getOrDefault(0L)

    companion object {
        private const val MAX_FREEZE_ATTEMPTS = 3

        /** 主库 + 存在时的 -wal。不复制 -shm / -journal：还原端会删掉重建。 */
        private fun copyDatabaseFiles(db: SQLiteDatabase, mainTarget: File, walTarget: File) {
            val dbPath = db.path ?: throw IllegalStateException("数据库没有文件路径，无法快照")
            File(dbPath).copyTo(mainTarget, overwrite = true)
            val wal = File("${dbPath}-wal")
            if (wal.isFile && wal.length() > 0) {
                wal.copyTo(walTarget, overwrite = true)
            } else {
                walTarget.delete()
            }
        }
    }
}
