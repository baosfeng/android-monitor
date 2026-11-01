package com.bsfeng.monitor.domain.totp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 倒计时口径：换码瞬间显示满格、永不出现 0 秒、进度随时间单调收缩。 */
class TotpCountdownTest {

    @Test
    fun `窗口起点显示满格`() {
        assertEquals(30, TotpCountdown.of(0L, 30).remainingSec)
        assertEquals(1.0f, TotpCountdown.of(0L, 30).progress, 0.0001f)
        assertEquals(30, TotpCountdown.of(30_000L, 30).remainingSec)
    }

    @Test
    fun `窗口末尾不出现零秒`() {
        assertEquals(1, TotpCountdown.of(29_999L, 30).remainingSec)
        assertEquals(1, TotpCountdown.of(29_001L, 30).remainingSec)
    }

    @Test
    fun `秒数向上取整且与进度一致`() {
        val half = TotpCountdown.of(15_000L, 30)
        assertEquals(15, half.remainingSec)
        assertEquals(0.5f, half.progress, 0.0001f)

        val oneSecondIn = TotpCountdown.of(1_000L, 30)
        assertEquals(29, oneSecondIn.remainingSec)
        assertEquals(29f / 30f, oneSecondIn.progress, 0.0001f)
    }

    @Test
    fun `六十秒周期按 60 秒整窗计算`() {
        val c = TotpCountdown.of(30_000L, 60)
        assertEquals(30, c.remainingSec)
        assertEquals(0.5f, c.progress, 0.0001f)
        assertEquals(60, TotpCountdown.of(60_000L, 60).remainingSec)
    }

    @Test
    fun `非法周期降级为三十秒`() {
        assertEquals(TotpCountdown.of(15_000L, 30), TotpCountdown.of(15_000L, 45))
        assertEquals(TotpCountdown.of(15_000L, 30), TotpCountdown.of(15_000L, 0))
        assertEquals(TotpCountdown.of(15_000L, 30), TotpCountdown.of(15_000L, -60))
    }

    @Test
    fun `负时间按零处理`() {
        assertEquals(TotpCountdown.of(0L, 30), TotpCountdown.of(-1L, 30))
        assertEquals(TotpCountdown.of(0L, 30), TotpCountdown.of(-86_400_000L, 30))
    }

    @Test
    fun `一整个窗口内秒数单调不增且始终在一到周期之间`() {
        var previous = Int.MAX_VALUE
        // 只扫单个窗口（0..29 秒），第 30 秒已经进位到下一窗口会重新满格
        for (step in 0 until 30) {
            val c = TotpCountdown.of(step * 1_000L, 30)
            assertTrue("第 " + step + " 秒剩余 " + c.remainingSec, c.remainingSec in 1..30)
            assertTrue("第 " + step + " 秒未单调", c.remainingSec <= previous)
            assertTrue("进度越界", c.progress > 0f && c.progress <= 1f)
            previous = c.remainingSec
        }
        assertEquals(1, previous)
    }

    @Test
    fun `毫秒精度不影响整秒判定`() {
        assertEquals(30, TotpCountdown.of(999L, 30).remainingSec)
        assertEquals(1, TotpCountdown.of(29_000L, 30).remainingSec)
        assertEquals(29, TotpCountdown.of(1_001L, 30).remainingSec)
    }
}
