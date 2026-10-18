package com.bsfeng.monitor.transfer.format

import com.bsfeng.monitor.domain.transfer.PairingPolicy
import com.bsfeng.monitor.domain.transfer.TransferFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 体积 / 速度 / 倒计时格式化 + 免确认策略（纯 JVM，无 Android 依赖）。
 *
 * 文案口径对齐需求：「1.2 / 4.1 MB · 8.4 MB/s」「免配对 08:32」。
 */
class TransferFormatTest {

    @Test
    fun `体积按 1024 进制保留一位小数`() {
        assertEquals("0 B", TransferFormat.fileSize(0L))
        assertEquals("512 B", TransferFormat.fileSize(512L))
        assertEquals("1023 B", TransferFormat.fileSize(1023L))
        assertEquals("1.0 KB", TransferFormat.fileSize(1024L))
        assertEquals("1.2 MB", TransferFormat.fileSize(1_300_000L))
        assertEquals("4.1 MB", TransferFormat.fileSize(4_300_000L))
        assertEquals("5.0 GB", TransferFormat.fileSize(5L * 1024 * 1024 * 1024))
    }

    @Test
    fun `负数体积按下限 0 处理`() {
        assertEquals("0 B", TransferFormat.fileSize(-1L))
    }

    @Test
    fun `进度文案两数共用总量单位`() {
        assertEquals("1.2 / 4.1 MB", TransferFormat.progressPair(1_300_000L, 4_300_000L))
        assertEquals("0.0 / 4.1 MB", TransferFormat.progressPair(0L, 4_300_000L))
        // 总量 1024 起按 KB 计（与 fileSize(1024) = "1.0 KB" 同口径）
        assertEquals("0.5 / 1.0 KB", TransferFormat.progressPair(512L, 1024L))
        assertEquals("512 / 1000 B", TransferFormat.progressPair(512L, 1000L))
        // 已传超过总量（异常输入）按总量封顶
        assertEquals("4.1 / 4.1 MB", TransferFormat.progressPair(9_000_000L, 4_300_000L))
    }

    @Test
    fun `速度文案与空值`() {
        assertEquals("8.4 MB/s", TransferFormat.speed(8_800_000L))
        assertEquals("512 B/s", TransferFormat.speed(512L))
        assertEquals("", TransferFormat.speed(0L))
        assertEquals("", TransferFormat.speed(-5L))
    }

    @Test
    fun `免配对窗口倒计时为 mm ss`() {
        assertEquals("08:32", TransferFormat.windowRemaining(8 * 60_000L + 32_000L))
        assertEquals("00:00", TransferFormat.windowRemaining(0L))
        assertEquals("00:00", TransferFormat.windowRemaining(-1_000L))
        assertEquals("30:00", TransferFormat.windowRemaining(30 * 60_000L))
    }

    @Test
    fun `阈值文案`() {
        assertEquals("关闭", TransferFormat.threshold(0))
        assertEquals("10 MB", TransferFormat.threshold(10))
        assertEquals("100 MB", TransferFormat.threshold(100))
    }

    @Test
    fun `窗口生效与剩余时间`() {
        val now = 10_000L
        assertTrue(PairingPolicy.isWindowActive(now + 1L, now))
        assertFalse(PairingPolicy.isWindowActive(now, now))
        assertFalse(PairingPolicy.isWindowActive(0L, now))
        assertEquals(5_000L, PairingPolicy.remainingMs(now + 5_000L, now))
        assertEquals(0L, PairingPolicy.remainingMs(now - 1L, now))
    }

    @Test
    fun `体积阈值命中边界 等于阈值算命中`() {
        val threshold = PairingPolicy.thresholdBytes(10)
        assertEquals(10L * 1024 * 1024, threshold)
        assertTrue(PairingPolicy.isAutoApproved(0L, 10, threshold, 0L))
        assertTrue(PairingPolicy.isAutoApproved(0L, 10, threshold - 1, 0L))
        assertFalse(PairingPolicy.isAutoApproved(0L, 10, threshold + 1, 0L))
    }

    @Test
    fun `阈值关闭时一律需要 PIN`() {
        assertFalse(PairingPolicy.isAutoApproved(0L, PairingPolicy.THRESHOLD_OFF_MB, 0L, 0L))
        assertFalse(PairingPolicy.isAutoApproved(0L, 0, 10L * 1024 * 1024, 0L))
    }

    @Test
    fun `窗口与阈值是或关系`() {
        val now = 1_000L
        // 只有窗口
        assertTrue(PairingPolicy.isAutoApproved(now + 1_000L, 0, 1_000_000_000L, now))
        // 只有阈值
        assertTrue(PairingPolicy.isAutoApproved(0L, 10, 1024L, now))
        // 都没有
        assertFalse(PairingPolicy.isAutoApproved(0L, 0, 1024L, now))
        // 窗口过期且阈值未命中
        assertFalse(PairingPolicy.isAutoApproved(now - 1L, 10, 20L * 1024 * 1024, now))
    }

    @Test
    fun `非法总大小只按窗口判定`() {
        assertFalse(PairingPolicy.isAutoApproved(0L, 10, -1L, 0L))
        assertTrue(PairingPolicy.isAutoApproved(10L, 10, -1L, 0L))
    }

    @Test
    fun `可选档位与协议设置一致`() {
        assertEquals(listOf(5, 10, 30), PairingPolicy.WINDOW_MINUTES)
        assertEquals(listOf(0, 10, 50, 100), PairingPolicy.THRESHOLD_OPTIONS_MB)
    }
}
