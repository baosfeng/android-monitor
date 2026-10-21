package com.bsfeng.monitor.backup

import java.io.File
import java.io.FileOutputStream
import java.io.InputStream

/** SAF 选包编排的结果：通过校验时带回落盘后的 zip 与清单，否则带可直接展示的原因。 */
sealed class RestoreIntakeOutcome {
    data class Verified(val zipFile: File, val manifest: BackupManifest) : RestoreIntakeOutcome()
    data class Rejected(val reason: String) : RestoreIntakeOutcome()
}

/**
 * 「读 URI 字节流 → 落临时 zip → 校验清单 / sha256 → 交给还原」的编排层。
 *
 * 不依赖 Activity / ContentResolver：读取动作由调用方注入（`suspend () -> InputStream?`），
 * 因此整段编排可以在纯 JVM 单测里跑通（见 `RestoreIntakeTest`）。
 *
 * 口径：校验完全复用 [BackupUnpacker.verify] —— 缺清单、size/sha256 不符、schemaVersion
 * 高于当前代码支持值一律拒绝；本层只读不写应用数据目录，拒绝时现有数据不受影响。
 */
class RestoreIntake(private val scratchFile: File) {

    suspend fun intake(openStream: suspend () -> InputStream?): RestoreIntakeOutcome {
        return try {
            scratchFile.parentFile?.mkdirs()
            val opened = openStream() ?: return RestoreIntakeOutcome.Rejected("无法读取所选文件")
            opened.use { input ->
                FileOutputStream(scratchFile).use { output -> input.copyTo(output) }
            }
            if (scratchFile.length() == 0L) {
                return RestoreIntakeOutcome.Rejected("所选文件为空")
            }
            when (val verified = BackupUnpacker.verify(scratchFile)) {
                is BackupVerify.Invalid -> RestoreIntakeOutcome.Rejected(verified.reason)
                is BackupVerify.Ok -> RestoreIntakeOutcome.Verified(scratchFile, verified.manifest)
            }
        } catch (t: Throwable) {
            RestoreIntakeOutcome.Rejected("还原准备失败：${t.message ?: t.javaClass.simpleName}")
        }
    }
}
