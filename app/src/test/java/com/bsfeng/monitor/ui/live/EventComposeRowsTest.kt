package com.bsfeng.monitor.ui.live

import com.bsfeng.monitor.domain.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * s02「今日事件构成」（锚点 s02-compose）的行口径：五类按 LiveFilter 的类型集合合计、
 * 「其他」= 总数 − 五类之和，行序固定为原型顺序（应用/屏幕/网络/充电/音频/其他）。
 */
class EventComposeRowsTest {

    @Test
    fun `按原型顺序输出六行且其他由总数减五类得出`() {
        val rows = eventComposeRows(
            mapOf(
                RecordType.APP_USAGE to 52,
                RecordType.SCREEN to 31,
                RecordType.WIFI to 18,
                RecordType.CHARGING to 11,
                RecordType.AUDIO to 8,
                RecordType.LOCATION to 8,
            ),
            total = 128,
        )
        assertEquals(
            listOf("应用", "屏幕", "网络", "充电", "音频", "其他"),
            rows.map { it.label },
        )
        assertEquals(listOf(52, 31, 18, 11, 8, 8), rows.map { it.count })
    }

    @Test
    fun `充电与网络按类型集合合并计数`() {
        val rows = eventComposeRows(
            mapOf(
                RecordType.CHARGING to 2,
                RecordType.BATTERY to 1,
                RecordType.WIFI to 3,
                RecordType.PHONE_STATE to 1,
                RecordType.AIRPLANE to 1,
            ),
            total = 8,
        )
        assertEquals(3, rows.first { it.label == "充电" }.count)
        assertEquals(5, rows.first { it.label == "网络" }.count)
        assertEquals(0, rows.first { it.label == "其他" }.count)
    }

    @Test
    fun `总数小于五类之和时其他夹到零不为负`() {
        val rows = eventComposeRows(mapOf(RecordType.SCREEN to 5), total = 3)
        assertEquals(0, rows.last().count)
    }

    @Test
    fun `空数据仍输出六行零值不隐藏行`() {
        val rows = eventComposeRows(emptyMap(), total = 0)
        assertEquals(6, rows.size)
        assertEquals(listOf(0, 0, 0, 0, 0, 0), rows.map { it.count })
    }
}
