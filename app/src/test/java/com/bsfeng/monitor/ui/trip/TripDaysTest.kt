package com.bsfeng.monitor.ui.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 行程日期切换的边界（TripDays）：最近 30 天到今天，越界回退，今天不能再往后。
 *
 * 时区固定 Asia/Shanghai，避免跑测试的机器时区影响结果；日期加减在纯逻辑里按「日」算，
 * 不按固定 86400000 毫秒推算（跨夏令时地区会少一小时）。
 */
class TripDaysTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val now: Long = Instant.parse("2026-10-24T10:30:00Z").toEpochMilli() // 北京时间 10-24 18:30
    private val today: Long = millis("2026-10-24T00:00:00+08:00")
    private val yesterday: Long = millis("2026-10-23T00:00:00+08:00")
    private val firstDay: Long = millis("2026-09-25T00:00:00+08:00") // 今天往前 29 天

    private fun millis(text: String): Long = Instant.parse(text).toEpochMilli()

    @Test
    fun `今天取当地零点`() {
        assertEquals(today, TripDays.today(now, zone))
    }

    @Test
    fun `前一天与后一天各自走一天`() {
        assertEquals(yesterday, TripDays.previous(today, now, zone))
        assertEquals(today, TripDays.next(yesterday, now, zone))
    }

    @Test
    fun `今天不能再往后`() {
        assertEquals(today, TripDays.next(today, now, zone))
        assertFalse(TripDays.canNext(today, now, zone))
        assertTrue(TripDays.canNext(yesterday, now, zone))
    }

    @Test
    fun `窗口共三十天含今天`() {
        assertEquals(firstDay, TripDays.firstSelectable(now, zone))
        val spanDays = (today - firstDay) / 86_400_000L
        assertEquals((TripDays.WINDOW_DAYS - 1).toLong(), spanDays)
    }

    @Test
    fun `最早一天不能再往前`() {
        assertFalse(TripDays.canPrevious(firstDay, now, zone))
        assertTrue(TripDays.canPrevious(yesterday, now, zone))
        assertEquals(firstDay, TripDays.previous(firstDay, now, zone))
    }

    @Test
    fun `超范围回退夹到边界`() {
        assertEquals(firstDay, TripDays.clamp(firstDay - 86_400_000L * 40L, now, zone))
        assertEquals(today, TripDays.clamp(today + 86_400_000L * 3L, now, zone))
        assertEquals(yesterday, TripDays.clamp(yesterday, now, zone))
    }

    @Test
    fun `今天窗口截到此刻历史日期取整日`() {
        assertEquals(now, TripDays.dayEnd(today, now, zone))
        assertEquals(today, TripDays.dayEnd(yesterday, now, zone))
    }
}
