package com.bsfeng.monitor.backup

import java.io.BufferedOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** 一个待打包条目：`open` 返回原始字节流，测试里可直接给 [java.io.ByteArrayInputStream]。 */
class BackupSource(
    val archivePath: String,
    val open: () -> InputStream,
)

/** 打包时由调用方提供、与具体 IO 无关的元信息。 */
data class BackupMeta(
    val appVersionName: String,
    val createdAt: Long = System.currentTimeMillis(),
    val tables: List<BackupTableCount> = emptyList(),
)

/** 校验结果：Ok 带解析后的清单；Invalid 带可直接展示给用户的中文原因。 */
sealed class BackupVerify {
    data class Ok(val manifest: BackupManifest) : BackupVerify()
    data class Invalid(val reason: String) : BackupVerify()
}

/**
 * 备份包读写（纯 JVM：`java.util.zip` + `MessageDigest`，不依赖 Android）。
 *
 * 结构：数据条目按传入顺序写入，`manifest.json` 最后写，内含每个条目的 size + sha256。
 */
object BackupPackager {

    private const val BUFFER_SIZE = 8 * 1024

    /**
     * 打包并返回清单。会关闭 [out]（调用方传入的流按一次性使用处理）。
     *
     * `createdAt` 同时写入每个 zip 条目的时间戳，保证同样的输入产出同样字节（便于断言）。
     */
    fun pack(sources: List<BackupSource>, meta: BackupMeta, out: OutputStream): BackupManifest {
        val entries = mutableListOf<BackupFileEntry>()
        ZipOutputStream(BufferedOutputStream(out)).use { zip ->
            sources.forEach { source ->
                val digest = MessageDigest.getInstance("SHA-256")
                val entry = ZipEntry(source.archivePath).apply { time = meta.createdAt }
                zip.putNextEntry(entry)
                var size = 0L
                source.open().use { input ->
                    val buf = ByteArray(BUFFER_SIZE)
                    while (true) {
                        val read = input.read(buf)
                        if (read < 0) break
                        digest.update(buf, 0, read)
                        zip.write(buf, 0, read)
                        size += read
                    }
                }
                zip.closeEntry()
                entries += BackupFileEntry(source.archivePath, size, hex(digest.digest()))
            }
            val manifest = BackupManifest(
                schemaVersion = BackupManifest.SCHEMA_VERSION,
                appVersionName = meta.appVersionName,
                createdAt = meta.createdAt,
                files = entries,
                tables = meta.tables,
            )
            zip.putNextEntry(ZipEntry(BackupManifest.MANIFEST_NAME).apply { time = meta.createdAt })
            zip.write(manifest.toJson().toString(2).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return BackupManifest(
            schemaVersion = BackupManifest.SCHEMA_VERSION,
            appVersionName = meta.appVersionName,
            createdAt = meta.createdAt,
            files = entries,
            tables = meta.tables,
        )
    }

    private fun hex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        bytes.forEach { sb.append("%02x".format(it)) }
        return sb.toString()
    }
}

/**
 * 备份包校验与解包（纯 JVM）。
 *
 * 还原顺序固定：先 [verify] 全量比对清单，再 [extract] 落盘 —— 不边校验边覆盖，
 * 避免半途失败留下半新半旧的数据。
 */
object BackupUnpacker {

    private const val BUFFER_SIZE = 8 * 1024

    /** 读取清单；不是本项目备份包（无 manifest.json / JSON 非法）时返回 null。 */
    fun readManifest(zipFile: File): BackupManifest? = runCatching {
        if (!zipFile.isFile || zipFile.length() == 0L) return null
        ZipFile(zipFile).use { zip ->
            val entry = zip.getEntry(BackupManifest.MANIFEST_NAME) ?: return null
            zip.getInputStream(entry).use { input ->
                BackupManifest.parse(input.readBytes().toString(Charsets.UTF_8))
            }
        }
    }.getOrNull()

    /** 清单 + 每个条目的 size/sha256 全量比对；任何一项不符都返回可展示的原因。 */
    fun verify(zipFile: File): BackupVerify {
        val manifest = readManifest(zipFile)
            ?: return BackupVerify.Invalid("不是有效的备份包：缺少或无法解析 manifest.json")
        if (manifest.schemaVersion > BackupManifest.SCHEMA_VERSION) {
            return BackupVerify.Invalid(
                "备份包结构版本 ${manifest.schemaVersion} 高于当前 App 支持的 ${BackupManifest.SCHEMA_VERSION}，请先升级 App"
            )
        }
        if (manifest.files.isEmpty()) {
            return BackupVerify.Invalid("备份包清单为空，没有可还原的文件")
        }
        val seen = mutableSetOf<String>()
        val inspected = runCatching {
            ZipFile(zipFile).use { zip ->
                manifest.files.forEach { file ->
                    if (!BackupPaths.isSafe(file.path)) {
                        return BackupVerify.Invalid("备份包含非法路径：${file.path}")
                    }
                    if (!seen.add(file.path)) {
                        return BackupVerify.Invalid("备份清单含重复条目：${file.path}")
                    }
                    val entry = zip.getEntry(file.path)
                        ?: return BackupVerify.Invalid("备份包缺少文件：${file.path}")
                    val (size, sha256) = digestOf(zip.getInputStream(entry))
                    if (size != file.size) {
                        return BackupVerify.Invalid("文件大小不符：${file.path}（实际 $size，清单 ${file.size}）")
                    }
                    if (!sha256.equals(file.sha256, ignoreCase = true)) {
                        return BackupVerify.Invalid("校验和不符：${file.path}（文件可能已损坏）")
                    }
                }
                true
            }
        }
        inspected.onFailure {
            return BackupVerify.Invalid("备份包无法读取：${it.message ?: it.javaClass.simpleName}")
        }
        // 上面 lambda 内的 `return` 会直接带值返回；走到这里说明全部通过
        return BackupVerify.Ok(manifest)
    }

    /**
     * 把清单内文件解到 [targetRoot]（每个条目保留相对路径）。
     *
     * 只解清单里列出的条目；非法路径 / 缺条目 / 写盘失败返回 false（调用方据此中止还原）。
     */
    fun extract(zipFile: File, manifest: BackupManifest, targetRoot: File): Boolean {
        targetRoot.mkdirs()
        return runCatching {
            ZipFile(zipFile).use { zip ->
                manifest.files.forEach { file ->
                    if (!BackupPaths.isSafe(file.path)) return false
                    val entry = zip.getEntry(file.path) ?: return false
                    val target = File(targetRoot, file.path)
                    target.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { input ->
                        target.outputStream().use { output -> input.copyTo(output, BUFFER_SIZE) }
                    }
                }
            }
            true
        }.getOrDefault(false)
    }

    private fun digestOf(input: InputStream): Pair<Long, String> {
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        input.use {
            val buf = ByteArray(BUFFER_SIZE)
            while (true) {
                val read = it.read(buf)
                if (read < 0) break
                digest.update(buf, 0, read)
                size += read
            }
        }
        val hex = StringBuilder()
        digest.digest().forEach { b -> hex.append("%02x".format(b)) }
        return size to hex.toString()
    }
}
