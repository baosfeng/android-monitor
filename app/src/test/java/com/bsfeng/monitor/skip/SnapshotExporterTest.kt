package com.bsfeng.monitor.skip

import android.content.Context
import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/**
 * SnapshotExporter 单元测试（Robolectric）。
 *
 * 覆盖：正常路径（三层节点树的字段与 children 层级）、
 * 边界（单节点、空文本省略、超深树截断）、
 * 异常（root 为 null、快照目录创建失败均返回 null 且不抛异常）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SnapshotExporterTest {

    @After
    fun cleanUp() {
        // 清理快照目录，避免测试间相互影响
        File(RuntimeEnvironment.getApplication().filesDir, "snapshots").deleteRecursively()
    }

    private fun node(
        className: String = "android.widget.TextView",
        text: String? = null,
        id: String? = null,
        desc: String? = null,
        clickable: Boolean = false
    ): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        this.className = className
        this.text = text
        this.viewIdResourceName = id
        this.contentDescription = desc
        this.isClickable = clickable
    }

    /** SDK 36 移除了 addChild(AccessibilityNodeInfo),通过 Shadow 建立父子关系 */
    private fun AccessibilityNodeInfo.addChildNode(child: AccessibilityNodeInfo) {
        shadowOf(this).addChild(child)
    }

    private fun rootOf(json: String): JSONObject =
        JSONObject(json).getJSONObject("root")

    // ========== 正常路径 ==========

    @Test
    fun `三层节点树导出包含关键字段与 children 层级`() {
        val root = node(className = "android.widget.FrameLayout")
        val mid = node(className = "android.widget.LinearLayout")
        val skip = node(text = "跳过", id = "com.demo:id/btn_skip", clickable = true)
        val adText = node(text = "跳过广告 5s", desc = "关闭广告")
        mid.addChildNode(skip)
        mid.addChildNode(adText)
        root.addChildNode(mid)

        val json = SnapshotExporter.toJson(root, "com.demo", "com.demo.SplashActivity")

        // 顶层元信息
        val document = JSONObject(json)
        assertEquals("com.demo", document.getString("packageName"))
        assertEquals("com.demo.SplashActivity", document.getString("className"))
        assertTrue("应带导出时间戳", document.getLong("exportedAt") > 0L)
        // 2 空格缩进（顶层键前有 2 个空格）
        assertTrue("应使用 2 空格缩进", json.contains("\n  \"packageName\""))

        // 第一层
        val rootObj = document.getJSONObject("root")
        assertEquals(1, rootObj.getInt("childCount"))
        assertEquals(0, rootObj.getInt("depth"))
        assertEquals(0, rootObj.getInt("index"))
        assertEquals("android.widget.FrameLayout", rootObj.getString("name"))

        // 第二层
        val midObj = rootObj.getJSONArray("children").getJSONObject(0)
        assertEquals("android.widget.LinearLayout", midObj.getString("name"))
        assertEquals(1, midObj.getInt("depth"))
        assertEquals(0, midObj.getInt("index"))
        assertEquals(2, midObj.getInt("childCount"))

        // 第三层
        val skipObj = midObj.getJSONArray("children").getJSONObject(0)
        assertEquals("跳过", skipObj.getString("text"))
        assertEquals("com.demo:id/btn_skip", skipObj.getString("id"))
        assertEquals("btn_skip", skipObj.getString("vid"))
        assertTrue("clickable 应为 true", skipObj.getBoolean("clickable"))
        assertEquals(2, skipObj.getInt("depth"))
        assertEquals(0, skipObj.getInt("index"))
        assertFalse("叶子节点不应有 children 键", skipObj.has("children"))

        val adObj = midObj.getJSONArray("children").getJSONObject(1)
        assertEquals("跳过广告 5s", adObj.getString("text"))
        assertEquals("关闭广告", adObj.getString("desc"))
        assertEquals(1, adObj.getInt("index"))
        assertFalse("未点击节点 clickable 应为 false", adObj.getBoolean("clickable"))
    }

    @Test
    fun `导出屏幕坐标与宽高`() {
        val root = node(className = "android.widget.FrameLayout").apply {
            setBoundsInScreen(Rect(10, 20, 110, 220))
        }

        val obj = rootOf(SnapshotExporter.toJson(root, "com.demo", "MainActivity"))

        assertEquals(10, obj.getInt("left"))
        assertEquals(20, obj.getInt("top"))
        assertEquals(110, obj.getInt("right"))
        assertEquals(220, obj.getInt("bottom"))
        assertEquals(100, obj.getInt("width"))
        assertEquals(200, obj.getInt("height"))
    }

    @Test
    fun `summarize 输出节点总数 最大深度 可点击与文本节点统计`() {
        val root = node(className = "android.widget.FrameLayout")
        val mid = node(className = "android.widget.LinearLayout")
        mid.addChildNode(node(text = "跳过", clickable = true))
        mid.addChildNode(node(text = "跳过广告"))
        root.addChildNode(mid)

        val summary = SnapshotExporter.summarize(root)

        assertTrue("摘要: $summary", summary.contains("节点总数=4"))
        assertTrue("摘要: $summary", summary.contains("最大深度=2"))
        assertTrue("摘要: $summary", summary.contains("可点击节点数=1"))
        assertTrue("摘要: $summary", summary.contains("文本节点数=2"))
        assertTrue("摘要: $summary", summary.contains("耗时="))
    }

    // ========== 边界条件 ==========

    @Test
    fun `单节点树不输出 children 键`() {
        val root = node(className = "android.widget.FrameLayout")

        val obj = rootOf(SnapshotExporter.toJson(root, "com.demo", "MainActivity"))

        assertFalse(obj.has("children"))
        assertEquals(0, obj.getInt("childCount"))
        assertEquals(0, obj.getInt("depth"))
        assertEquals(0, obj.getInt("index"))
    }

    @Test
    fun `空文本与空描述被省略`() {
        val root = node(className = "android.widget.FrameLayout", text = "", desc = null)

        val json = SnapshotExporter.toJson(root, "com.demo", "MainActivity")

        assertFalse("空文本应省略 text 键", json.contains("\"text\""))
        assertFalse("空描述应省略 desc 键", json.contains("\"desc\""))
        // 其余属性仍然输出
        assertTrue(json.contains("\"name\""))
        assertTrue(json.contains("\"clickable\""))
        assertTrue(json.contains("\"visibleToUser\""))
    }

    @Test
    fun `超深树在深度上限处截断`() {
        val root = node(className = "android.widget.FrameLayout")
        var current = root
        for (i in 1..SnapshotExporter.MAX_DEPTH + 5) {
            val child = node(text = "d$i")
            current.addChildNode(child)
            current = child
        }

        // 只遍历一次：遍历过程会回收 getChild 产生的子节点，重复遍历同一棵树不可靠
        val json = SnapshotExporter.toJson(root, "com.demo", "MainActivity")

        // 沿单链下钻，应在深度上限处停止展开
        var cursor = rootOf(json)
        var depth = 0
        while (cursor.has("children")) {
            cursor = cursor.getJSONArray("children").getJSONObject(0)
            depth++
        }
        assertEquals(SnapshotExporter.MAX_DEPTH, depth)
        assertEquals("d${SnapshotExporter.MAX_DEPTH}", cursor.getString("text"))
        assertEquals("截断节点仍应报告真实子节点数", 1, cursor.getInt("childCount"))
        assertFalse(
            "超过深度上限的节点不应出现在 JSON 中",
            json.contains("\"d${SnapshotExporter.MAX_DEPTH + 1}\"")
        )
    }

    // ========== 异常场景 ==========

    @Test
    fun `root 为 null 时 exportCurrentPage 返回 null`() {
        val context = RuntimeEnvironment.getApplication() as Context

        val path = SnapshotExporter.exportCurrentPage(
            context,
            null,
            "com.demo",
            "com.demo.MainActivity"
        )

        assertNull("根节点为空必须返回 null 而不是抛异常", path)
    }

    @Test
    fun `快照目录创建失败时返回 null 且不抛异常`() {
        val context = RuntimeEnvironment.getApplication() as Context
        // 用同名普通文件占位，令 mkdirs 失败
        File(context.filesDir, "snapshots").writeText("occupied")

        val path = SnapshotExporter.exportCurrentPage(context, node(), "com.demo", "MainActivity")

        assertNull("目录创建失败必须返回 null", path)
    }

    // ========== 落盘 ==========

    @Test
    fun `exportCurrentPage 写入 snapshots 目录并返回绝对路径`() {
        val context = RuntimeEnvironment.getApplication() as Context
        val root = node(className = "android.widget.FrameLayout")
        root.addChildNode(node(text = "跳过"))

        val path = SnapshotExporter.exportCurrentPage(
            context,
            root,
            "com.demo",
            "com.demo.SplashActivity"
        )

        assertNotNull("导出成功应返回路径", path)
        val file = File(path!!)
        assertTrue("快照文件应存在", file.exists())
        assertEquals(
            "快照应落在 filesDir/snapshots 下",
            File(context.filesDir, "snapshots").absolutePath,
            file.parentFile?.absolutePath
        )
        assertTrue("文件名应为时间戳", file.name.matches(Regex("\\d+\\.json")))
        val document = JSONObject(file.readText())
        assertEquals("com.demo", document.getString("packageName"))
        assertEquals("跳过", document.getJSONObject("root")
            .getJSONArray("children").getJSONObject(0).getString("text"))
    }
}
