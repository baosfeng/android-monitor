package com.bsfeng.monitor.backup

import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 还原原子性与启动自愈的纯 JVM 用例（临时目录模拟 dataDir，不装 App、不碰模拟器）。
 *
 * 钉住四件事：正常还原按清单落盘并清 marker；校验不符时一个字节都不动；
 * 中途失败保留 marker；marker 存在时用 pre-restore 包回滚。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RestoreAtomicityTest {

    @get:Rule
    val temp = TemporaryFolder()

    private val dbNames = listOf("finance.db", "monitor.db")

    @Test
    fun `正常还原：原子替换文件并清除 marker`() {
        val dataDir = temp.newFolder("data")
        val staging = temp.newFolder("staging")
        val fresh = "fresh-finance-db".toByteArray()
        writeFile(File(staging, "databases/finance.db"), fresh)
        val manifest = manifestOf("databases/finance.db" to fresh)
        writeFile(File(dataDir, "databases/finance.db"), "old-finance-db".toByteArray())

        val outcome = RestoreFileOperator.apply(staging, manifest, dataDir, "pre.zip", dbNames)

        assertEquals(RestoreApplyOutcome.Applied, outcome)
        assertEquals("fresh-finance-db", File(dataDir, "databases/finance.db").readText())
        assertFalse(RestoreFileOperator.markerFile(dataDir).exists())
        assertFalse(staging.exists())
        assertFalse(File(dataDir, "databases/finance.db.restoring").exists())
    }

    @Test
    fun `暂存区校验不符时不写 marker 也不动原库`() {
        val dataDir = temp.newFolder("data")
        val staging = temp.newFolder("staging")
        writeFile(File(staging, "databases/finance.db"), "staging-content".toByteArray())
        // 清单记的是另一份内容的指纹 → 校验必须失败
        val manifest = manifestOf("databases/finance.db" to "expected-content".toByteArray())
        val target = File(dataDir, "databases/finance.db")
        writeFile(target, "old-finance-db".toByteArray())

        val outcome = RestoreFileOperator.apply(staging, manifest, dataDir, "pre.zip", dbNames)

        assertTrue(outcome is RestoreApplyOutcome.Failed)
        assertEquals("old-finance-db", target.readText())
        assertFalse(RestoreFileOperator.markerFile(dataDir).exists())
    }

    @Test
    fun `写入失败时保留 marker 供下次启动自愈`() {
        val dataDir = temp.newFolder("data")
        val staging = temp.newFolder("staging")
        val fresh = "fresh-finance-db".toByteArray()
        writeFile(File(staging, "databases/finance.db"), fresh)
        val manifest = manifestOf("databases/finance.db" to fresh)
        // databases 被占成一个普通文件 → 无法在其下创建临时文件
        writeFile(File(dataDir, "databases"), "not-a-dir".toByteArray())

        val outcome = RestoreFileOperator.apply(staging, manifest, dataDir, "monitor-backup-x.zip", dbNames)

        assertTrue("应写入失败：$outcome", outcome is RestoreApplyOutcome.Failed)
        val marker = RestoreFileOperator.readMarker(RestoreFileOperator.markerFile(dataDir))
        assertNotNull("失败后必须留下 marker", marker)
        assertEquals("monitor-backup-x.zip", marker?.preRestoreName)
    }

    @Test
    fun `marker 存在时用 pre-restore 包回滚并清 marker`() {
        val dataDir = temp.newFolder("data")
        val staging = temp.newFolder("heal-staging")
        val preRestore = temp.newFolder("pre-restore")
        val zip = File(preRestore, "monitor-backup-20240101-000000.zip")
        val good = "pre-restore-good".toByteArray()
        writeBackupZip(zip, mapOf("databases/finance.db" to good))

        // 模拟中断现场：库是半截内容，且残留旧 WAL；marker 指向 pre-restore 包
        writeFile(File(dataDir, "databases/finance.db"), "half-written".toByteArray())
        writeFile(File(dataDir, "databases/finance.db-wal"), "stale-wal".toByteArray())
        RestoreFileOperator.writeMarker(
            RestoreFileOperator.markerFile(dataDir),
            RestoreMarker(zip.name, 1_700_000_000_000L),
        )

        val outcome = RestoreRecovery.recover(
            dataDir = dataDir,
            stagingDir = staging,
            findBackup = { name -> File(preRestore, name).takeIf { it.isFile } },
            dbNames = dbNames,
        )

        assertEquals(HealOutcome.RolledBack(zip.name), outcome)
        assertEquals("pre-restore-good", File(dataDir, "databases/finance.db").readText())
        assertFalse("旧 WAL 必须清掉", File(dataDir, "databases/finance.db-wal").exists())
        assertFalse(RestoreFileOperator.markerFile(dataDir).exists())
        assertFalse(staging.exists())
    }

    @Test
    fun `marker 存在但无可用包时清 marker 且不动数据`() {
        val dataDir = temp.newFolder("data")
        val staging = temp.newFolder("heal-staging")
        val current = File(dataDir, "databases/finance.db")
        writeFile(current, "current-data".toByteArray())
        RestoreFileOperator.writeMarker(
            RestoreFileOperator.markerFile(dataDir),
            RestoreMarker("missing.zip", 1L),
        )

        val outcome = RestoreRecovery.recover(
            dataDir = dataDir,
            stagingDir = staging,
            findBackup = { null },
            dbNames = dbNames,
        )

        assertTrue(outcome is HealOutcome.NoBackup)
        assertEquals("current-data", current.readText())
        assertFalse("无回滚包时 marker 必须清掉，避免每次启动重试", RestoreFileOperator.markerFile(dataDir).exists())
    }

    @Test
    fun `没有 marker 时自愈是空操作`() {
        val dataDir = temp.newFolder("data")
        val outcome = RestoreRecovery.recover(
            dataDir = dataDir,
            stagingDir = temp.newFolder("heal-staging"),
            findBackup = { error("不应查找备份包") },
            dbNames = dbNames,
        )
        assertEquals(HealOutcome.NothingToDo, outcome)
    }

    // ---------------------------------------------------------------- 工具

    private fun manifestOf(vararg entries: Pair<String, ByteArray>): BackupManifest = BackupManifest(
        schemaVersion = BackupManifest.SCHEMA_VERSION,
        appVersionName = "1.0.0",
        createdAt = 1_700_000_000_000L,
        files = entries.map { (path, bytes) -> BackupFileEntry(path, bytes.size.toLong(), sha256(bytes)) },
        tables = emptyList(),
    )

    private fun writeBackupZip(target: File, entries: Map<String, ByteArray>) {
        writeFile(target, backupZipBytes(entries))
    }

    /** 生成一个合法备份包（清单由内容现算），等价于 App 真实产出的包。 */
    private fun backupZipBytes(entries: Map<String, ByteArray>): ByteArray {
        val manifest = BackupManifest(
            schemaVersion = BackupManifest.SCHEMA_VERSION,
            appVersionName = "1.0.0",
            createdAt = 1_700_000_000_000L,
            files = entries.map { (path, bytes) ->
                BackupFileEntry(path, bytes.size.toLong(), sha256(bytes))
            },
            tables = emptyList(),
        )
        return zipBytes(manifest.toJson().toString(), entries)
    }

    private fun zipBytes(manifestJson: String, entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            entries.forEach { (path, bytes) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(BackupManifest.MANIFEST_NAME))
            zip.write(manifestJson.toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    private fun writeFile(file: File, bytes: ByteArray) {
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
