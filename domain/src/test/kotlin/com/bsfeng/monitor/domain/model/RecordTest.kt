package com.bsfeng.monitor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordTest {

    @Test
    fun `Record 默认值与字段`() {
        val r = Record(
            id = "1", type = RecordType.SCREEN, action = "OFF",
            timestamp = 100L, label = "屏幕熄灭",
        )
        assertNull(r.duration)
        assertEquals("", r.owner)
        assertNull(r.detail)
    }

    @Test
    fun `Record copy 与相等`() {
        val a = Record("1", RecordType.WIFI, "CONNECTED", 10L, 5L, "wifi", mapOf("ssid" to "x"), "owner")
        val b = a.copy()
        assertEquals(a, b)
        assertEquals(5L, b.duration)
        assertEquals("x", b.detail?.get("ssid"))
    }
}
