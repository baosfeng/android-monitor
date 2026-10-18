package com.bsfeng.monitor.transfer.discovery

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DiscoveryMessage] 编解码测试（Robolectric 提供真实 `org.json`）。
 *
 * 协议 §3 的字段集固定：编码只能写文档里的字段；解码对未定义行为 fail-closed（返回 null）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DiscoveryMessageTest {

    private val protocolFields = setOf(
        "v", "type", "deviceId", "alias", "deviceType", "port", "proto", "caps", "appVersion", "ts",
    )

    @Test
    fun `announce 编码字段集与协议完全一致`() {
        val message = DiscoveryMessage.announce(
            deviceId = "and-7f3a9c",
            alias = "小米14",
            port = 53319,
            appVersion = "1.0.0",
            ts = 1760000000L,
        )
        val json = message.toJson()

        val keys = json.keys().asSequence().toSet()
        assertEquals(protocolFields, keys)
        assertEquals(1, json.getInt("v"))
        assertEquals("announce", json.getString("type"))
        assertEquals("and-7f3a9c", json.getString("deviceId"))
        assertEquals("小米14", json.getString("alias"))
        assertEquals("android", json.getString("deviceType"))
        assertEquals(53319, json.getInt("port"))
        assertEquals("http", json.getString("proto"))
        assertEquals("send", json.getJSONArray("caps").getString(0))
        assertEquals("recv", json.getJSONArray("caps").getString(1))
        assertEquals("1.0.0", json.getString("appVersion"))
        assertEquals(1760000000L, json.getLong("ts"))
    }

    @Test
    fun `discover 只带 v 与 type`() {
        val json = DiscoveryMessage.discover().toJson()
        assertEquals(setOf("v", "type"), json.keys().asSequence().toSet())
        assertEquals("discover", json.getString("type"))
    }

    @Test
    fun `offer 编码字段集与 announce 一致`() {
        val json = DiscoveryMessage.offer(
            deviceId = "mac-123456",
            alias = "MacBook",
            port = 53320,
            deviceType = "macos",
            appVersion = "1.0.0",
            ts = 1760000001L,
        ).toJson()
        assertEquals(protocolFields, json.keys().asSequence().toSet())
        assertEquals("offer", json.getString("type"))
        assertEquals("macos", json.getString("deviceType"))
    }

    @Test
    fun `往返编解码保持字段`() {
        val original = DiscoveryMessage.announce(
            deviceId = "and-abc123",
            alias = "小米 14 Pro",
            port = 53322,
            appVersion = "1.0.0",
            ts = 123L,
        )
        val parsed = DiscoveryMessage.fromJson(original.toJsonString())
        assertEquals(original, parsed)
    }

    @Test
    fun `discover 解码只校验类型`() {
        val parsed = DiscoveryMessage.fromJson("""{"v":1,"type":"discover"}""")
        assertEquals(DiscoveryType.DISCOVER, parsed?.type)
        assertEquals("", parsed?.deviceId)
    }

    @Test
    fun `非法 JSON 返回 null`() {
        assertNull(DiscoveryMessage.fromJson("not json"))
        assertNull(DiscoveryMessage.fromJson(""))
        assertNull(DiscoveryMessage.fromJson("   "))
        assertNull(DiscoveryMessage.fromJson("{}"))
    }

    @Test
    fun `未知 type 返回 null`() {
        assertNull(DiscoveryMessage.fromJson("""{"v":1,"type":"bye","deviceId":"x","port":1}"""))
        assertNull(DiscoveryMessage.fromJson("""{"v":1,"deviceId":"x","port":1}"""))
    }

    @Test
    fun `版本不支持返回 null 缺 v 按 1 处理`() {
        assertNull(DiscoveryMessage.fromJson("""{"v":2,"type":"discover"}"""))
        assertNull(DiscoveryMessage.fromJson("""{"type":"discover","v":0}"""))
        assertEquals(
            DiscoveryType.DISCOVER,
            DiscoveryMessage.fromJson("""{"type":"discover"}""")?.type,
        )
    }

    @Test
    fun `announce 缺 deviceId 或端口越界返回 null`() {
        assertNull(
            DiscoveryMessage.fromJson("""{"v":1,"type":"announce","port":53319}"""),
        )
        assertNull(
            DiscoveryMessage.fromJson("""{"v":1,"type":"announce","deviceId":"and-1","port":0}"""),
        )
        assertNull(
            DiscoveryMessage.fromJson("""{"v":1,"type":"announce","deviceId":"and-1","port":70000}"""),
        )
    }

    @Test
    fun `caps 非数组回落默认能力`() {
        val parsed = DiscoveryMessage.fromJson(
            """{"v":1,"type":"announce","deviceId":"and-1","port":53319,"caps":"oops"}""",
        )
        assertEquals(listOf("send", "recv"), parsed?.caps)
    }

    @Test
    fun `caps 空数组回落默认能力`() {
        val parsed = DiscoveryMessage.fromJson(
            """{"v":1,"type":"announce","deviceId":"and-1","port":53319,"caps":[]}""",
        )
        assertEquals(listOf("send", "recv"), parsed?.caps)
    }

    @Test
    fun `中文别名与 emoji 往返不乱码`() {
        val alias = "开发机 🍎 (MacBook Pro)"
        val json = DiscoveryMessage.announce("mac-1", alias, 53319).toJsonString()
        assertEquals(alias, DiscoveryMessage.fromJson(json)?.alias)
    }

    @Test
    fun `缺省字段取默认值`() {
        val parsed = DiscoveryMessage.fromJson(
            """{"v":1,"type":"announce","deviceId":"and-1","port":53319}""",
        )
        assertEquals("", parsed?.alias)
        assertEquals("http", parsed?.proto)
        assertEquals("", parsed?.appVersion)
        assertEquals(0L, parsed?.ts)
    }

    @Test
    fun `caps 含未知能力时保留原值`() {
        val json = JSONObject()
            .put("v", 1)
            .put("type", "offer")
            .put("deviceId", "mac-1")
            .put("port", 53319)
            .put("caps", JSONArray(listOf("recv", "preview")))
        val parsed = DiscoveryMessage.fromJson(json)
        assertEquals(listOf("recv", "preview"), parsed?.caps)
        assertTrue(parsed!!.caps.contains("preview"))
    }
}
