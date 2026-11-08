package com.bsfeng.monitor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordTypeTest {

    @Test
    fun `规范名可完整往返`() {
        assertEquals(11, RecordType.CANONICAL_NAMES.size)
        for (name in RecordType.CANONICAL_NAMES) {
            val type = RecordType.fromString(name)
            assertEquals(name, RecordType.toString(type!!))
        }
    }

    @Test
    fun `fromString 不区分大小写并允许空白`() {
        assertEquals(RecordType.SCREEN, RecordType.fromString("screen"))
        assertEquals(RecordType.SCREEN, RecordType.fromString("  ScReEn  "))
        assertEquals(RecordType.APP_USAGE, RecordType.fromString("app_usage"))
    }

    @Test
    fun `空白入参返回 null`() {
        assertNull(RecordType.fromString(""))
        assertNull(RecordType.fromString("   "))
    }

    @Test
    fun `混淆别名按原样小写匹配`() {
        for ((alias, type) in RecordType.LEGACY_ALIASES) {
            assertEquals(type, RecordType.fromString(alias))
            assertEquals(type, RecordType.fromString(alias.uppercase()))
        }
    }

    @Test
    fun `inferFromAction 覆盖唯一归属动作`() {
        assertEquals(RecordType.SCREEN, RecordType.inferFromAction("AD_SKIPPED"))
        assertEquals(RecordType.APP_USAGE, RecordType.inferFromAction("USAGE"))
        assertEquals(RecordType.CHARGING, RecordType.inferFromAction("STARTED"))
        assertEquals(RecordType.PHONE_STATE, RecordType.inferFromAction("RINGING"))
        assertEquals(RecordType.LOCATION, RecordType.inferFromAction("COORDINATE"))
        assertNull(RecordType.inferFromAction("OFF"))
        assertNull(RecordType.inferFromAction("UNKNOWN"))
    }

    @Test
    fun `fromStored 优先名称再回退 action`() {
        assertEquals(RecordType.SCREEN, RecordType.fromStored("SCREEN", "USAGE"))
        assertEquals(RecordType.APP_USAGE, RecordType.fromStored("zzz", "USAGE"))
        assertEquals(RecordType.APP_USAGE, RecordType.fromStored(null, "USAGE"))
        assertNull(RecordType.fromStored(null, "OFF"))
        assertNull(RecordType.fromStored("zzz", null))
    }

    @Test
    fun `toString 覆盖全部类型`() {
        val values = RecordType.CANONICAL_NAMES.map { RecordType.fromString(it)!! }
        assertEquals(values.size, values.toSet().size)
    }
}
