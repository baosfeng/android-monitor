package com.bsfeng.monitor.backup

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.finance.FinanceDatabaseHelper
import com.bsfeng.monitor.record.LocationPolicy
import java.io.File
import java.io.FileInputStream

// 从 backup/BackupManager.kt 拆出：备份源收集（库快照 + 白名单文件）、旧包清理、库句柄关闭。
// 负责「备份包里装什么」；编排与还原流程仍在 BackupManager。

/** 一次备份的取料结果：库快照（含冻结窗口内统计的表行数）+ 白名单文件。 */
internal class BackupSnapshot(
    val sources: List<BackupSource>,
    val counts: List<BackupTableCount>,
)

/**
 * 备份的「取料」动作。
 *
 * 全部方法同步 IO，调用方负责放到 `Dispatchers.IO`；**唯一会抛异常的地方是库快照拿不到写闸**
 * （见 [BackupDbSnapshotter]）—— 那时必须让备份失败，不能产出还原后少数据的包。
 */
internal class BackupSourceCollector(private val app: Context) {

    /**
     * 两张库的一致快照 + 各自的表行数。
     *
     * 顺序固定：先冻结取快照、再统计行数（同一个冻结窗口内，行数与快照严格对应），
     * 最后对 monitor.db 的快照副本降坐标精度（见 [BackupCoordinatePrecision]）。
     */
    fun snapshotDatabases(snapshotDir: File): BackupSnapshot {
        snapshotDir.mkdirs()
        val sources = mutableListOf<BackupSource>()
        val counts = mutableListOf<BackupTableCount>()
        DB_SPECS.forEach { spec ->
            val main = File(snapshotDir, spec.name)
            val wal = File(snapshotDir, spec.name + "-wal")
            val snapshot = BackupDbSnapshotter({ spec.open(app) }, spec.tables).snapshot(main, wal)
            counts += snapshot.counts
            if (spec.name == MONITOR_DB) degradeCoordinates(main)
            sources += fileSource("${BackupPaths.DB_DIR}/${spec.name}", main)
            // wal 只在真正非空时才进包：checkpoint 成功后它就是 0 字节，带上只是给还原端添乱
            snapshot.walFile?.takeIf { it.isFile && it.length() > 0 }?.let {
                sources += fileSource("${BackupPaths.DB_DIR}/${spec.name}-wal", it)
            }
        }
        return BackupSnapshot(sources, counts)
    }

    /** 白名单 prefs 与 files 下两个白名单文件（都在 App 私有目录，体积小，直接按现文件读）。 */
    fun collectSources(snapshot: BackupSnapshot): List<BackupSource> {
        val sources = snapshot.sources.toMutableList()
        val prefsDir = File(app.dataDir, BackupPaths.PREFS_DIR)
        prefsDir.listFiles()
            ?.filter { it.isFile && it.name.endsWith(".xml") && it.nameWithoutExtension in BACKUP_PREFS }
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

    /**
     * 备份包里的定位坐标降到 3 位小数；失败只留痕、按原精度打包。
     *
     * 为什么不 fail-closed：包已经不再离开 App 私有目录（不再写公共 Downloads），一次坏库
     * 换来的「备份失败」代价远大于包里多几位小数；而降精度本身是尽力而为的隐私加固。
     */
    private fun degradeCoordinates(dbFile: File) {
        when (val rewritten = BackupCoordinatePrecision.sanitize(dbFile)) {
            0 -> Unit
            -1 -> android.util.Log.w(TAG, "备份包坐标降精度失败，按原精度打包")
            else -> android.util.Log.i(
                TAG,
                "备份包里的 $rewritten 个定位点降到 ${LocationPolicy.COARSE_COORDINATE_DECIMALS} 位小数",
            )
        }
    }

    /** 本地快速列表目录按保留策略清理。 */
    fun pruneLocal(dir: File) {
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(BackupManager.NAME_PREFIX) }
            ?.map { BackupFileInfo(it.name, it.lastModified(), it.length()) }
            ?: return
        // 只看普通备份：回滚包前缀不同，已被上面 filter 排除，由 BackupManager 单独按 2 份收敛
        RetentionPolicy.expired(files).forEach { File(dir, it.name).delete() }
    }

    /**
     * 把私有目录里的某一份备份**复制**一份到公共 Downloads（只由用户的显式导出调用）。
     *
     * 不自动、不定时、不动原文件：包是未加密的，公共目录里的每一份都得是用户亲手同意的；
     * 复制失败时删掉半成品条目，避免下载目录里留下 0 字节的假包。
     *
     * @return 展示用路径（Downloads/行为记录备份/<文件名>）；失败返回 null
     */
    fun exportToDownloads(source: File, name: String): String? = runCatching {
        val resolver = app.contentResolver
        val targetName = BackupExport.targetName(name)
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, targetName)
            put(MediaStore.Downloads.MIME_TYPE, "application/zip")
            put(MediaStore.Downloads.RELATIVE_PATH, BackupExport.relativePath())
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = resolver.insert(collection, values) ?: return@runCatching null
        val copied = runCatching {
            resolver.openOutputStream(uri)?.use { output ->
                FileInputStream(source).use { input -> input.copyTo(output) }
            } != null
        }.getOrDefault(false)
        if (!copied) {
            runCatching { resolver.delete(uri, null, null) }
            return@runCatching null
        }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        BackupExport.displayPath(targetName)
    }.getOrNull()

    /** 关闭打开中的单例库句柄；替换文件前调用，避免旧连接把内容写回去。 */
    fun closeDatabases() {
        runCatching { FinanceDatabaseHelper.getInstance(app).close() }
        runCatching { DatabaseHelper.getInstance(app).close() }
    }

    private class DbSpec(
        val name: String,
        val tables: List<String>,
        val open: (Context) -> android.database.sqlite.SQLiteDatabase,
    )

    companion object {
        private const val TAG = "BackupSource"
        private const val MONITOR_DB = "monitor.db"

        private val FINANCE_TABLES = listOf(
            "finance_transactions",
            "finance_categories",
            "finance_accounts",
            "finance_budgets",
            "finance_tags",
        )
        private val MONITOR_TABLES = listOf("records", "transfer_tasks")
        private val EXTRA_FILES = listOf("skip_rules_v1_backup.json", "profileInstalled")

        private val DB_SPECS = listOf(
            DbSpec("finance.db", FINANCE_TABLES) { ctx ->
                FinanceDatabaseHelper.getInstance(ctx).writableDatabase
            },
            DbSpec(MONITOR_DB, MONITOR_TABLES) { ctx ->
                DatabaseHelper.getInstance(ctx).writableDatabase
            },
        )

        /**
         * 允许进备份包的 SharedPreferences 白名单（文件名去 .xml）。
         *
         * 只收**纯偏好**：丢了可以重设、换机后能省几步；一旦带上凭据就变成泄漏面 ——
         * 备份包虽然只落 App 私有目录（不再写公共 Downloads），但它仍是**一个可被整份复制的文件**，
         * 而且能通过 SAF 交给任何 App 打开。凭据进包 = 既泄漏又无用。
         *
         * 显式排除的四类（都以本机 Keystore / 明文的形式存着凭据）：
         * - monitor_prefs：auth_token（Keystore 密文）+ 暂停/心跳/动效等状态；凭据不进备份
         * - lan_transfer：pairing_keys（Keystore 密文）—— 拿到即可无确认推文件，仍必须排除
         * - totp_secrets：TOTP 私钥密文，跨设备还原也解不开（Keystore 不可导出）
         * - quota_credentials：额度凭据密文，同上；且解密失败会静默删掉用户凭据
         */
        private val BACKUP_PREFS = setOf(
            "monitor_appearance",
            "feature_flags",
            "skip_config",
            "skip_rule_prefs",
            "skip_whitelist",
            "backup_prefs",
            "widget_prefs",
            "trip_map",
            "quota_settings",
            "quota_alerts",
            "quota_snapshots",
            "autobook_ignored",
            "totp_settings",
            "finance_prefs",
        )
    }
}
