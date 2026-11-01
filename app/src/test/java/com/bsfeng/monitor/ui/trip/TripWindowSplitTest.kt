package com.bsfeng.monitor.ui.trip

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 行程查询窗口切分：窗口按 [dayStart, dayEnd) 半开区间，窗口外的前后各 2 小时只作跨天边界上下文。
 */
class TripWindowSplitTest {

    @Test
    fun `窗口内按半开区间切分且端点 dayEnd 归窗口外`() {
        val records = listOf(
            rec(DAY_START - 1),
            rec(DAY_START),
            rec(DAY_START + 1),
            rec(DAY_END - 1),
            rec(DAY_END),
        )

        val (inside, outside) = splitByWindow(records, DAY_START, DAY_END)

        assertEquals("窗口内 = [dayStart, dayEnd)", listOf(DAY_START, DAY_START + 1, DAY_END - 1), inside.map { it.timestamp })
        assertEquals("边界端点 dayEnd 属于窗口外", listOf(DAY_START - 1, DAY_END), outside.map { it.timestamp })
    }

    @Test
    fun `空输入两边都为空`() {
        val (inside, outside) = splitByWindow(emptyList(), DAY_START, DAY_END)

        assertEquals(0, inside.size)
        assertEquals(0, outside.size)
    }

    private fun rec(ts: Long): Record = Record(
        id = "rec-" + ts,
        type = RecordType.LOCATION,
        action = "COORDINATE",
        timestamp = ts,
        label = "",
    )

    private companion object {
        const val HOUR_MS = 60 * 60_000L
        const val DAY_START = 1_700_000_000_000L
        val DAY_END = DAY_START + 24 * HOUR_MS
    }
}
