package com.bsfeng.monitor.backup

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.finance.FinanceDatabaseHelper
import java.io.File
import java.io.FileInputStream

// 从 backup/BackupManager.kt 拆出：备份源收集、表行数统计、Downloads 发布、旧包清理。
// 负责「备份包里装什么、放哪儿、删哪份」；编排与还原流程仍在 BackupManager。

/**
 * 备份的「取料」与「归档」动作。
 *
 * 全部方法同步 IO，调用方负责放到 `Dispatchers.IO`；
 * 失败一律降级（`runCatching` / `getOrNull`），不把异常抛给编排层。
 */
internal class BackupSourceCollector(private val app: Context) {

    private fun dbNames(): List<String> = listOf("finance.db", "monitor.db")

    /** 归档根 = 应用数据目录；路径相对 dataDir，还原时按同一相对路径落盘。 */
    fun collectSources(): List<BackupSource> {
        val sources = mutableListOf<BackupSource>()
        val dbDir = File(app.dataDir, BackupPaths.DB_DIR)
        dbNames().forEach { dbName ->
            listOf("", "-wal", "-shm").forEach { suffix ->
                val file = File(dbDir, "$dbName$suffix")
                if (file.isFile) {
                    sources += fileSource("${BackupPaths.DB_DIR}/$dbName$suffix", file)
                }
            }
        }
        val prefsDir = File(app.dataDir, BackupPaths.PREFS_DIR)
        prefsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".xml") }
            ?.sortedBy { it.name }
            ?.forEach { sources += fileSource("${BackupPaths.PREFS_DIR}/${it.name}", it) }

        // files/ 下按白名单收集：只在存在时加，不存在的文件不写进清单
        EXTRA_FILES.forEach { name ->
            val file = File(app.filesDir, name)
            if (file.isFile) sources += fileSource("${BackupPaths.FILES_DIR}/$name", file)
        }
        return sources
    }

    private fun fileSource(archivePath: String, file: File): BackupSource =
        BackupSource(archivePath) { FileInputStream(file) }

    /** 表行数：备份包里记下，还原后可比对「数据是否真的回来了」。 */
    fun collectTableCounts(): List<BackupTableCount> {
        val counts = mutableListOf<BackupTableCount>()
        runCatching {
            val financeDb = FinanceDatabaseHelper.getInstance(app).readableDatabase
            FINANCE_TABLES.forEach { counts += BackupTableCount(it, countRows(financeDb, it)) }
            val monitorDb = DatabaseHelper.getInstance(app).readableDatabase
            MONITOR_TABLES.forEach { counts += BackupTableCount(it, countRows(monitorDb, it)) }
        }
        return counts
    }

    private fun countRows(db: SQLiteDatabase, table: String): Long =
        runCatching {
            db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor ->
                if (cursor.moveToFirst()) cursor.getLong(0) else 0L
            }
        }.getOrDefault(0L)

    /**
     * WAL 检查点：把 -wal 里未落盘的内容并进主库文件。
     *
     * 备份会一并打包 -wal/-shm，正常路径不依赖这一步；但它失败也不影响正确性，只是主库文件
     * 之外的增量变多。读库（无打开句柄）时返回 (0,-1,-1)，同样无害。
     */
    fun checkpointDatabases() {
        runCatching {
            FinanceDatabaseHelper.getInstance(app).writableDatabase
                .rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)
                .use { it.moveToFirst() }
            DatabaseHelper.getInstance(app).writableDatabase
                .rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)
                .use { it.moveToFirst() }
        }
    }

    private fun downloadsCollection(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun publicRelativePath(): String =
        "${Environment.DIRECTORY_DOWNLOADS}/${BackupManager.PUBLIC_DIR}/"

    /** 发布到公共 Downloads（MediaStore，无需存储权限）；失败返回 null，不影响本地那份。 */
    fun publishToDownloads(file: File, name: String): String? = runCatching {
        val resolver = app.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, publicRelativePath())
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = downloadsCollection()
        val uri = resolver.insert(collection, values) ?: return null
        resolver.openOutputStream(uri)?.use { output ->
            FileInputStream(file).use { input -> input.copyTo(output) }
        } ?: return null
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        "${publicRelativePath()}$name"
    }.getOrNull()

    /** 本地快速列表目录按保留策略清理。 */
    fun pruneLocal(dir: File) {
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(BackupManager.NAME_PREFIX) }
            ?.map { BackupFileInfo(it.name, it.lastModified(), it.length()) }
            ?: return
        RetentionPolicy.expired(files).forEach { File(dir, it.name).delete() }
    }

    /** 公共 Downloads 目录按保留策略清理（MediaStore 查询 + 删除；失败静默）。 */
    fun prunePublic() {
        runCatching {
            val collection = downloadsCollection()
            val projection = arrayOf(
                MediaStore.Downloads._ID,
                MediaStore.Downloads.DISPLAY_NAME,
                MediaStore.Downloads.DATE_ADDED,
            )
            val rows = mutableListOf<Triple<Long, String, Long>>()
            app.contentResolver.query(
                collection,
                projection,
                "${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(publicRelativePath()),
                null,
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    rows += Triple(cursor.getLong(0), cursor.getString(1) ?: "", cursor.getLong(2) * 1000L)
                }
            }
            val expiredNames = RetentionPolicy
                .expired(rows.map { BackupFileInfo(it.second, it.third) })
                .map { it.name }
                .toSet()
            if (expiredNames.isEmpty()) return@runCatching
            rows.filter { it.second in expiredNames }.forEach { row ->
                app.contentResolver.delete(ContentUris.withAppendedId(collection, row.first), null, null)
            }
        }
    }

    /** 关闭打开中的单例库句柄；替换文件前调用，避免旧连接把内容写回去。 */
    fun closeDatabases() {
        runCatching { FinanceDatabaseHelper.getInstance(app).close() }
        runCatching { DatabaseHelper.getInstance(app).close() }
    }

    companion object {
        private val FINANCE_TABLES = listOf(
            "finance_transactions",
            "finance_categories",
            "finance_accounts",
            "finance_budgets",
            "finance_tags",
        )
        private val MONITOR_TABLES = listOf("records", "transfer_tasks")
        private val EXTRA_FILES = listOf("skip_rules_v1_backup.json", "profileInstalled")
    }
}
