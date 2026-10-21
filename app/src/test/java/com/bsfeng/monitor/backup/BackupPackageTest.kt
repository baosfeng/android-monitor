package com.bsfeng.monitor.backup

import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 打包 / 解包 / 清单校验的端到端用例（不碰 Android 存储：源与目标都在临时目录）。
 *
 * 钉住四件事：条目与清单一一对应、sha256 不符即拒、非法路径即拒、解包按相对路径落盘。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupPackageTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun source(path: String, content: String) =
        BackupSource(path) { ByteArrayInputStream(content.toByteArray()) }

    private fun packTo(vararg entries: Pair<String, String>): File {
        val file = temp.newFile("backup-${entries.size}-${System.nanoTime()}.zip")
        val sources = entries.map { source(it.first, it.second) }
        FileOutputStream(file).use { out ->
            BackupPackager.pack(sources, BackupMeta("1.0.0", 1_700_000_000_000L), out)
        }
        return file
    }

    /** 手写一个 zip（用来构造「清单说的」与「实际内容」不一致的坏包）。 */
    private fun writeZip(file: File, entries: List<Pair<String, ByteArray>>) {
        ZipOutputStream(FileOutputStream(file)).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }

    @Test
    fun `打包后清单与条目一一对应`() {
        val zip = packTo(
            "databases/finance.db" to "finance-bytes",
            "shared_prefs/backup_prefs.xml" to "<prefs/>",
        )
        val manifest = BackupUnpacker.readManifest(zip)
        assertNotNull("打包后必须能读回 manifest", manifest)
        assertEquals(2, manifest!!.files.size)
        assertEquals("1.0.0", manifest.appVersionName)
        assertEquals(BackupManifest.SCHEMA_VERSION, manifest.schemaVersion)
        assertEquals(13L, manifest.files[0].size)
        assertEquals(8L, manifest.files[1].size)

        ZipFile(zip).use { zf ->
            assertNotNull(zf.getEntry(BackupManifest.MANIFEST_NAME))
            assertNotNull(zf.getEntry("databases/finance.db"))
            assertNotNull(zf.getEntry("shared_prefs/backup_prefs.xml"))
        }
    }

    @Test
    fun `校验通过返回 Ok`() {
        val zip = packTo("databases/monitor.db" to "monitor")
        val result = BackupUnpacker.verify(zip)
        assertTrue(result is BackupVerify.Ok)
        assertEquals(1, (result as BackupVerify.Ok).manifest.files.size)
    }

    @Test
    fun `内容被篡改时按校验和不符拒绝`() {
        val original = packTo("databases/finance.db" to "original")
        val manifest = BackupUnpacker.readManifest(original)!!
        val tampered = temp.newFile("tampered.zip")
        writeZip(
            tampered,
            listOf(
                "databases/finance.db" to "tampered".toByteArray(),
                BackupManifest.MANIFEST_NAME to manifest.toJson().toString().toByteArray(),
            ),
        )
        val result = BackupUnpacker.verify(tampered)
        assertTrue(result is BackupVerify.Invalid)
        assertTrue((result as BackupVerify.Invalid).reason.contains("校验和不符"))
    }

    @Test
    fun `清单含上跳路径时直接拒绝`() {
        val manifest = BackupManifest(
            schemaVersion = 1,
            appVersionName = "1.0.0",
            createdAt = 1L,
            files = listOf(BackupFileEntry("../evil.txt", 1L, "c".repeat(64))),
            tables = emptyList(),
        )
        val zip = temp.newFile("evil.zip")
        writeZip(zip, listOf(BackupManifest.MANIFEST_NAME to manifest.toJson().toString().toByteArray()))
        val result = BackupUnpacker.verify(zip)
        assertTrue(result is BackupVerify.Invalid)
        assertTrue((result as BackupVerify.Invalid).reason.contains("非法路径"))
    }

    @Test
    fun `清单为空时拒绝`() {
        val manifest = BackupManifest(1, "1.0.0", 1L, emptyList(), emptyList())
        val zip = temp.newFile("empty.zip")
        writeZip(zip, listOf(BackupManifest.MANIFEST_NAME to manifest.toJson().toString().toByteArray()))
        val result = BackupUnpacker.verify(zip)
        assertTrue(result is BackupVerify.Invalid)
        assertTrue((result as BackupVerify.Invalid).reason.contains("清单为空"))
    }

    @Test
    fun `结构版本高于当前支持版本时拒绝`() {
        val manifest = BackupManifest(99, "9.9.9", 1L, emptyList(), emptyList())
        val zip = temp.newFile("future.zip")
        writeZip(zip, listOf(BackupManifest.MANIFEST_NAME to manifest.toJson().toString().toByteArray()))
        val result = BackupUnpacker.verify(zip)
        assertTrue(result is BackupVerify.Invalid)
        assertTrue((result as BackupVerify.Invalid).reason.contains("高于当前 App 支持"))
    }

    @Test
    fun `没有清单的文件不是备份包`() {
        val zip = temp.newFile("not-backup.zip")
        writeZip(zip, listOf("databases/finance.db" to "x".toByteArray()))
        assertEquals(null, BackupUnpacker.readManifest(zip))
        assertTrue(BackupUnpacker.verify(zip) is BackupVerify.Invalid)
    }

    @Test
    fun `解包按相对路径落盘`() {
        val zip = packTo(
            "databases/finance.db" to "abc",
            "files/skip_rules_v1_backup.json" to "{}",
        )
        val manifest = BackupUnpacker.readManifest(zip)!!
        val root = temp.newFolder("staging")
        assertTrue(BackupUnpacker.extract(zip, manifest, root))
        assertEquals("abc", File(root, "databases/finance.db").readText())
        assertEquals("{}", File(root, "files/skip_rules_v1_backup.json").readText())
    }
}
