package com.bsfeng.monitor.backup

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupServerRecordMapperTest {

    @Test
    fun mapsRecordJsonToBackupFileInfo() {
        val json = JSONObject().apply {
            put("backupId", "abc123")
            put("createdAtMs", 1234L)
            put("sizeBytes", 42L)
        }
        val items = BackupServerRecordMapper.toBackupFileInfo(listOf(json))
        assertEquals(1, items.size)
        assertEquals("abc123.zip", items[0].name)
        assertEquals(1234L, items[0].modifiedAt)
        assertEquals(42L, items[0].sizeBytes)
    }

    @Test
    fun mapsRawResponseString() {
        val raw = JSONObject().apply {
            put("items", JSONArray().put(JSONObject().apply {
                put("backupId", "server-backup")
                put("receivedAtMs", 999L)
                put("sizeBytes", 7L)
            }))
        }.toString()
        val items = BackupServerRecordMapper.toBackupFileInfo(raw)
        assertEquals(1, items.size)
        assertEquals("server-backup.zip", items[0].name)
        assertTrue(items[0].modifiedAt == 999L)
    }
}
