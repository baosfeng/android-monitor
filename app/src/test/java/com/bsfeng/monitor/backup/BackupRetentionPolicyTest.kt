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
    fun `回滚包不参与普通备份的轮转`() {
        // 5 份普通备份 + 1 份回滚包：按前缀隔离后，普通备份刚好满额，一份都不该被删。
        // 共用前缀时这里会删掉最旧的 —— 若它恰好是 marker 指名的回滚包，还原中断就无法回滚。
        val backups = (1..5).map { info("monitor-backup-0$it.zip", it * 1000L) } +
            info("monitor-prerestore-01.zip", 100L)

        val expired = RetentionPolicy.expired(backups, prefix = "monitor-backup-")

        assertEquals("普通备份满额时不删任何一份", emptyList<BackupFileInfo>(), expired)
        assertTrue(
            "回滚包不在普通备份的删除范围里",
            expired.none { it.name.startsWith("monitor-prerestore-") },
        )
    }

    @Test
    fun `回滚包按自己的上限删除更旧的`() {
        val backups = listOf(
            info("monitor-prerestore-old.zip", 1_000L),
            info("monitor-prerestore-new.zip", 3_000L),
            info("monitor-prerestore-mid.zip", 2_000L),
            info("monitor-backup-09.zip", 9_000L),
        )

        val expired = RetentionPolicy.expired(backups, keep = 2, prefix = "monitor-prerestore-")

        assertEquals("只删最旧的那份回滚包", listOf("monitor-prerestore-old.zip"), expired.map { it.name })
    }

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
