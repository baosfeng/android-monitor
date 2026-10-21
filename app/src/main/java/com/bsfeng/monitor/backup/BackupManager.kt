package com.bsfeng.monitor.backup

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.provider.MediaStore
import com.bsfeng.monitor.BuildConfig
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.finance.FinanceDatabaseHelper
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 备份触发来源：决定成败文案与后续提示，不影响备份内容。 */
enum class BackupOrigin { MANUAL, SCHEDULED, PRE_RESTORE }

sealed class BackupOutcome {
    data class Success(
        val manifest: BackupManifest,
        val publicPath: String?,
        val localName: String?,
    ) : BackupOutcome()

    data class Failed(val message: String) : BackupOutcome()
}

/** 已校验、已解包到暂存目录的还原计划；[preRestoreName] 是还原前自动留的一份当前数据。 */
class RestorePlan(
    val stagingDir: File,
    val manifest: BackupManifest,
    val preRestoreName: String?,
)

sealed class RestoreOutcome {
    data class Ready(val plan: RestorePlan) : RestoreOutcome()
    data class Rejected(val reason: String) : RestoreOutcome()
}

/**
 * 备份 / 还原的全部 IO 编排（Android 依赖只在这一层）。
 *
 * 口径：
 * - 备份包落在两处：公共 `Downloads/行为记录备份/`（用户可见、卸载不丢）+ `getExternalFilesDir("backups")`
 *   （快速列表来源，扫描快）。两处各留最多 [RetentionPolicy.MAX_BACKUPS] 份。
 * - 还原是「先备份、再校验、后替换」：任何一步失败都不动现有数据。
 * - 替换文件后旧连接已失效（单例 helper 已被 close），**必须重启进程**，见 [restartProcess]。
 */
class BackupManager(context: Context) {

    private val app = context.applicationContext
    private val config = BackupConfig(app)

    // ---------------------------------------------------------------- 备份

    /** 同步 IO：调用方负责放到 `Dispatchers.IO`。 */
    fun backupNow(origin: BackupOrigin = BackupOrigin.MANUAL): BackupOutcome {
        val createdAt = System.currentTimeMillis()
        val name = backupName(createdAt)
        val tmpFile = File(cacheDir("backup-tmp"), name)
        return try {
            val tables = collectTableCounts()
            checkpointDatabases()
            val sources = collectSources()
            if (sources.isEmpty()) return BackupOutcome.Failed("没有可备份的数据文件")

            val manifest = FileOutputStream(tmpFile).use { out ->
                BackupPackager.pack(sources, BackupMeta(BuildConfig.VERSION_NAME, createdAt, tables), out)
            }

            // ① 本地快速列表（还原入口扫描这里）
            val localDir = localBackupDir()
            val localFile = File(localDir, name)
            tmpFile.copyTo(localFile, overwrite = true)
            // ② 公共 Downloads（MediaStore，无需存储权限）；失败不影响本地那份
            val publicPath = publishToDownloads(tmpFile, name)

            pruneLocal(localDir)
            prunePublic()

            config.recordBackup(createdAt, publicPath, name)
            BackupOutcome.Success(manifest, publicPath, name)
        } catch (t: Throwable) {
            BackupOutcome.Failed("备份失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            tmpFile.delete()
        }
    }

    /** 本地快速列表目录里的备份，按修改时间倒序。 */
    fun listBackups(): List<BackupFileInfo> =
        localBackupDir().listFiles()
            ?.filter { it.isFile && it.name.startsWith(NAME_PREFIX) }
            ?.map { BackupFileInfo(it.name, it.lastModified(), it.length()) }
            ?.sortedWith(compareByDescending<BackupFileInfo> { it.modifiedAt }.thenByDescending { it.name })
            ?: emptyList()

    // ---------------------------------------------------------------- 还原

    /**
     * 还原第一步（同步 IO）：拷贝所选 zip → 校验清单与 sha256 → 自动备份当前数据 → 解包到暂存目录。
     *
     * 任何一步失败都返回 [RestoreOutcome.Rejected]，且**不改动现有数据**；
     * 校验不过时绝不删 `pre-restore` 包。
     */
    fun prepareRestore(uri: Uri): RestoreOutcome {
        val imported = File(cacheDir("restore-import"), "import.zip")
        return try {
            app.contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(imported).use { output -> input.copyTo(output) }
            } ?: return RestoreOutcome.Rejected("无法读取所选文件")

            if (imported.length() == 0L) return RestoreOutcome.Rejected("所选文件为空")

            val manifest = when (val verified = BackupUnpacker.verify(imported)) {
                is BackupVerify.Invalid -> return RestoreOutcome.Rejected(verified.reason)
                is BackupVerify.Ok -> verified.manifest
            }

            // 先给当前数据留一份兜底包：还原后发现问题还能再导回来
            val preRestore = backupNow(BackupOrigin.PRE_RESTORE)
            if (preRestore is BackupOutcome.Failed) {
                return RestoreOutcome.Rejected("还原前自动备份失败（${preRestore.message}），已中止还原")
            }
            val preRestoreName = (preRestore as BackupOutcome.Success).localName

            val staging = cacheDir("restore-staging")
            if (!BackupUnpacker.extract(imported, manifest, staging)) {
                return RestoreOutcome.Rejected("解包失败：备份包内容与清单不一致")
            }
            RestoreOutcome.Ready(RestorePlan(staging, manifest, preRestoreName))
        } catch (t: Throwable) {
            RestoreOutcome.Rejected("还原准备失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            imported.delete()
        }
    }

    /**
     * 还原第二步（同步 IO）：关闭数据库连接 → 用暂存目录覆盖应用数据文件。
     *
     * 失败后果（写进文档）：文件替换到一半被中断，库可能打不开 —— 用
     * `Downloads/行为记录备份/` 里最新的 `pre-restore` 包再还原一次即可。
     * 替换完成后必须尽快 [restartProcess]：单例 helper 已 close，继续使用会抛
     * “attempt to re-open an already-closed object”。
     */
    fun applyRestore(plan: RestorePlan) {
        closeDatabases()
        val root = app.dataDir
        // 旧的 WAL/SHM/journal 必须清掉，否则可能把还原后的库回滚回旧状态
        dbNames().forEach { dbName ->
            listOf("-wal", "-shm", "-journal").forEach { suffix ->
                File(File(root, BackupPaths.DB_DIR), "$dbName$suffix").delete()
            }
        }
        plan.stagingDir.walkTopDown().filter { it.isFile }.forEach { source ->
            val relative = source.relativeTo(plan.stagingDir).path.replace(File.separatorChar, '/')
            if (!BackupPaths.isSafe(relative)) return@forEach
            val target = File(root, relative)
            target.parentFile?.mkdirs()
            if (target.exists()) target.delete()
            source.copyTo(target, overwrite = true)
        }
        plan.stagingDir.deleteRecursively()
    }

    /**
     * 让新文件真的被读取：结束本进程，连接在下一次启动时重建。
     *
     * 用系统 API（无需第三方重启库）。进程退出后由用户从桌面重新打开 —— Android 10+
     * 对「从后台拉起自身 Activity」有限制，这里不强行 startActivity，以免静默失败。
     */
    fun restartProcess() {
        Process.killProcess(Process.myPid())
    }

    // ---------------------------------------------------------------- 内部

    private fun cacheDir(name: String): File = File(app.cacheDir, name).apply { mkdirs() }

    /** 备份文件名：`monitor-backup-yyyyMMdd-HHmmss.zip`（pre-restore 包用同一前缀，一起参与保留策略）。 */
    private fun backupName(createdAt: Long): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(createdAt))
        return "$NAME_PREFIX$stamp.zip"
    }

    private fun dbNames(): List<String> = listOf("finance.db", "monitor.db")

    /** 归档根 = 应用数据目录；路径相对 dataDir，还原时按同一相对路径落盘。 */
    private fun collectSources(): List<BackupSource> {
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
    private fun collectTableCounts(): List<BackupTableCount> {
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
    private fun checkpointDatabases() {
        runCatching {
            FinanceDatabaseHelper.getInstance(app).writableDatabase
                .rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)
                .use { it.moveToFirst() }
            DatabaseHelper.getInstance(app).writableDatabase
                .rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null)
                .use { it.moveToFirst() }
        }
    }

    private fun localBackupDir(): File = File(app.getExternalFilesDir(null) ?: app.filesDir, LOCAL_DIR).apply { mkdirs() }

    private fun downloadsCollection(): Uri =
        MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

    private fun publicRelativePath(): String =
        "${Environment.DIRECTORY_DOWNLOADS}/$PUBLIC_DIR/"

    private fun publishToDownloads(file: File, name: String): String? = runCatching {
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

    private fun pruneLocal(dir: File) {
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(NAME_PREFIX) }
            ?.map { BackupFileInfo(it.name, it.lastModified(), it.length()) }
            ?: return
        RetentionPolicy.expired(files).forEach { File(dir, it.name).delete() }
    }

    private fun prunePublic() {
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
    private fun closeDatabases() {
        runCatching { FinanceDatabaseHelper.getInstance(app).close() }
        runCatching { DatabaseHelper.getInstance(app).close() }
    }

    companion object {
        const val PUBLIC_DIR = "行为记录备份"
        const val LOCAL_DIR = "backups"
        const val NAME_PREFIX = "monitor-backup-"

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
