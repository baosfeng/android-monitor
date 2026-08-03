package com.bsfeng.monitor.skip

import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.domain.model.Selector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * NodeMatcher 匹配逻辑测试（Robolectric）。
 *
 * 覆盖：文本/ID 查找、标签匹配、属性操作符、组合器。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NodeMatcherTest {

    private fun node(
        text: String? = null,
        id: String? = null,
        className: String = "android.widget.TextView",
        desc: String? = null
    ): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        this.text = text
        this.viewIdResourceName = id
        this.className = className
        this.contentDescription = desc
    }

    /** SDK 36 移除了 addChild(AccessibilityNodeInfo),通过 Shadow 建立父子关系 */
    private fun AccessibilityNodeInfo.addChildNode(child: AccessibilityNodeInfo) {
        shadowOf(this).addChild(child)
    }

    /** 原生 API(findByText/ByViewId)要求节点已 seal;Robolectric 提供 setSealed 测试辅助 */
    private fun seal(node: AccessibilityNodeInfo) {
        try {
            AccessibilityNodeInfo::class.java
                .getMethod("setSealed", Boolean::class.javaPrimitiveType)
                .invoke(node, true)
        } catch (_: Exception) {
            // Robolectric 不支持时静默跳过
        }
    }

    private fun sealTree(node: AccessibilityNodeInfo) {
        seal(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { sealTree(it) }
        }
    }

    /** 用 SelectorParser 解析表达式（与生产代码同一入口） */
    private fun sel(expr: String): Selector = SelectorParser.parse(expr)

    @Test
    fun `文本精确匹配找到节点`() {
        val root = node()
        val child = node(text = "跳")
        root.addChildNode(child)
        sealTree(root)

        // 单字符值避开原生 API 加速路径(Robolectric shadow 不支持 findByText),走遍历匹配
        val result = NodeMatcher.find(root, sel("[text=\"跳\"]"))
        assertEquals(1, result.size)
        assertEquals("跳", result.first().text.toString())
    }

    @Test
    fun `文本不匹配返回空列表`() {
        val root = node()
        val child = node(text = "确定")
        root.addChildNode(child)
        sealTree(root)

        val result = NodeMatcher.find(root, sel("[text=\"跳\"]"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `ID 精确匹配找到节点`() {
        val root = node()
        val child = node(text = "跳过", id = "com.example:id/btn_skip")
        root.addChildNode(child)
        sealTree(root)

        // 用 vid 属性(非 id)避开原生 API 加速路径(Robolectric shadow 不支持 findByViewId)
        val result = NodeMatcher.find(root, sel("TextView[vid=\"btn_skip\"]"))
        assertEquals(1, result.size)
    }

    @Test
    fun `标签简写匹配`() {
        val target = node(text = "跳过")
        assertTrue(NodeMatcher.matchNode(target, sel("TextView")))
    }

    @Test
    fun `标签不匹配`() {
        val target = node(text = "跳过", className = "android.widget.Button")
        assertFalse(NodeMatcher.matchNode(target, sel("TextView")))
    }

    @Test
    fun `CONTAINS 操作符匹配`() {
        val target = node(text = "跳过广告")
        assertTrue(NodeMatcher.matchNode(target, sel("[text*=\"广告\"]")))
    }

    @Test
    fun `STARTS_WITH 操作符匹配`() {
        val target = node(text = "跳过广告")
        assertTrue(NodeMatcher.matchNode(target, sel("[text^=\"跳过\"]")))
        assertFalse(NodeMatcher.matchNode(target, sel("[text^=\"广告\"]")))
    }

    @Test
    fun `ENDS_WITH 操作符匹配`() {
        val target = node(text = "跳过广告")
        assertTrue(NodeMatcher.matchNode(target, sel("[text$=\"广告\"]")))
        assertFalse(NodeMatcher.matchNode(target, sel("[text$=\"跳过\"]")))
    }

    @Test
    fun `NOT_EQ 操作符匹配`() {
        val target = node(text = "确定")
        assertTrue(NodeMatcher.matchNode(target, sel("[text!=\"跳过\"]")))
        assertFalse(NodeMatcher.matchNode(target, sel("[text!=\"确定\"]")))
    }

    @Test
    fun `REGEX 操作符匹配`() {
        val target = node(text = "跳过广告")
        assertTrue(NodeMatcher.matchNode(target, sel("[text~=\"^跳过.*\"]")))
        assertFalse(NodeMatcher.matchNode(target, sel("[text~=\"^广告.*\"]")))
    }

    @Test
    fun `desc 属性匹配`() {
        val target = node(desc = "关闭广告")
        assertTrue(NodeMatcher.matchNode(target, sel("[desc*=\"广告\"]")))
    }

    @Test
    fun `CHILD 组合器匹配直接子节点`() {
        val root = node(className = "android.widget.FrameLayout")
        val child = node(text = "跳过")
        root.addChildNode(child)
        sealTree(root)

        val result = NodeMatcher.find(root, sel("FrameLayout > TextView[text=\"跳过\"]"))
        assertEquals(1, result.size)
    }

    @Test
    fun `CHILD 组合器不匹配孙子节点`() {
        val root = node(className = "android.widget.FrameLayout")
        val mid = node(className = "android.widget.LinearLayout")
        val grandChild = node(text = "跳过")
        mid.addChildNode(grandChild)
        root.addChildNode(mid)
        sealTree(root)

        val result = NodeMatcher.find(root, sel("FrameLayout > TextView[text=\"跳过\"]"))
        assertTrue(result.isEmpty())
    }

    @Test
    fun `DESCENDANT 组合器匹配任意后代`() {
        val root = node(className = "android.widget.FrameLayout")
        val mid = node(className = "android.widget.LinearLayout")
        val grandChild = node(text = "跳")
        mid.addChildNode(grandChild)
        root.addChildNode(mid)
        sealTree(root)

        // 单字符文本避开原生 API 加速路径,确保组合器走遍历匹配
        val result = NodeMatcher.find(root, sel("FrameLayout [text=\"跳\"]"))
        assertEquals(1, result.size)
    }

    @Test
    fun `组合器匹配多个同级节点`() {
        val root = node(className = "android.widget.FrameLayout")
        root.addChildNode(node(text = "跳过"))
        root.addChildNode(node(text = "跳过"))
        sealTree(root)

        val result = NodeMatcher.find(root, sel("FrameLayout > TextView[text=\"跳过\"]"))
        assertEquals(2, result.size)
    }

    @Test
    fun `空根节点返回空列表`() {
        val result = NodeMatcher.find(null, sel("TextView"))
        assertTrue(result.isEmpty())
    }
}
