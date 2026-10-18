package com.bsfeng.monitor.transfer.transport

import com.bsfeng.monitor.domain.transfer.CHUNK_SIZE
import com.bsfeng.monitor.domain.transfer.PART_SUFFIX
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/**
 * 流式落盘 / 续传基元（协议 §6）。
 *
 * - 固定 [CHUNK_SIZE] 缓冲，内存占用与文件大小无关；
 * - 边写边算 sha256，返回累计摘要；
 * - `.part` 临时文件命名与「同名追加 ` (1)`」规则集中在这里，便于单测；
 * - 越界即为错误：实际字节数超过声明大小抛 [IOException]，由调用方转 4004/4005。
 */
object StreamCopier {

    /** 单次拷贝结果。 */
    data class CopyResult(
        val bytesCopied: Long,
        val totalBytes: Long,
        val sha256: String,
    )

    /** 新建 sha256 摘要器。 */
    fun newDigest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    /** 字节数组 → 小写 hex。 */
    fun sha256Hex(bytes: ByteArray): String = toHex(newDigest().digest(bytes))

    /** 输入流 → 小写 hex（流式，读尽为止；不关闭调用方的流）。 */
    fun sha256Of(input: InputStream): String {
        val digest = newDigest()
        val buffer = ByteArray(CHUNK_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            digest.update(buffer, 0, read)
        }
        return toHex(digest.digest())
    }

    /** 文件 → 小写 hex（流式计算，不整file读入内存）。 */
    fun sha256Of(file: File): String {
        val digest = newDigest()
        file.inputStream().use { input ->
            val buffer = ByteArray(CHUNK_SIZE)
            while (true) {
                val read = input.read(buffer)
                if (read <= 0) break
                digest.update(buffer, 0, read)
            }
        }
        return toHex(digest.digest())
    }

    /**
     * 把 [input] 全量拷到 [output]，同时推进 [digest]。
     *
     * @param expectedTotal 声明大小；`<= 0` 表示未知，不做越界校验。
     * @param onChunk 每块回调（累计已拷字节），默认空。
     * @return 累计字节数、声明大小、当前摘要 hex。
     * @throws IOException 实际字节数超过 [expectedTotal]（fail-closed，不静默截断）。
     */
    fun copy(
        input: InputStream,
        output: OutputStream,
        expectedTotal: Long,
        digest: MessageDigest = newDigest(),
        onChunk: (copiedTotal: Long) -> Unit = {},
    ): CopyResult {
        require(expectedTotal >= 0L) { "声明大小不能为负：$expectedTotal" }
        val buffer = ByteArray(CHUNK_SIZE)
        var copied = 0L
        while (true) {
            val read = input.read(buffer)
            if (read <= 0) break
            copied += read
            if (expectedTotal > 0L && copied > expectedTotal) {
                throw IOException("实际字节数超过声明大小：${copied}/${expectedTotal}")
            }
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            onChunk(copied)
        }
        output.flush()
        return CopyResult(copied, expectedTotal, toHex(digest.digest()))
    }

    /** 从当前摘要推进一片字节（断点续传时用于补算已收部分的摘要）。 */
    fun updateDigest(digest: MessageDigest, bytes: ByteArray, length: Int = bytes.size) {
        digest.update(bytes, 0, length)
    }

    /** `.part` 临时文件（按 `fileId` 命名，重启后可续传）。 */
    fun partFile(dir: File, fileId: String): File {
        require(fileId.isNotBlank()) { "fileId 不能为空" }
        return File(dir, sanitizeFileName(fileId) + PART_SUFFIX)
    }

    /**
     * 目标文件名去重：与已有名字冲突时在扩展名前追加 ` (1)`、` (2)`……
     *
     * - `a.jpg` → `a (1).jpg`；`a.tar.gz` → `a.tar (1).gz`；无扩展名 → `a (1)`；
     * - [maxAttempts] 次仍冲突时追加时间戳，保证一定会返回可用名字。
     */
    fun uniqueFileName(
        name: String,
        exists: (String) -> Boolean,
        maxAttempts: Int = 999,
    ): String {
        val safe = sanitizeFileName(name)
        if (!exists(safe)) return safe
        val dot = safe.lastIndexOf('.')
        val base = if (dot > 0) safe.substring(0, dot) else safe
        val ext = if (dot > 0) safe.substring(dot) else ""
        for (i in 1..maxAttempts) {
            val candidate = "$base ($i)$ext"
            if (!exists(candidate)) return candidate
        }
        return "$base (${System.currentTimeMillis()})$ext"
    }

    /** 去掉目录穿越与非法字符，只保留文件名本身。 */
    fun sanitizeFileName(name: String): String {
        val trimmed = name.trim().replace('\\', '/')
        val baseName = trimmed.substringAfterLast('/')
        val cleaned = baseName
            .replace("\u0000", "")
            .trim()
            .trimStart('.')
            .ifBlank { "unnamed" }
        return cleaned.take(MAX_NAME_LENGTH)
    }

    private fun toHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0F])
        }
        return sb.toString()
    }

    private const val HEX = "0123456789abcdef"

    /** 单段文件名上限（避免超长名字导致 MediaStore 插入失败）。 */
    private const val MAX_NAME_LENGTH = 180
}
