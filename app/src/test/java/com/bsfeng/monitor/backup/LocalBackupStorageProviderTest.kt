package com.bsfeng.monitor.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class LocalBackupStorageProviderTest {

    @Test
    fun localRetentionKeepsOnlyFive() {
        val dir = File(System.getProperty("java.io.tmpdir"), "bsm-retention-" + System.nanoTime())
        dir.mkdirs()
        val provider = LocalBackupStorageProvider(dir)
        for (i in 1..7) {
            val file = File(dir, "monitor-backup-0$i.zip")
            file.writeText("data$i")
            provider.put(file.name, file)
        }
        val listed = provider.list()
        assertEquals(5, listed.size)
        assertTrue(listed.none { it.name.contains("monitor-backup-01.zip") || it.name.contains("monitor-backup-02.zip") })
        dir.deleteRecursively()
    }

    @Test
    fun putGetExistsDeleteRoundTrip() {
        val dir = File(System.getProperty("java.io.tmpdir"), "bsm-roundtrip-" + System.nanoTime())
        dir.mkdirs()
        val provider = LocalBackupStorageProvider(dir)
        val source = File(dir, "source.zip").apply { writeText("payload") }
        assertTrue(provider.put("backup.zip", source))
        assertTrue(provider.exists("backup.zip"))
        assertEquals(source.readText(), provider.get("backup.zip")?.readText())
        assertTrue(provider.delete("backup.zip"))
        assertTrue(!provider.exists("backup.zip"))
        dir.deleteRecursively()
    }
}
