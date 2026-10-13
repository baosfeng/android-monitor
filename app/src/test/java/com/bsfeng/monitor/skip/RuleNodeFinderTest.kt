package com.bsfeng.monitor.skip

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.domain.model.MatchRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * RuleNodeFinder 单元测试（Robolectric）。
 *
 * 覆盖：matches 全命中取最后一段、anyMatches 命中即用、面积守卫丢弃整屏容器、
 * 复核（matches 三态）与排除选择器。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RuleNodeFinderTest {

    private fun node(
        text: String? = null,
        clickable: Boolean = true,
        visible: Boolean = true,
        bounds: Rect = Rect(900, 100, 1100, 200)
    ): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        this.text = text
        this.className = "android.widget.TextView"
        this.isClickable = clickable
        this.isVisibleToUser = visible
        setBoundsInScreen(bounds)
    }

    private fun tree(children: List<AccessibilityNodeInfo>): AccessibilityNodeInfo {
        val root = node(text = null, clickable = false, bounds = Rect(0, 0, 1200, 2670))
        children.forEach { shadowOf(root).addChild(it) }
        return root
    }

    // ==================== 正常路径 ====================

    @Test
    fun `anyMatches 命中即返回该节点与选择器`() {
        val root = tree(listOf(node(text = "确定"), node(text = "跳过广告")))
        val target = RuleNodeFinder.findTarget(
            MatchRule(key = 0, anyMatches = listOf("[text=\"跳过广告\"]")),
            root
        )
        assertNotNull(target)
        assertEquals("[text=\"跳过广告\"]", target!!.selector)
        assertEquals("跳过广告", target.node.text.toString())
    }

    @Test
    fun `matches 全命中时点击最后一个选择器的目标节点`() {
        val root = tree(listOf(node(text = "开屏广告"), node(text = "跳过")))
        val target = RuleNodeFinder.findTarget(
            MatchRule(key = 0, matches = listOf("[text=\"开屏广告\"]", "[text=\"跳过\"]")),
            root
        )
        assertNotNull(target)
        assertEquals("[text=\"跳过\"]", target!!.selector)
    }

    @Test
    fun `复核：选择器命中返回 true、不命中返回 false`() {
        val root = tree(listOf(node(text = "跳过")))
        assertEquals(true, RuleNodeFinder.matches("[text=\"跳过\"]", true, root))
        assertEquals(false, RuleNodeFinder.matches("[text=\"确定\"]", true, root))
    }

    @Test
    fun `排除选择器命中时返回 true`() {
        val root = tree(listOf(node(text = "跳过")))
        assertTrue(
            RuleNodeFinder.anySelectorMatches(listOf("[text=\"跳过\"]"), true, root)
        )
        assertFalse(
            RuleNodeFinder.anySelectorMatches(listOf("[text=\"没有这个\"]"), true, root)
        )
    }

    // ==================== 边界条件 ====================

    @Test
    fun `matches 为空且 anyMatches 为空时不命中`() {
        val root = tree(listOf(node(text = "跳过")))
        assertNull(RuleNodeFinder.findTarget(MatchRule(key = 0), root))
    }

    @Test
    fun `matches 中有一个不命中则整体放弃`() {
        val root = tree(listOf(node(text = "跳过")))
        val target = RuleNodeFinder.findTarget(
            MatchRule(key = 0, matches = listOf("[text=\"跳过\"]", "[text=\"不存在\"]")),
            root
        )
        assertNull(target)
    }

    @Test
    fun `不可见节点不可用时会退而取第一个候选`() {
        val root = tree(listOf(node(text = "跳过", visible = false)))
        val target = RuleNodeFinder.findTarget(
            MatchRule(key = 0, anyMatches = listOf("[text=\"跳过\"]")),
            root
        )
        assertNotNull("无可见候选时应有兜底节点", target)
    }

    // ==================== 异常场景 ====================

    @Test
    fun `面积超过根节点三分之一的命中被丢弃`() {
        // 整屏广告容器：点中心会进落地页
        val root = tree(listOf(node(text = "跳过", bounds = Rect(0, 0, 1200, 2670))))
        assertNull(
            RuleNodeFinder.findTarget(MatchRule(key = 0, anyMatches = listOf("[text=\"跳过\"]")), root)
        )
    }

    @Test
    fun `选择器无法解析时复核返回 null 表示无法判断`() {
        val root = tree(listOf(node(text = "跳过")))
        assertNull(RuleNodeFinder.matches("[text=未闭合", true, root))
    }

    @Test
    fun `选择器无法解析时排除选择器按未命中处理`() {
        val root = tree(listOf(node(text = "跳过")))
        assertFalse(RuleNodeFinder.anySelectorMatches(listOf("[text=未闭合"), true, root))
    }
}
