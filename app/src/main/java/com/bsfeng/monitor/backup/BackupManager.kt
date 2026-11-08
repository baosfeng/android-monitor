package com.bsfeng.monitor.backup

import android.content.Context
import android.os.Process
import com.bsfeng.monitor.BuildConfig
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// 从 backup/BackupManager.kt 拆出：备份 / 还原的流程编排与对外 API。
// 取料、Downloads 发布、清理、库句柄关闭都在 BackupSourceCollector。

/** 备份触发来源：决定成败文案与后续提示，不影响备份内容。 */
enum class BackupOrigin { MANUAL, SCHEDULED, PRE_RESTORE }

sealed class BackupOutcome {
    data class Success(
        val manifest: BackupManifest,
        /** 本地快速列表里的文件名；备份只落私有目录（见 [BackupManager] 类注释），没有公共路径。 */
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
 * - 备份包**只落 App 私有目录** `getExternalFilesDir("backups")`（快速列表来源，扫描快），
 *   留最多 [RetentionPolicy.MAX_BACKUPS] 份。不再写公共 `Downloads/行为记录备份/`：
 *   那个目录里的不加密包（真实账目 + 使用记录 + 轨迹坐标）对同机任何文件管理器与网盘同步可见，
 *   且用户无从知晓。需要把包搬出设备时走「从备份还原」以外的显式导出，不走自动落盘。
 * - 打包前对两张库取**一致快照**（见 [BackupDbSnapshotter]）：主库与 wal 在同一把写闸内读走，
 *   不会出现还原后少数据的包。
 * - 还原是「先备份、再校验、后替换」：任何一步失败都不动现有数据。
 * - 替换走 [RestoreFileOperator]（临时文件 + 原子 rename + restore.marker）：中途中断的数据由
 *   启动时的 [healIfNeeded] 用 pre-restore 包回滚。
 * - 替换文件后旧连接已失效（单例 helper 已被 close），**必须重启进程**，见 [restartProcess]。
 */
class BackupManager(context: Context) {

    private val app = context.applicationContext
    private val config = BackupConfig(app)
    private val collector = BackupSourceCollector(app)

    // ---------------------------------------------------------------- 备份

    /** 同步 IO：调用方负责放到 `Dispatchers.IO`。 */
    fun backupNow(origin: BackupOrigin = BackupOrigin.MANUAL): BackupOutcome {
        val createdAt = System.currentTimeMillis()
        val name = backupName(createdAt, origin)
        val tmpFile = File(cacheDir("backup-tmp"), name)
        val snapshotDir = cacheDir("backup-snapshot")
        return try {
            // 库快照拿不到写闸会抛异常 → 走下面的 Failed 分支，绝不出半成品包
            val snapshot = collector.snapshotDatabases(snapshotDir)
            val sources = collector.collectSources(snapshot)
            if (sources.isEmpty()) return BackupOutcome.Failed("没有可备份的数据文件")

            val manifest = FileOutputStream(tmpFile).use { out ->
                BackupPackager.pack(
                    sources,
                    BackupMeta(BuildConfig.VERSION_NAME, createdAt, snapshot.counts),
                    out,
                )
            }

            // 只落本地快速列表（还原入口扫描这里）；公共 Downloads 不再写，见类注释
            val localDir = localBackupDir()
            val localFile = File(localDir, name)
            tmpFile.copyTo(localFile, overwrite = true)

            collector.pruneLocal(localDir)
            prunePreRestore(localDir)

            config.recordBackup(createdAt, name)
            BackupOutcome.Success(manifest, name)
        } catch (t: Throwable) {
            BackupOutcome.Failed("备份失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            tmpFile.delete()
            snapshotDir.deleteRecursively()
        }
    }

    /** 本地快速列表目录里的备份，按修改时间倒序。 */
    fun listBackups(): List<BackupFileInfo> =
        localBackupDir().listFiles()
            ?.filter { it.isFile && it.name.startsWith(NAME_PREFIX) }
            ?.map { BackupFileInfo(it.name, it.lastModified(), it.length()) }
            ?.sortedWith(compareByDescending<BackupFileInfo> { it.modifiedAt }.thenByDescending { it.name })
            ?: emptyList()

    /**
     * 把本地列表里的某一份**复制**一份到公共 Downloads（同步 IO，调用方负责 IO 线程）。
     *
     * 只读本地、只复制：原文件保留，不改配置、不参与保留策略轮转。确认闸门在调用方
     * （[BackupExportGate]），这里只管落盘 —— 用户没确认过的名字不该走到这一步。
     *
     * @return 展示用路径；文件不存在或复制失败返回 null
     */
    fun exportBackup(localName: String): String? {
        // 名字来自本地列表，理论上安全；照样过一遍文件名归一，避免任何 ../ 被当成路径片段
        val file = File(localBackupDir(), BackupExport.targetName(localName))
        if (!file.isFile) return null
        return collector.exportToDownloads(file, file.name)
    }

    // ---------------------------------------------------------------- 还原

    /**
     * 还原第一步（同步 IO）：读 URI 流 → 校验清单与 sha256 → 自动备份当前数据 → 解包到暂存目录。
     *
     * 读取动作由调用方注入（SAF 的 `contentResolver.openInputStream`），编排本身在
     * [RestoreIntake] 里且可纯 JVM 测试。任何一步失败都返回 [RestoreOutcome.Rejected]，
     * 且**不改动现有数据**；校验不过时绝不删 `pre-restore` 包。
     */
    suspend fun prepareRestore(openStream: suspend () -> InputStream?): RestoreOutcome {
        val intake = RestoreIntake(File(cacheDir("restore-import"), "import.zip"))
        val verified = when (val outcome = intake.intake(openStream)) {
            is RestoreIntakeOutcome.Rejected -> return RestoreOutcome.Rejected(outcome.reason)
            is RestoreIntakeOutcome.Verified -> outcome
        }
        val imported = verified.zipFile
        return try {
            // 先给当前数据留一份兜底包：还原后发现问题还能再导回来
            val preRestore = backupNow(BackupOrigin.PRE_RESTORE)
            if (preRestore is BackupOutcome.Failed) {
                return RestoreOutcome.Rejected("还原前自动备份失败（${preRestore.message}），已中止还原")
            }
            val preRestoreName = (preRestore as BackupOutcome.Success).localName

            val staging = cacheDir("restore-staging")
            if (!BackupUnpacker.extract(imported, verified.manifest, staging)) {
                return RestoreOutcome.Rejected("解包失败：备份包内容与清单不一致")
            }
            RestoreOutcome.Ready(RestorePlan(staging, verified.manifest, preRestoreName))
        } catch (t: Throwable) {
            RestoreOutcome.Rejected("还原准备失败：${t.message ?: t.javaClass.simpleName}")
        } finally {
            imported.delete()
        }
    }

    /**
     * 还原第二步（同步 IO）：关闭数据库连接 → 原子替换应用数据文件。
     *
     * 替换前先校验暂存区、再落 `restore.marker`；每个文件走「同目录临时文件 + 原子 rename」。
     * 成功返回 [RestoreApplyOutcome.Applied]，调用方随后必须 [restartProcess]：单例 helper 已
     * close，继续使用会抛“attempt to re-open an already-closed object”。
     * 中途中断（进程被杀/崩溃）时 marker 留在盘上，下次启动由 [healIfNeeded] 回滚。
     */
    fun applyRestore(plan: RestorePlan): RestoreApplyOutcome {
        collector.closeDatabases()
        return RestoreFileOperator.apply(
            stagingDir = plan.stagingDir,
            manifest = plan.manifest,
            dataDir = app.dataDir,
            preRestoreName = plan.preRestoreName,
            dbNames = dbNames(),
        )
    }

    /**
     * 启动自愈（同步 IO）：上次还原是否中断？中断则用 pre-restore 包回滚。
     *
     * 应用启动早期调用（见 `MonitorApp.onCreate`），在任何库句柄打开之前完成，避免读到
     * 半新半旧的文件。无 marker 时是纯文件存在性检查，开销可忽略。
     */
    fun healIfNeeded(): HealOutcome = RestoreRecovery.recover(
        dataDir = app.dataDir,
        stagingDir = cacheDir("restore-heal"),
        findBackup = { name -> File(localBackupDir(), name).takeIf { it.isFile } },
        dbNames = dbNames(),
    )

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

    /**
     * 备份文件名：普通包 `monitor-backup-yyyyMMdd-HHmmss.zip`，回滚包 `monitor-prerestore-…`。
     *
     * 回滚包**必须**用独立前缀：两者共用前缀时它会参与 5 份轮转，连续几次备份就把
     * marker 指名的那份淘汰掉，还原中断后 [healIfNeeded] 只能放弃回滚（数据停在半新半旧）。
     */
    private fun backupName(createdAt: Long, origin: BackupOrigin): String {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date(createdAt))
        val prefix = if (origin == BackupOrigin.PRE_RESTORE) PRE_RESTORE_PREFIX else NAME_PREFIX
        return "$prefix$stamp.zip"
    }

    /** 回滚包只留最近 [PRE_RESTORE_KEEP] 份：它不参与普通备份的 5 份轮转，但也不该无限攒。 */
    private fun prunePreRestore(dir: File) {
        val files = dir.listFiles()
            ?.filter { it.isFile && it.name.startsWith(PRE_RESTORE_PREFIX) }
            ?.map { BackupFileInfo(it.name, it.lastModified(), it.length()) }
            ?: return
        RetentionPolicy.expired(files, keep = PRE_RESTORE_KEEP, prefix = PRE_RESTORE_PREFIX)
            .forEach { File(dir, it.name).delete() }
    }

    private fun dbNames(): List<String> = listOf("finance.db", "monitor.db")

    private fun localBackupDir(): File =
        File(app.getExternalFilesDir(null) ?: app.filesDir, LOCAL_DIR).apply { mkdirs() }

    companion object {
        const val LOCAL_DIR = "backups"
        const val NAME_PREFIX = "monitor-backup-"
        const val PRE_RESTORE_PREFIX = "monitor-prerestore-"
        private const val PRE_RESTORE_KEEP = 2
    }
}
