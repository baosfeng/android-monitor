package com.bsfeng.monitor.ui.live

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.TimeZone

/**
 * 时区与日界（R14）。
 *
 * 回归点：旧实现 `private val ZONE = ZoneId.systemDefault()` 在类加载时固化，改系统时区
 * 后日界一直按旧时区算到进程重启；跨日窗口又用定长 `DAY_MS` 回推，DST 日整体偏 1 小时。
 */
class LiveModelsZoneTest {

    private val newYork = ZoneId.of("America/New_York")
    private val originalZone: TimeZone = TimeZone.getDefault()

    @After
    fun restoreZone() {
        TimeZone.setDefault(originalZone)
    }

    private fun noonOf(date: LocalDate, zone: ZoneId): Long =
        ZonedDateTime.of(date, java.time.LocalTime.NOON, zone).toInstant().toEpochMilli()

    @Test
    fun `春季 DST 切换日的日界只有 23 小时`() {
        // 2026-03-08 America/New_York：02:00 → 03:00，这一天本身只有 23 小时
        val springDay = LocalDate.of(2026, 3, 8)
        val springStart = startOfDay(noonOf(springDay, newYork), newYork)
        val nextDayStart = startOfDay(noonOf(springDay.plusDays(1), newYork), newYork)

        assertEquals(23 * HOUR_MS, nextDayStart - springStart)
        assertEquals(
            "日界是本地 00:00，不是 UTC 偏移",
            springDay.atStartOfDay(newYork).toInstant().toEpochMilli(),
            springStart,
        )
    }

    @Test
    fun `秋季 DST 切换日的日界有 25 小时`() {
        // 2026-11-01 America/New_York：02:00 → 01:00，这一天本身有 25 小时
        val fallDay = LocalDate.of(2026, 11, 1)
        val fallStart = startOfDay(noonOf(fallDay, newYork), newYork)
        val nextDayStart = startOfDay(noonOf(fallDay.plusDays(1), newYork), newYork)

        assertEquals(25 * HOUR_MS, nextDayStart - fallStart)
    }

    @Test
    fun `按日历回推的 7 天窗口起点与 DST 无关`() {
        // 取 DST 后一天：窗口覆盖 3/8 这一天，其中一段只有 23 小时
        val dayAfterSpring = LocalDate.of(2026, 3, 9)
        val now = noonOf(dayAfterSpring, newYork)
        val starts = dayStarts(days = 7, now = now, zone = newYork)

        assertEquals(7, starts.size)
        assertEquals(dayAfterSpring.minusDays(6).atStartOfDay(newYork).toInstant().toEpochMilli(), starts[0])
        assertEquals(dayAfterSpring.atStartOfDay(newYork).toInstant().toEpochMilli(), starts[6])
        // 6 段日历日 = 144 小时；跨过 3/8 的跳变后真实经过 143 小时
        assertEquals(143 * HOUR_MS, starts[6] - starts[0])
        assertEquals("startOfDaysAgo 与 dayStarts 同源", starts[0], startOfDaysAgo(6, now, newYork))
    }

    @Test
    fun `DST 后一天的 7 天轴标签按日历回推`() {
        TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        // 3/9 的 7 天窗口首日是 3/3；旧的「-6×24h」会落在 3/2 23:00，标签写成 03-02
        val now = noonOf(LocalDate.of(2026, 3, 9), newYork)
        val labels = com.bsfeng.monitor.ui.dayLabels(7, now)

        assertEquals("03-03", labels.first())
        assertEquals("03-09", labels.last())
    }

    @Test
    fun `改系统时区后日界立即跟随 不固化到进程重启`() {
        val instant = noonOf(LocalDate.of(2026, 6, 15), ZoneId.of("Asia/Shanghai"))

        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val utcStart = startOfDay(instant)
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Shanghai"))
        val shanghaiStart = startOfDay(instant)

        assertNotEquals("同一时刻在两个时区下的日界必须不同", utcStart, shanghaiStart)
        assertEquals(
            LocalDate.of(2026, 6, 15).atStartOfDay(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli(),
            shanghaiStart,
        )
    }
}
