package com.bsfeng.monitor.skip

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.domain.model.AttrSegment
import com.bsfeng.monitor.domain.model.SelectorChain
import com.bsfeng.monitor.domain.model.SelectorExpr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * NodeMatcher 匹配逻辑测试（Robolectric，GKD 语义）。
 *
 * 覆盖：属性段单节点匹配（类名/文本/ID/描述/可点击/可见/子节点数/宽高）、
 * 链式关系（父级/任意祖先/子孙/兄弟）、@ 目标节点、快速查询回退、深度截断与边界情况。
 *
 * 说明：原生快速查询（findAccessibilityNodeInfosByText/ByViewId）在 Robolectric 下不可用，
 * 未 seal 的节点调用会抛异常，正好用于验证引擎「查询失败回退遍历」的分支。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NodeMatcherTest {

    /** 4 层节点树：root(FrameLayout) 下 top(LinearLayout) 与 tail；top 下 title(TextView 跳过广告) 与 btn(Button 关闭)；title 下 sub */
    private class Tree(
        val root: AccessibilityNodeInfo,
        val top: AccessibilityNodeInfo,
        val title: AccessibilityNodeInfo,
        val sub: AccessibilityNodeInfo,
        val btn: AccessibilityNodeInfo,
        val tail: AccessibilityNodeInfo
    )

    // ===== 辅助方法 =====

    private fun node(
        text: String? = null,
        id: String? = null,
        className: String = "android.widget.TextView",
        desc: String? = null,
        clickable: Boolean = false,
        visible: Boolean = true,
        width: Int = 100,
        height: Int = 50
    ): AccessibilityNodeInfo = AccessibilityNodeInfo.obtain().apply {
        this.text = text
        this.viewIdResourceName = id
        this.className = className
        this.contentDescription = desc
        this.isClickable = clickable
        this.isVisibleToUser = visible
        setBoundsInScreen(Rect(0, 0, width, height))
    }

    /** SDK 36 移除了 addChild(AccessibilityNodeInfo)，通过 Shadow 建立父子关系 */
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
    private fun sel(selector: String): SelectorExpr = SelectorParser.parse(selector)

    /** 解析单段选择器并取出属性段（matchNode 用） */
    private fun seg(selector: String): AttrSegment =
        (SelectorParser.parse(selector) as SelectorChain).parts.single().segment

    private fun buildTree(): Tree {
        val root = node(className = "android.widget.FrameLayout")
        val top = node(className = "android.widget.LinearLayout", id = "com.example:id/top")
        val title = node(text = "跳过广告", id = "com.example:id/skip_title", desc = "跳过", clickable = true)
        val sub = node(text = "3", visible = false)
        val btn = node(text = "关闭", className = "android.widget.Button", clickable = true)
        val tail = node(text = "尾部")
        top.addChildNode(title)
        title.addChildNode(sub)
        top.addChildNode(btn)
        root.addChildNode(top)
        root.addChildNode(tail)
        root.packageName = "com.example"
        return Tree(root, top, title, sub, btn, tail)
    }

    // ===== 属性段单节点匹配 =====

    @Test
    fun `类名简写与全限定类名匹配`() {
        val tree = buildTree()
        assertTrue(NodeMatcher.matchNode(tree.title, seg("TextView")))
        assertFalse(NodeMatcher.matchNode(tree.btn, seg("TextView")))
        assertTrue(NodeMatcher.matchNode(tree.btn, seg("Button")))
        assertTrue(NodeMatcher.matchNode(tree.btn, seg("android.widget.Button")))
        assertFalse(NodeMatcher.matchNode(tree.btn, seg("android.widget.TextView")))
        assertTrue(NodeMatcher.matchNode(tree.title, seg("*[clickable=true]")))
    }

    @Test
    fun `文本与内容描述匹配`() {
        val tree = buildTree()
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[text=\"跳过广告\"]")))
        assertFalse(NodeMatcher.matchNode(tree.title, seg("[text=\"跳广告\"]")))
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[text!=\"关闭\"]")))
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[desc=\"跳过\"]")))
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[desc*=\"跳\"]")))
    }

    @Test
    fun `ID 与 VID 匹配`() {
        val tree = buildTree()
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[id=\"com.example:id/skip_title\"]")))
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[vid=\"skip_title\"]")))
        assertFalse(NodeMatcher.matchNode(tree.title, seg("[vid=\"other\"]")))
        assertFalse(NodeMatcher.matchNode(tree.tail, seg("[id=\"com.example:id/skip_title\"]")))
    }

    @Test
    fun `可点击与可见性匹配`() {
        val tree = buildTree()
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[clickable=true]")))
        assertFalse(NodeMatcher.matchNode(tree.sub, seg("[clickable=true]")))
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[visibleToUser=true]")))
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[visible=true]")))
        assertFalse(NodeMatcher.matchNode(tree.sub, seg("[visibleToUser=true]")))
        assertTrue(NodeMatcher.matchNode(tree.sub, seg("[visibleToUser=false]")))
    }

    @Test
    fun `子节点数量与宽高匹配`() {
        val tree = buildTree()
        assertTrue(NodeMatcher.matchNode(tree.top, seg("[childCount=2]")))
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[childCount=1]")))
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[width=100&&height=50]")))
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[width<200]")))
        assertFalse(NodeMatcher.matchNode(tree.title, seg("[width>500]")))
        assertFalse(NodeMatcher.matchNode(tree.title, seg("[height<10]")))
    }

    @Test
    fun `逻辑运算与成员表达式匹配`() {
        val tree = buildTree()
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[text.length=4]")))
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[text.length<10]")))
        assertFalse(NodeMatcher.matchNode(tree.title, seg("[text.length>10]")))
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[clickable=true&&text.length>2]")))
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[text.length=4||clickable=false]")))
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[!(clickable=false)]")))
        assertFalse(NodeMatcher.matchNode(tree.title, seg("[!(clickable=true)]")))
    }

    @Test
    fun `字符串操作符匹配且区分大小写`() {
        val tree = buildTree()
        val upper = node(text = "Skip")
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[text^=\"跳过\"]")))
        assertFalse(NodeMatcher.matchNode(tree.title, seg("[text^=\"广告\"]")))
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[text$=\"广告\"]")))
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[text*=\"过\"]")))
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[text~=\"^跳过.*$\"]")))
        assertTrue(NodeMatcher.matchNode(tree.title, seg("[text!*=\"关闭\"]")))
        assertFalse(NodeMatcher.matchNode(tree.title, seg("[text!^=\"跳过\"]")))
        assertTrue(NodeMatcher.matchNode(upper, seg("[text=\"Skip\"]")))
        assertFalse(NodeMatcher.matchNode(upper, seg("[text=\"skip\"]")))
    }

    @Test
    fun `属性为空时非等号比较返回假`() {
        val tree = buildTree()
        assertNull(tree.top.text)
        assertNull(tree.tail.contentDescription)
        assertFalse(NodeMatcher.matchNode(tree.top, seg("[text^=\"跳\"]")))
        assertFalse(NodeMatcher.matchNode(tree.top, seg("[text*=\"跳\"]")))
        assertTrue(NodeMatcher.matchNode(tree.top, seg("[text!=\"跳\"]")))
        assertFalse(NodeMatcher.matchNode(tree.tail, seg("[desc^=\"跳\"]")))
        assertTrue(NodeMatcher.matchNode(tree.tail, seg("[desc!=\"跳\"]")))
    }

    // ===== 链式关系（find） =====

    @Test
    fun `类名选择器查找到唯一目标节点`() {
        val tree = buildTree()
        val result = NodeMatcher.find(tree.root, sel("Button"))
        assertEquals(1, result.size)
        assertEquals("关闭", result[0].text.toString())
    }

    @Test
    fun `大于号只匹配直接父级`() {
        val tree = buildTree()
        val found = NodeMatcher.find(tree.root, sel("LinearLayout > TextView[text~=\"^跳过广告$\"]"))
        assertEquals(1, found.size)
        assertEquals("跳过广告", found[0].text.toString())

        val tooFar = NodeMatcher.find(tree.root, sel("FrameLayout > TextView[text~=\"^跳过广告$\"]"))
        assertTrue(tooFar.isEmpty())
    }

    @Test
    fun `空白关系匹配任意祖先`() {
        val tree = buildTree()
        val found = NodeMatcher.find(tree.root, sel("FrameLayout TextView[text~=\"^3$\"]"))
        assertEquals(1, found.size)
        assertEquals("3", found[0].text.toString())
    }

    @Test
    fun `子孙关系匹配深层后代`() {
        val tree = buildTree()
        val found = NodeMatcher.find(tree.root, sel("TextView[text~=\"^3$\"] <<n FrameLayout"))
        assertEquals(1, found.size)

        val notDescendant = NodeMatcher.find(tree.root, sel("TextView[text~=\"^尾部$\"] <<n LinearLayout"))
        assertTrue(notDescendant.isEmpty())
    }

    @Test
    fun `子孙关系按先序序号定位`() {
        val tree = buildTree()
        // root 的先序子孙序号：1 top、2 title、3 sub、4 btn、5 tail
        val second = NodeMatcher.find(tree.root, sel("TextView[text~=\"^跳过广告$\"] <<2 FrameLayout"))
        assertEquals(1, second.size)

        val fifth = NodeMatcher.find(tree.root, sel("TextView[text~=\"^尾部$\"] <<5 FrameLayout"))
        assertEquals(1, fifth.size)

        val mismatch = NodeMatcher.find(tree.root, sel("TextView[text~=\"^尾部$\"] <<2 FrameLayout"))
        assertTrue(mismatch.isEmpty())
    }

    @Test
    fun `小于号匹配直接子级`() {
        val tree = buildTree()
        val found = NodeMatcher.find(tree.root, sel("TextView[text~=\"^3$\"] < TextView[text~=\"^跳过广告$\"]"))
        assertEquals(1, found.size)
        assertEquals("跳过广告", found[0].text.toString())

        val notChild = NodeMatcher.find(tree.root, sel("TextView[text~=\"^3$\"] < LinearLayout"))
        assertTrue(notChild.isEmpty())
    }

    @Test
    fun `兄弟关系匹配前后置兄弟`() {
        val tree = buildTree()
        val preceding = NodeMatcher.find(tree.root, sel("TextView[text~=\"^跳过广告$\"] + Button"))
        assertEquals(1, preceding.size)
        assertEquals("关闭", preceding[0].text.toString())

        val following = NodeMatcher.find(tree.root, sel("Button - TextView[text~=\"^跳过广告$\"]"))
        assertEquals(1, following.size)
        assertEquals("跳过广告", following[0].text.toString())

        val none = NodeMatcher.find(tree.root, sel("Button + TextView[text~=\"^跳过广告$\"]"))
        assertTrue(none.isEmpty())
    }

    @Test
    fun `AT 标记决定返回的目标节点`() {
        val tree = buildTree()
        val defaultTarget = NodeMatcher.find(tree.root, sel("LinearLayout > TextView[text~=\"^跳过广告$\"]"))
        assertEquals(1, defaultTarget.size)
        assertEquals("跳过广告", defaultTarget[0].text.toString())

        val atTarget = NodeMatcher.find(tree.root, sel("@LinearLayout > TextView[text~=\"^跳过广告$\"]"))
        assertEquals(1, atTarget.size)
        assertEquals("android.widget.LinearLayout", atTarget[0].className.toString())
        assertNull(atTarget[0].text)
    }

    @Test
    fun `同层多个匹配全部返回`() {
        val tree = buildTree()
        val result = NodeMatcher.find(tree.root, sel("[clickable=true]"))
        assertEquals(2, result.size)
        val texts = result.map { it.text.toString() }.toSet()
        assertEquals(setOf("跳过广告", "关闭"), texts)
    }

    // ===== 快速查询与边界情况 =====

    @Test
    fun `未 seal 节点树的文本快速查询回退到遍历`() {
        val tree = buildTree()
        // 长度 >= 2 的中文文本会触发 findByText 快速查询，Robolectric 下抛异常后必须回退到遍历匹配
        val result = NodeMatcher.find(tree.root, sel("[text*=\"跳过\"]"))
        assertEquals(1, result.size)
        assertEquals("跳过广告", result[0].text.toString())
    }

    @Test
    fun `未 seal 节点树的 VID 快速查询回退到遍历`() {
        val tree = buildTree()
        val result = NodeMatcher.find(tree.root, sel("[vid=\"skip_title\"]"))
        assertEquals(1, result.size)
        assertEquals("跳过广告", result[0].text.toString())
    }

    @Test
    fun `seal 后的节点树遍历匹配仍然可用`() {
        val tree = buildTree()
        sealTree(tree.root)
        val result = NodeMatcher.find(tree.root, sel("Button[text~=\"^关闭$\"]"))
        assertEquals(1, result.size)
        assertEquals("关闭", result[0].text.toString())
    }

    @Test
    fun `根节点为空或无匹配时返回空列表`() {
        val tree = buildTree()
        assertTrue(NodeMatcher.find(null, sel("TextView")).isEmpty())
        assertTrue(NodeMatcher.find(tree.root, sel("Button[text~=\"^不存在$\"]")).isEmpty())
    }

    @Test
    fun `超深节点树在深度上限截断且不抛异常`() {
        val root = node(className = "android.widget.FrameLayout")
        var current = root
        repeat(70) { index ->
            val next = node(text = "层$index", clickable = index == 9 || index == 69)
            current.addChildNode(next)
            current = next
        }
        val result = NodeMatcher.find(root, sel("[clickable=true]"))
        assertEquals("仅深度上限内的节点被匹配", 1, result.size)
    }

    @Test
    fun `结果节点回收幂等`() {
        val tree = buildTree()
        val result = NodeMatcher.find(tree.root, sel("Button"))
        assertEquals(1, result.size)
        NodeMatcher.recycleAll(result)
        NodeMatcher.recycleAll(result)
    }
}