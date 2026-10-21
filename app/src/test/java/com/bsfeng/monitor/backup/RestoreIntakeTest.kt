package com.bsfeng.monitor.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * SAF 选包编排层的纯 JVM 用例：注入 `suspend () -> InputStream?` 模拟 contentResolver，
 * 覆盖正常、缺清单、sha256 不符、schemaVersion 过高、读不到流五种结局。
 *
 * 编排层不接受 Activity / Context，因此这些路径不再只能靠手点模拟器回归。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreIntakeTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val entryPath = "databases/finance.db"
    private val payload = "finance-db-bytes".toByteArray()

    private fun intake() = RestoreIntake(File(temp.newFolder("import"), "import.zip"))

    @Test
    fun `正常选包：读流校验通过并返回清单`() = runBlocking {
        val zip = zipBytes(manifestJson(schemaVersion = BackupManifest.SCHEMA_VERSION), payload)

        val outcome = intake().intake { ByteArrayInputStream(zip) }

        assertTrue("应通过校验：$outcome", outcome is RestoreIntakeOutcome.Verified)
        val verified = outcome as RestoreIntakeOutcome.Verified
        assertTrue(verified.zipFile.isFile)
        assertEquals(1, verified.manifest.files.size)
        assertEquals(entryPath, verified.manifest.files[0].path)
    }

    @Test
    fun `manifest 缺失被拒绝`() = runBlocking {
        val zip = zipBytes(manifestJson = null, payload = payload)

        val outcome = intake().intake { ByteArrayInputStream(zip) }

        assertTrue("应拒绝：$outcome", outcome is RestoreIntakeOutcome.Rejected)
        assertTrue((outcome as RestoreIntakeOutcome.Rejected).reason.contains("manifest"))
    }

    @Test
    fun `sha256 不匹配被拒绝`() = runBlocking {
        // 清单记的是「同样长度、不同内容」的指纹：排除 size 偏差，只让 sha256 不符
        val sameSizeOther = ByteArray(payload.size) { 0x41 }
        val zip = zipBytes(manifestJson(BackupManifest.SCHEMA_VERSION, sameSizeOther), payload)

        val outcome = intake().intake { ByteArrayInputStream(zip) }

        assertTrue("应拒绝：$outcome", outcome is RestoreIntakeOutcome.Rejected)
        assertTrue((outcome as RestoreIntakeOutcome.Rejected).reason.contains("校验和"))
    }

    @Test
    fun `schemaVersion 高于当前支持值时被拒绝`() = runBlocking {
        val zip = zipBytes(manifestJson(BackupManifest.SCHEMA_VERSION + 1), payload)

        val outcome = intake().intake { ByteArrayInputStream(zip) }

        assertTrue("应拒绝：$outcome", outcome is RestoreIntakeOutcome.Rejected)
        assertTrue((outcome as RestoreIntakeOutcome.Rejected).reason.contains("版本"))
    }

    @Test
    fun `读不到 URI 流时被拒绝`() = runBlocking {
        val outcome = intake().intake { null }

        assertTrue("应拒绝：$outcome", outcome is RestoreIntakeOutcome.Rejected)
        assertTrue((outcome as RestoreIntakeOutcome.Rejected).reason.contains("无法读取"))
    }

    @Test
    fun `空文件被拒绝`() = runBlocking {
        val outcome = intake().intake { ByteArrayInputStream(ByteArray(0)) }

        assertTrue("应拒绝：$outcome", outcome is RestoreIntakeOutcome.Rejected)
        assertTrue((outcome as RestoreIntakeOutcome.Rejected).reason.contains("为空"))
    }

    // ---------------------------------------------------------------- 工具

    /** manifestJson 为 null 时生成「只有数据、没有清单」的包。 */
    private fun manifestJson(schemaVersion: Int, payloadForSha: ByteArray = payload): String =
        BackupManifest(
            schemaVersion = schemaVersion,
            appVersionName = "1.0.0",
            createdAt = 1_700_000_000_000L,
            files = listOf(
                BackupFileEntry(entryPath, payloadForSha.size.toLong(), sha256(payloadForSha)),
            ),
            tables = emptyList(),
        ).toJson().toString()

    private fun zipBytes(manifestJson: String?, payload: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            zip.putNextEntry(ZipEntry(entryPath))
            zip.write(payload)
            zip.closeEntry()
            if (manifestJson != null) {
                zip.putNextEntry(ZipEntry(BackupManifest.MANIFEST_NAME))
                zip.write(manifestJson.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
