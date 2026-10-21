package com.bsfeng.monitor.backup

import java.io.File

/** 启动自愈结果；调用方按它决定日志级别，不改变启动流程。 */
sealed class HealOutcome {
    data object NothingToDo : HealOutcome()
    data class RolledBack(val preRestoreName: String) : HealOutcome()
    data class NoBackup(val reason: String) : HealOutcome()
    data class Failed(val reason: String) : HealOutcome()
}

/**
 * 启动自愈：还原中途中断（`restore.marker` 还在）时，用 pre-restore 包把数据回滚到还原前状态。
 *
 * 纯 JVM：包查找与暂存目录由调用方注入，不依赖 Context。
 * 口径：
 * - 没有 marker → [HealOutcome.NothingToDo]；
 * - marker 有包名且包可用 → 解包 → 原子替换 → 清 marker，返回 [HealOutcome.RolledBack]；
 * - 包缺失 / 不可用 → 清 marker 并返回 [HealOutcome.NoBackup]：继续每次启动重试没有意义，
 *   用户仍可从「数据与备份」手动选一份包还原；
 * - 回滚写入失败 → 保留 marker，返回 [HealOutcome.Failed]，下次启动再试。
 */
object RestoreRecovery {

    fun recover(
        dataDir: File,
        stagingDir: File,
        findBackup: (String) -> File?,
        dbNames: List<String>,
        now: Long = System.currentTimeMillis(),
    ): HealOutcome {
        val markerFile = RestoreFileOperator.markerFile(dataDir)
        if (!markerFile.exists()) return HealOutcome.NothingToDo

        val marker = RestoreFileOperator.readMarker(markerFile) ?: RestoreMarker(null, 0L)
        val zip = marker.preRestoreName?.let(findBackup)?.takeIf { it.isFile }
        if (zip == null) {
            markerFile.delete()
            return HealOutcome.NoBackup("未找到 pre-restore 备份包，已跳过自动回滚")
        }
        val manifest = when (val verified = BackupUnpacker.verify(zip)) {
            is BackupVerify.Invalid -> {
                markerFile.delete()
                return HealOutcome.NoBackup("pre-restore 包不可用（${verified.reason}），已跳过自动回滚")
            }
            is BackupVerify.Ok -> verified.manifest
        }

        if (stagingDir.exists() && !stagingDir.deleteRecursively()) {
            return HealOutcome.Failed("无法清理回滚暂存目录：${stagingDir.path}")
        }
        if (!BackupUnpacker.extract(zip, manifest, stagingDir)) {
            stagingDir.deleteRecursively()
            markerFile.delete()
            return HealOutcome.NoBackup("pre-restore 包解包失败，已跳过自动回滚")
        }
        val applied = RestoreFileOperator.apply(
            stagingDir = stagingDir,
            manifest = manifest,
            dataDir = dataDir,
            preRestoreName = marker.preRestoreName,
            dbNames = dbNames,
            now = now,
        )
        return when (applied) {
            is RestoreApplyOutcome.Applied -> HealOutcome.RolledBack(marker.preRestoreName ?: zip.name)
            is RestoreApplyOutcome.Failed -> HealOutcome.Failed(applied.reason)
        }
    }
}
