package com.bsfeng.monitor.ui.trip

import com.bsfeng.monitor.domain.trip.DaySegment
import com.bsfeng.monitor.domain.trip.Gap
import com.bsfeng.monitor.domain.trip.Move
import com.bsfeng.monitor.domain.trip.Place
import com.bsfeng.monitor.domain.trip.Stay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 时间轴显示顺序（trip 时间轴倒序）：升序输入 → 倒序输出，视觉位次 0 = 最新段，锚点跟随段的身份。
 *
 * 边界口径：空列表 / 单段不炸；start 相同的段保持输入相对顺序（稳定排序）；
 * 视觉第 i 行按时间序第 (段数 - 1 - i) 段查锚点表，时间序第 10 段起为 null（不打锚点，锚点集合严格等于契约 9 段）。
 */
class TripTimelineOrderTest {

    private val dayStart = 1_700_000_000_000L
    private fun min(n: Int): Long = dayStart + n * 60_000L
    private fun place(id: String): Place = Place(id = id, label = id)

    private fun stay(from: Int, to: Int): Stay = Stay(min(from), min(to), place("p0"))

    private fun move(from: Int, to: Int): Move =
        Move(min(from), min(to), place("p0"), place("p1"), distanceMeters = 1200.0)

    private fun gap(from: Int, to: Int): Gap = Gap(min(from), min(to))

    @Test
    fun `升序输入倒序输出且不改输入顺序`() {
        val input: List<DaySegment> = listOf(stay(0, 550), move(550, 570), stay(570, 720))
        val rows = timelineRowsNewestFirst(input)

        assertEquals(3, rows.size)
        assertEquals(input.reversed(), rows)
        // 域层顺序不动：导出与 s01 概览卡仍吃原列表
        assertEquals(listOf(0, 550, 570), input.map { ((it.start - dayStart) / 60_000L).toInt() })
    }

    @Test
    fun `位次零是最晚段且锚点跟段身份走`() {
        // 契约示例：9 段，时间序 0..8 对应 home-am … home-pm
        val input = (0 until 9).map { stay(it * 60, it * 60 + 55) }
        val rows = timelineRowsNewestFirst(input)

        assertEquals(9, rows.size)
        assertSame(input.last(), rows[0])
        // 最上一行是最晚那段（家 15:00–24:00）→ 与原型同一块：home-pm
        assertEquals("s16-seg-home-pm", timelineAnchorFor(0, rows.size))
        // 最下一行是最早那段 → home-am
        assertEquals("s16-seg-home-am", timelineAnchorFor(8, rows.size))
        assertSame(input.first(), rows.last())
    }

    @Test
    fun `空列表与单段边界`() {
        assertTrue(timelineRowsNewestFirst(emptyList()).isEmpty())

        val only = stay(600, 660)
        val rows = timelineRowsNewestFirst(listOf(only))
        assertEquals(1, rows.size)
        assertSame(only, rows[0])
        // 单段即是当天最早也最晚的一段，落在时间序 0 → home-am
        assertEquals("s16-seg-home-am", timelineAnchorFor(0, 1))
    }

    @Test
    fun `同起点保持输入相对顺序`() {
        val first = stay(600, 660)
        val second = move(600, 630)
        val rows = timelineRowsNewestFirst(listOf(first, second))
        assertEquals(listOf<DaySegment>(first, second), rows)
    }

    @Test
    fun `锚点只覆盖时间序前九段`() {
        // 12 段：时间序 9..11 没有锚点，落在视觉最上三行
        assertNull(timelineAnchorFor(0, 12))
        assertNull(timelineAnchorFor(2, 12))
        assertEquals("s16-seg-home-pm", timelineAnchorFor(3, 12))
        assertEquals("s16-seg-home-am", timelineAnchorFor(11, 12))

        // 越界视觉位次不打锚点
        assertNull(timelineAnchorFor(-1, 5))
        assertNull(timelineAnchorFor(5, 5))
        assertNull(timelineAnchorFor(0, 0))
    }

    @Test
    fun `混合类型按开始时间倒序`() {
        val am = stay(0, 550)
        val go = move(550, 570)
        val noon = stay(570, 720)
        val hole = gap(720, 760)
        val pm = stay(760, 1440)

        val rows = timelineRowsNewestFirst(listOf(am, go, noon, hole, pm))
        assertEquals(listOf<DaySegment>(pm, hole, noon, go, am), rows)
    }
}
