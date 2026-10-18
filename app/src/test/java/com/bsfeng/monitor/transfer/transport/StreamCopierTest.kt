package com.bsfeng.monitor.transfer.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.util.Random

/**
 * [StreamCopier] 测试（纯 JVM，用临时目录）。
 *
 * 钉住协议 §6：64 KiB 流式计算、sha256 一致、`.part` 命名、同名追加 ` (1)`、越界即报错。
 */
class StreamCopierTest {

    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `拷贝内容与 sha256 正确`() {
        val payload = ByteArray(200_000) { (it % 251).toByte() }
        val output = ByteArrayOutputStream()

        val result = StreamCopier.copy(
            input = ByteArrayInputStream(payload),
            output = output,
            expectedTotal = payload.size.toLong(),
        )

        assertTrue(output.toByteArray().contentEquals(payload))
        assertEquals(payload.size.toLong(), result.bytesCopied)
        assertEquals(payload.size.toLong(), result.totalBytes)
        assertEquals(StreamCopier.sha256Hex(payload), result.sha256)
    }

    @Test
    fun `分块回调单调递增且末值等于总量`() {
        val payload = ByteArray(150_000) { 7 }
        val progresses = mutableListOf<Long>()

        StreamCopier.copy(
            input = ByteArrayInputStream(payload),
            output = ByteArrayOutputStream(),
            expectedTotal = payload.size.toLong(),
            onChunk = { progresses += it },
        )

        assertTrue(progresses.isNotEmpty())
        assertEquals(progresses.sorted(), progresses)
        assertEquals(payload.size.toLong(), progresses.last())
    }

    @Test
    fun `超过声明大小时抛出 IOException 不静默截断`() {
        val payload = ByteArray(2048) { 1 }
        val error = runCatching {
            StreamCopier.copy(
                input = ByteArrayInputStream(payload),
                output = ByteArrayOutputStream(),
                expectedTotal = 1024L,
            )
        }.exceptionOrNull()

        assertTrue(error is IOException)
        assertTrue(error!!.message!!.contains("超过声明大小"))
    }

    @Test
    fun `声明大小未知时不做越界校验`() {
        val payload = ByteArray(4096) { 3 }
        val result = StreamCopier.copy(
            input = ByteArrayInputStream(payload),
            output = ByteArrayOutputStream(),
            expectedTotal = 0L,
        )
        assertEquals(4096L, result.bytesCopied)
    }

    @Test
    fun `负的声明大小抛 IllegalArgumentException`() {
        assertTrue(
            runCatching {
                StreamCopier.copy(ByteArrayInputStream(ByteArray(1)), ByteArrayOutputStream(), -1L)
            }.exceptionOrNull() is IllegalArgumentException,
        )
    }

    @Test
    fun `文件 sha256 与内存一致`() {
        val file = temp.newFile("a.bin")
        val bytes = ByteArray(70_000).also { Random(7).nextBytes(it) }
        file.writeBytes(bytes)

        assertEquals(StreamCopier.sha256Hex(bytes), StreamCopier.sha256Of(file))
    }

    @Test
    fun `输入流 sha256 与内存一致`() {
        val bytes = ByteArray(33_000).also { Random(11).nextBytes(it) }
        assertEquals(StreamCopier.sha256Hex(bytes), StreamCopier.sha256Of(ByteArrayInputStream(bytes)))
    }

    @Test
    fun `part 文件按 fileId 命名并清洗非法字符`() {
        val dir = temp.newFolder("parts")
        assertEquals("f1.part", StreamCopier.partFile(dir, "f1").name)
        // 目录部分被剥离，只保留文件名本身
        assertEquals("b.part", StreamCopier.partFile(dir, "a/b").name)
        assertEquals(File(dir, "f1.part"), StreamCopier.partFile(dir, "f1"))
    }

    @Test
    fun `part 文件 fileId 为空时抛异常`() {
        assertTrue(
            runCatching { StreamCopier.partFile(temp.newFolder("p2"), " ") }.exceptionOrNull()
                is IllegalArgumentException,
        )
    }

    @Test
    fun `同名追加序号`() {
        val existing = mutableSetOf("照片.jpg")
        assertEquals("照片 (1).jpg", StreamCopier.uniqueFileName("照片.jpg", existing::contains))

        existing += "照片 (1).jpg"
        assertEquals("照片 (2).jpg", StreamCopier.uniqueFileName("照片.jpg", existing::contains))

        assertEquals("新文件.jpg", StreamCopier.uniqueFileName("新文件.jpg", existing::contains))
    }

    @Test
    fun `多点文件名只在最后一段扩展名前追加`() {
        val existing = setOf("archive.tar.gz")
        assertEquals("archive.tar (1).gz", StreamCopier.uniqueFileName("archive.tar.gz", existing::contains))
    }

    @Test
    fun `无扩展名与隐藏文件名处理`() {
        val existing = setOf("README")
        assertEquals("README (1)", StreamCopier.uniqueFileName("README", existing::contains))
        // 以点开头的隐藏文件去掉前导点后按无扩展名处理
        assertEquals("bashrc", StreamCopier.sanitizeFileName(".bashrc"))
        assertEquals("bashrc (1)", StreamCopier.uniqueFileName(".bashrc", setOf("bashrc")::contains))
        assertEquals("a (1).", StreamCopier.uniqueFileName("a.", setOf("a.")::contains))
    }

    @Test
    fun `目录穿越被清洗为纯文件名`() {
        assertEquals("passwd", StreamCopier.sanitizeFileName("../../etc/passwd"))
        assertEquals("x.txt", StreamCopier.sanitizeFileName("C:\\tmp\\x.txt"))
        assertEquals("unnamed", StreamCopier.sanitizeFileName("..."))
        assertEquals("unnamed", StreamCopier.sanitizeFileName("   "))
    }

    @Test
    fun `超长文件名被截断`() {
        val long = "a".repeat(500) + ".bin"
        assertTrue(StreamCopier.sanitizeFileName(long).length <= 180)
    }

    @Test
    fun `空前缀文件一定能拿到可用名字`() {
        // 名字冲突量远超 maxAttempts 时回落时间戳后缀，保证不返回已存在名字
        val existing = HashSet<String>()
        var name = StreamCopier.uniqueFileName("x.bin", existing::contains, maxAttempts = 2)
        existing += name
        name = StreamCopier.uniqueFileName("x.bin", existing::contains, maxAttempts = 2)
        existing += name
        name = StreamCopier.uniqueFileName("x.bin", existing::contains, maxAttempts = 2)
        assertFalse(existing.contains(name))
    }
}
