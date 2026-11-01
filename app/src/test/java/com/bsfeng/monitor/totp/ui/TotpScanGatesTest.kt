package com.bsfeng.monitor.totp.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer

/** 扫码链路的纯逻辑：帧限流 / YUV 取 Y / 结果去重 —— 三路径各钉一遍。 */
class TotpScanGatesTest {

    @Test
    fun `帧限流 首个放行 间隔内拒绝 到点放行`() {
        val gate = TotpFrameGate(intervalMs = 300)
        assertTrue("首帧必放行", gate.tryAcquire(1_000))
        assertFalse("199ms 时还不到点", gate.tryAcquire(1_199))
        assertFalse("299ms 仍拒绝", gate.tryAcquire(1_299))
        assertTrue("满 300ms 放行", gate.tryAcquire(1_300))
        assertTrue("reset 后立刻放行", gate.reset().let { gate.tryAcquire(1_301) })
    }

    @Test
    fun `帧限流 时钟回拨按未到点处理`() {
        val gate = TotpFrameGate(intervalMs = 300)
        assertTrue(gate.tryAcquire(5_000))
        assertFalse("时钟倒退不能骗出第二帧", gate.tryAcquire(4_000))
    }

    @Test
    fun `YUV 取 Y rowStride 带填充时逐行取`() {
        // 2x2 图像，rowStride=4（每行尾部 2 字节填充），pixelStride=1
        val buffer = ByteBuffer.allocateDirect(8)
        byteArrayOf(11, 12, 99, 99, 21, 22, 99, 99).forEach { buffer.put(it) }
        buffer.position(0)
        val luma = copyLuminance(buffer, rowStride = 4, pixelStride = 1, width = 2, height = 2)
        assertArrayEquals(byteArrayOf(11, 12, 21, 22), luma)
    }

    @Test
    fun `YUV 取 Y pixelStride 大于 1 时跳采`() {
        val buffer = ByteBuffer.allocateDirect(4)
        byteArrayOf(1, 9, 2, 9).forEach { buffer.put(it) }
        buffer.position(0)
        val luma = copyLuminance(buffer, rowStride = 4, pixelStride = 2, width = 2, height = 1)
        assertArrayEquals(byteArrayOf(1, 2), luma)
    }

    @Test
    fun `YUV 取 Y 尺寸或缓冲不合法返回 null`() {
        val buffer = ByteBuffer.allocateDirect(4)
        assertNull("宽度 0", copyLuminance(buffer, 4, 1, 0, 1))
        assertNull("缓冲不足", copyLuminance(buffer, rowStride = 8, pixelStride = 1, width = 4, height = 2))
        assertNull("非法行距", copyLuminance(buffer, 0, 1, 1, 1))
    }

    @Test
    fun `结果去重 同码只上抛一次 换码放行`() {
        val gate = TotpResultGate()
        assertTrue(gate.accept("otpauth://totp/A?secret=JBSWY3DPEHPK3PXP"))
        assertFalse("连续帧解出同一个码不能重复入库", gate.accept("otpauth://totp/A?secret=JBSWY3DPEHPK3PXP"))
        assertTrue("换了码立刻放行", gate.accept("otpauth://totp/B?secret=JBSWY3DPEHPK3PXP"))
        assertFalse("null 直接拒绝", gate.accept(null))
        assertFalse("空白直接拒绝", gate.accept("   "))
        gate.reset()
        assertTrue("reset 后可再次接受同一个码", gate.accept("otpauth://totp/B?secret=JBSWY3DPEHPK3PXP"))
    }

    @Test
    fun `默认节流间隔是 300ms`() {
        assertEquals(300L, TotpFrameGate.DEFAULT_INTERVAL_MS)
    }
}
