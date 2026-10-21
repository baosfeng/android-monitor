package com.bsfeng.monitor.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 保留策略：最多 5 份，超额删最旧。
 *
 * 纯函数用例，不依赖 Android / 文件系统（`RetentionPolicy.expired` 只挑不删）。
 */
class BackupRetentionPolicyTest {

    private fun info(name: String, modifiedAt: Long) = BackupFileInfo(name, modifiedAt)

    @Test
    fun `7 份保留 5 份时返回最旧的 2 份且最旧在前`() {
        val backups = (1..7).map { info("monitor-backup-0$it.zip", it * 1000L) }
        val expired = RetentionPolicy.expired(backups)
        assertEquals(2, expired.size)
        assertEquals("monitor-backup-01.zip", expired[0].name)
        assertEquals("monitor-backup-02.zip", expired[1].name)
    }

    @Test
    fun `未超额时一份都不删`() {
        val backups = (1..5).map { info("monitor-backup-0$it.zip", it * 1000L) }
        assertTrue(RetentionPolicy.expired(backups).isEmpty())
    }

    @Test
    fun `keep 为 1 时只留最新一份`() {
        val backups = (1..3).map { info("monitor-backup-0$it.zip", it * 1000L) }
        val expired = RetentionPolicy.expired(backups, keep = 1)
        assertEquals(listOf("monitor-backup-01.zip", "monitor-backup-02.zip"), expired.map { it.name })
    }

    @Test
    fun `修改时间相同时按文件名倒序视为更新`() {
        // 同一秒内的两份：文件名里带时间戳，名字大的更新
        val older = info("monitor-backup-20250101-120000.zip", 5_000L)
        val newer = info("monitor-backup-20250101-120001.zip", 5_000L)
        val expired = RetentionPolicy.expired(listOf(older, newer), keep = 1)
        assertEquals(listOf(older.name), expired.map { it.name })
    }

    @Test
    fun `keep 非法值按 1 处理不抛异常`() {
        val backups = listOf(info("a.zip", 1L), info("b.zip", 2L))
        assertEquals(1, RetentionPolicy.expired(backups, keep = 0).size)
    }
}
