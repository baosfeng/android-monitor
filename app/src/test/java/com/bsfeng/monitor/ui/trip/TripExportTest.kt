package com.bsfeng.monitor.ui.trip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

/**
 * 导出文件名（TripExportNaming）：日期 + 时刻，避免不同天 / 不同时刻互相覆盖。
 *
 * 时区固定 Asia/Shanghai：文件名是给人看的，跟随系统时区由调用方默认参数决定，测试用固定时区钉住格式。
 */
class TripExportTest {

    private val zone: ZoneId = ZoneId.of("Asia/Shanghai")
    private val dayA: Long = Instant.parse("2026-10-23T16:00:00Z").toEpochMilli() // 10-24 00:00+08
    private val dayB: Long = Instant.parse("2026-10-22T16:00:00Z").toEpochMilli() // 10-23 00:00+08
    private val exportAt: Long = Instant.parse("2026-10-24T10:59:00Z").toEpochMilli() // 10-24 18:59+08

    @Test
    fun `文件名带所选日期与导出时刻`() {
        assertEquals("Trip-20261024-1859.png", TripExportNaming.fileName(dayA, exportAt, zone))
    }

    @Test
    fun `不同日期或不同时刻不会撞名`() {
        val name = TripExportNaming.fileName(dayA, exportAt, zone)
        assertNotEquals(name, TripExportNaming.fileName(dayB, exportAt, zone))
        assertNotEquals(name, TripExportNaming.fileName(dayA, exportAt + 60_000L, zone))
    }

    @Test
    fun `日期与时刻两位数补零`() {
        val early = Instant.parse("2026-01-05T00:07:00+08:00").toEpochMilli()
        val day = Instant.parse("2026-01-05T00:00:00+08:00").toEpochMilli()
        assertEquals("Trip-20260105-0007.png", TripExportNaming.fileName(day, early, zone))
    }

    @Test
    fun `文件名不含路径分隔符且以 png 结尾`() {
        val name = TripExportNaming.fileName(dayA, exportAt, zone)
        assertTrue(name.endsWith(".png"))
        assertTrue(!name.contains("/") && !name.contains("\\"))
    }

    @Test
    fun `相册落点是图片下的行为记录目录`() {
        assertEquals("Pictures/行为记录", TripExportNaming.RELATIVE_PATH)
        assertTrue(!TripExportNaming.RELATIVE_PATH.startsWith("/"))
    }
}
