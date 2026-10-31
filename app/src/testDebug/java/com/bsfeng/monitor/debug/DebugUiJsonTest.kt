package com.bsfeng.monitor.debug

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * dump 落盘形态（Robolectric 提供真实 `org.json`）。
 *
 * 关键契约：字段名固定、文本超 200 字截断且打 `truncated` 标记、bounds 是四元组 ——
 * 校验方与后续 agent 都按这份 JSON 写脚本，字段一改就全断。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DebugUiJsonTest {

    private fun node(text: String = "", desc: String = ""): UiNode = UiNode(
        index = 7,
        parent = 3,
        depth = 2,
        window = 0,
        className = "android.widget.TextView",
        viewId = "settings_row",
        text = text,
        desc = desc,
        left = 10,
        top = 20,
        right = 110,
        bottom = 80,
        clickable = true,
        scrollable = false,
        editable = false,
        enabled = true,
        selected = false,
        childCount = 2,
    )

    @Test
    fun `字段 - 契约字段齐全、bounds 是四元组`() {
        val json = node(text = "功能开关").toJson()
        val expected = setOf(
            "index", "parent", "depth", "window", "className", "viewIdResourceName", "text",
            "contentDescription", "boundsInScreen", "clickable", "scrollable", "editable",
            "enabled", "selected", "childCount", "truncated",
        )
        assertEquals(expected, json.keys().asSequence().toSet())
        assertEquals(7, json.getInt("index"))
        assertEquals(3, json.getInt("parent"))
        assertEquals(2, json.getInt("depth"))
        assertEquals("android.widget.TextView", json.getString("className"))
        assertEquals("settings_row", json.getString("viewIdResourceName"))
        assertEquals("功能开关", json.getString("text"))
        assertEquals(2, json.getInt("childCount"))
        assertEquals(true, json.getBoolean("clickable"))
        assertEquals(false, json.getBoolean("scrollable"))
        val bounds = json.getJSONArray("boundsInScreen")
        assertEquals(4, bounds.length())
        assertEquals(10, bounds.getInt(0))
        assertEquals(20, bounds.getInt(1))
        assertEquals(110, bounds.getInt(2))
        assertEquals(80, bounds.getInt(3))
        assertEquals(false, json.getBoolean("truncated"))
    }

    @Test
    fun `截断 - 超 200 字的 text 被砍并标 truncated`() {
        val long = "哈".repeat(UiText.MAX_TEXT_CHARS + 50)
        val json = node(text = long).toJson()
        assertEquals(UiText.MAX_TEXT_CHARS, json.getString("text").length)
        assertTrue("超长文本必须标 truncated", json.getBoolean("truncated"))
    }

    @Test
    fun `截断 - contentDescription 超长同样截断并标记`() {
        val json = node(desc = "描".repeat(UiText.MAX_TEXT_CHARS + 1)).toJson()
        assertEquals(UiText.MAX_TEXT_CHARS, json.getString("contentDescription").length)
        assertTrue(json.getBoolean("truncated"))
    }

    @Test
    fun `能力 - 只有 text 变了才影响 truncated 标记（短文本不动）`() {
        val json = node(text = "短", desc = "也短").toJson()
        assertEquals("短", json.getString("text"))
        assertEquals("也短", json.getString("contentDescription"))
        assertEquals(false, json.getBoolean("truncated"))
    }

    @Test
    fun `可序列化 - 整棵树能拼成 JSONObject 且节点数对得上`() {
        val nodes = listOf(node(text = "a"), node(text = "b"))
        val root = JSONObject()
            .put("nodeCount", nodes.size)
            .put("nodes", org.json.JSONArray().apply { nodes.forEach { put(it.toJson()) } })
        assertEquals(2, root.getInt("nodeCount"))
        assertEquals(2, root.getJSONArray("nodes").length())
        assertEquals("b", root.getJSONArray("nodes").getJSONObject(1).getString("text"))
    }
}
