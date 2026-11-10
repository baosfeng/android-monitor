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
    fun `十五秒周期按 15 秒整窗计算`() {
        // 15 秒周期不再被夹成 30：窗口边界在 0/15/30/45 秒处进位
        assertEquals(15, TotpCountdown.of(0L, 15).remainingSec)
        assertEquals(1.0f, TotpCountdown.of(0L, 15).progress, 0.0001f)
        assertEquals(8, TotpCountdown.of(7_000L, 15).remainingSec)
        assertEquals(15, TotpCountdown.of(15_000L, 15).remainingSec)
        assertEquals(1, TotpCountdown.of(14_999L, 15).remainingSec)
        // 与 30 秒口径不同：同一时刻落在不同窗口位置
        assertTrue(TotpCountdown.of(15_000L, 15).progress != TotpCountdown.of(15_000L, 30).progress)
    }

    @Test
    fun `一秒周期与最大周期都能算出合法倒计时`() {
        // 1 秒周期：任意时刻剩余都是 1 秒、进度恒接近满格
        assertEquals(1, TotpCountdown.of(0L, 1).remainingSec)
        assertEquals(1, TotpCountdown.of(999L, 1).remainingSec)
        assertEquals(1, TotpCountdown.of(1_000L, 1).remainingSec)
        // 86400 秒周期：半天处剩一半，且剩余秒数不超过周期
        assertEquals(43_200, TotpCountdown.of(43_200_000L, TotpParams.MAX_PERIOD_SEC).remainingSec)
        assertEquals(0.5f, TotpCountdown.of(43_200_000L, TotpParams.MAX_PERIOD_SEC).progress, 0.0001f)
    }

    @Test
    fun `周期按真实值计时只在越界时夹紧`() {
        // 45 秒周期按 45 秒整窗走，不再降级成 30
        assertEquals(45, TotpCountdown.of(0L, 45).remainingSec)
        assertEquals(30, TotpCountdown.of(15_000L, 45).remainingSec)
        assertEquals(1, TotpCountdown.of(44_500L, 45).remainingSec)
        // 越界夹紧：0 / 负数 → 1 秒，超上限 → 86400 秒
        assertEquals(TotpCountdown.of(0L, 1), TotpCountdown.of(0L, 0))
        assertEquals(TotpCountdown.of(0L, 1), TotpCountdown.of(0L, -60))
        assertEquals(86_400, TotpCountdown.of(0L, TotpParams.MAX_PERIOD_SEC + 1).remainingSec)
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