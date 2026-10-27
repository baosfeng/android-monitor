package com.bsfeng.monitor.ui

import com.bsfeng.monitor.ui.live.startOfDay
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 今日窗口 = 本地 00:00（回归 D1：原式 `now - (now % 86400000L)` 是 UTC 零点）。
 *
 * 「今天」按系统时区推算、不写死 Asia/Shanghai：用例在任何时区的机器上都必须成立。
 */
class MainViewModelTodayWindowTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    private fun atLocal(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        java.time.LocalDate.of(year, month, day)
            .atTime(hour, minute)
            .atZone(zone)
            .toInstant()
            .toEpochMilli()

    @Test
    fun `今日窗口从本地零点起算`() {
        val now = atLocal(2026, 10, 28, 2, 28)
        val todayStart = startOfDay(now)

        // 本地零点到 02:28 之间的事件在窗口内
        assertTrue("本地 00:05 属于今天", atLocal(2026, 10, 28, 0, 5) in todayStart..now)
        assertTrue("本地零点整算今天", todayStart == atLocal(2026, 10, 28, 0, 0))
    }

    @Test
    fun `昨天20点不落在今日窗口内`() {
        val now = atLocal(2026, 10, 28, 2, 28)
        val todayStart = startOfDay(now)
        // 这一条是 D1 的核心回归：UTC 取模会把昨天 20:00 当成「今天」
        assertFalse("昨天 20:00 必须被排除", atLocal(2026, 10, 27, 20, 0) >= todayStart)
        // 直接对照旧式算出来的窗口，确认两者在非 UTC 时区确实不同（若整机就是 UTC，跳过这条差异断言）
        val utcStart = now - (now % 86_400_000L)
        if (utcStart != todayStart) {
            assertTrue("旧式的 UTC 零点窗口会把昨天 20:00 算进今天", atLocal(2026, 10, 27, 20, 0) >= utcStart)
        }
    }

    @Test
    fun `跨天边界前后各一小时分属不同窗口`() {
        val justBeforeMidnight = atLocal(2026, 10, 27, 23, 59)
        val justAfterMidnight = atLocal(2026, 10, 28, 0, 1)
        val todayStart = startOfDay(justAfterMidnight)

        assertTrue("23:59 的昨天记录不进今天", justBeforeMidnight < todayStart)
        assertTrue("00:01 的记录进今天", justAfterMidnight >= todayStart)
        assertTrue(
            "本地日界应在 24 小时之内",
            todayStart - startOfDay(justBeforeMidnight) in 1..86_400_000L,
        )
    }

    @Test
    fun `startOfDay 结果始终是本地零点`() {
        val now = Instant.now().toEpochMilli()
        val start = startOfDay(now)
        val local = Instant.ofEpochMilli(start).atZone(zone)
        assertTrue(start <= now)
        assertTrue("时分秒必须归零", local.hour == 0 && local.minute == 0 && local.second == 0)
    }
}
