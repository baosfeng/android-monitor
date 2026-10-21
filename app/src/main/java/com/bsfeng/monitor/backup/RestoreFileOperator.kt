package com.bsfeng.monitor.backup

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/** restore.marker 的内容：还原起点 + 可用于回滚的 pre-restore 包名（null = 无回滚包）。 */
data class RestoreMarker(
    val preRestoreName: String?,
    val startedAt: Long,
)

/** 原子替换结果。[Applied] 表示数据已替换且 marker 已清；[Failed] 保留 marker 供下次启动自愈。 */
sealed class RestoreApplyOutcome {
    data object Applied : RestoreApplyOutcome()
    data class Failed(val reason: String) : RestoreApplyOutcome()
}

/**
 * 还原的原子替换（纯 JVM：只用 `java.io` / `java.nio.file`，不依赖 Android）。
 *
 * 不变量：
 * - 校验失败（暂存文件与清单 size/sha256 不符）→ **不写 marker、不碰原数据**；
 * - 开始替换前先原子落 `restore.marker`，全部替换成功后才删 —— 中途断电/崩溃时 marker 留在盘上，
 *   下次启动由 [RestoreRecovery] 用 pre-restore 包回滚；
 * - 每个文件都写「同目录临时文件 → 原子 rename」，不会出现只写了一半的目标文件。
 */
object RestoreFileOperator {

    const val MARKER_NAME = "restore.marker"

    private const val MARKER_TMP_SUFFIX = ".tmp"
    private const val TMP_SUFFIX = ".restoring"
    private const val BUFFER_SIZE = 8 * 1024

    /** 还原会替换主库文件，旧的这些附属文件必须清掉，否则可能被回滚回旧状态。 */
    private val SIDECAR_SUFFIXES = listOf("-wal", "-shm", "-journal")

    fun markerFile(dataDir: File): File = File(dataDir, MARKER_NAME)

    /** 暂存目录与清单逐字节比对：null = 一致，否则返回可展示原因（含第一个不符的文件）。 */
    fun verifyStaging(stagingDir: File, manifest: BackupManifest): String? {
        manifest.files.forEach { entry ->
            if (!BackupPaths.isSafe(entry.path)) return "备份包含非法路径：${entry.path}"
            val file = File(stagingDir, entry.path)
            if (!file.isFile) return "暂存区缺少文件：${entry.path}"
            if (file.length() != entry.size) {
                return "暂存文件大小不符：${entry.path}（实际 ${file.length()}，清单 ${entry.size}）"
            }
            if (!sha256(file).equals(entry.sha256, ignoreCase = true)) {
                return "暂存文件校验和不符：${entry.path}"
            }
        }
        return null
    }

    /**
     * 用暂存目录原子替换应用数据文件。
     *
     * [preRestoreName] 写进 marker，供 [RestoreRecovery] 定位回滚包；[dbNames] 用于清理旧附属文件。
     */
    fun apply(
        stagingDir: File,
        manifest: BackupManifest,
        dataDir: File,
        preRestoreName: String?,
        dbNames: List<String>,
        now: Long = System.currentTimeMillis(),
    ): RestoreApplyOutcome {
        // ① 先校验：不通过就一个字节都不动
        verifyStaging(stagingDir, manifest)?.let { return RestoreApplyOutcome.Failed(it) }

        val marker = markerFile(dataDir)
        return try {
            // ② 落 marker（原子写）：从这里开始，中断都可由下次启动自愈
            writeMarker(marker, RestoreMarker(preRestoreName, now))
            dbNames.forEach { dbName ->
                SIDECAR_SUFFIXES.forEach { suffix ->
                    File(File(dataDir, BackupPaths.DB_DIR), "$dbName$suffix").delete()
                }
            }
            // ③ 逐个文件：同目录临时文件 → 校验后原子 rename
            manifest.files.forEach { entry ->
                if (!BackupPaths.isSafe(entry.path)) {
                    return RestoreApplyOutcome.Failed("备份包含非法路径：${entry.path}")
                }
                val source = File(stagingDir, entry.path)
                if (!source.isFile) return RestoreApplyOutcome.Failed("暂存区缺少文件：${entry.path}")
                val target = File(dataDir, entry.path)
                val parent = target.parentFile
                if (parent != null && !parent.exists() && !parent.mkdirs()) {
                    return RestoreApplyOutcome.Failed("无法创建目录：${parent.path}")
                }
                val tmp = File(parent, target.name + TMP_SUFFIX)
                copy(source, tmp)
                moveAtomic(tmp, target)
            }
            // ④ 全部替换成功：清暂存区与 marker
            stagingDir.deleteRecursively()
            marker.delete()
            RestoreApplyOutcome.Applied
        } catch (t: Throwable) {
            // marker 故意保留：下次启动用 pre-restore 包回滚
            RestoreApplyOutcome.Failed("写入失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    /** 原子写 marker：先写 `.tmp` 再 rename，避免出现只写了一半的 marker。 */
    fun writeMarker(file: File, marker: RestoreMarker) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, file.name + MARKER_TMP_SUFFIX)
        tmp.writeText(
            "startedAt=${marker.startedAt}\npreRestoreName=${marker.preRestoreName ?: ""}\n",
            Charsets.UTF_8,
        )
        moveAtomic(tmp, file)
    }

    /** marker 不存在返回 null；内容不可解析时按「有 marker、无回滚包」处理（name=null）。 */
    fun readMarker(file: File): RestoreMarker? {
        if (!file.isFile) return null
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return RestoreMarker(null, 0L)
        val values = text.lineSequence()
            .mapNotNull { line ->
                val i = line.indexOf('=')
                if (i <= 0) null else line.substring(0, i).trim() to line.substring(i + 1).trim()
            }
            .toMap()
        return RestoreMarker(
            preRestoreName = values["preRestoreName"]?.takeIf { it.isNotBlank() },
            startedAt = values["startedAt"]?.toLongOrNull() ?: 0L,
        )
    }

    private fun copy(source: File, target: File) {
        FileInputStream(source).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output, BUFFER_SIZE) }
        }
    }

    /** 同目录 rename：用 ATOMIC_MOVE；文件系统不支持时退回普通 move（同盘 rename 仍是原子的）。 */
    private fun moveAtomic(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
                StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = input.read(buf)
                if (read < 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }
}
