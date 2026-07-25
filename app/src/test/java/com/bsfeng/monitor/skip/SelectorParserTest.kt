package com.bsfeng.monitor.skip

import com.bsfeng.monitor.domain.model.AttrOp
import com.bsfeng.monitor.domain.model.Combinator
import org.junit.Assert.*
import org.junit.Test

/**
 * 选择器解析器单元测试。
 *
 * 覆盖：
 * - 标签选择器
 * - 属性选择器（各种操作符）
 * - 组合选择器
 * - 复杂选择器
 * - 边界情况
 */
class SelectorParserTest {

    // ===== 标签选择器 =====

    @Test
    fun `parse tag only`() {
        val sel = SelectorParser.parse("TextView")
        assertEquals("TextView", sel.tag)
        assertTrue(sel.attributes.isEmpty())
        assertTrue(sel.isLeaf)
    }

    @Test
    fun `parse tag with dots`() {
        val sel = SelectorParser.parse("android.widget.TextView")
        assertEquals("android.widget.TextView", sel.tag)
    }

    // ===== 属性选择器 =====

    @Test
    fun `parse exact attribute`() {
        val sel = SelectorParser.parse("[text=\"跳过\"]")
        assertNull(sel.tag)
        assertEquals(1, sel.attributes.size)
        val attr = sel.attributes[0]
        assertEquals("text", attr.name)
        assertEquals(AttrOp.EQ, attr.op)
        assertEquals("跳过", attr.value)
    }

    @Test
    fun `parse contains attribute`() {
        val sel = SelectorParser.parse("[text*=\"广告\"]")
        val attr = sel.attributes[0]
        assertEquals(AttrOp.CONTAINS, attr.op)
        assertEquals("广告", attr.value)
    }

    @Test
    fun `parse starts with attribute`() {
        val sel = SelectorParser.parse("[text^=\"跳过\"]")
        val attr = sel.attributes[0]
        assertEquals(AttrOp.STARTS_WITH, attr.op)
        assertEquals("跳过", attr.value)
    }

    @Test
    fun `parse ends with attribute`() {
        val sel = SelectorParser.parse("[text$=\"关闭\"]")
        val attr = sel.attributes[0]
        assertEquals(AttrOp.ENDS_WITH, attr.op)
        assertEquals("关闭", attr.value)
    }

    @Test
    fun `parse regex attribute`() {
        val sel = SelectorParser.parse("[text~=\"^(跳过|关闭).*\"]")
        val attr = sel.attributes[0]
        assertEquals(AttrOp.REGEX, attr.op)
        assertEquals("^(跳过|关闭).*", attr.value)
    }

    @Test
    fun `parse not equal attribute`() {
        val sel = SelectorParser.parse("[text!=\"确定\"]")
        val attr = sel.attributes[0]
        assertEquals(AttrOp.NOT_EQ, attr.op)
        assertEquals("确定", attr.value)
    }

    @Test
    fun `parse boolean attribute`() {
        val sel = SelectorParser.parse("[clickable=true]")
        val attr = sel.attributes[0]
        assertEquals("clickable", attr.name)
        assertEquals(AttrOp.EQ, attr.op)
        assertEquals("true", attr.value)
    }

    @Test
    fun `parse attribute with single quotes`() {
        val sel = SelectorParser.parse("[text='跳过']")
        val attr = sel.attributes[0]
        assertEquals("text", attr.name)
        assertEquals(AttrOp.EQ, attr.op)
        assertEquals("跳过", attr.value)
    }

    // ===== 标签 + 属性 =====

    @Test
    fun `parse tag with attribute`() {
        val sel = SelectorParser.parse("TextView[id=\"btn_skip\"]")
        assertEquals("TextView", sel.tag)
        assertEquals(1, sel.attributes.size)
        assertEquals("id", sel.attributes[0].name)
        assertEquals("btn_skip", sel.attributes[0].value)
    }

    @Test
    fun `parse tag with multiple attributes`() {
        val sel = SelectorParser.parse("TextView[id=\"btn_skip\"][text*=\"跳过\"]")
        assertEquals("TextView", sel.tag)
        assertEquals(2, sel.attributes.size)
        assertEquals("id", sel.attributes[0].name)
        assertEquals("text", sel.attributes[1].name)
    }

    // ===== 组合选择器 =====

    @Test
    fun `parse child combinator`() {
        val sel = SelectorParser.parse("FrameLayout > TextView[text=\"跳过\"]")
        assertEquals("FrameLayout", sel.tag)
        assertEquals(Combinator.CHILD, sel.combinator)
        assertNotNull(sel.right)
        assertEquals("TextView", sel.right!!.tag)
        assertEquals(1, sel.right!!.attributes.size)
    }

    @Test
    fun `parse descendant combinator`() {
        val sel = SelectorParser.parse("FrameLayout [text=\"跳过\"]")
        assertEquals("FrameLayout", sel.tag)
        assertEquals(Combinator.DESCENDANT, sel.combinator)
        assertNotNull(sel.right)
    }

    @Test
    fun `parse sibling combinator`() {
        val sel = SelectorParser.parse("[text*=\"广告\"] + [text*=\"关闭\"]")
        assertNull(sel.tag)
        assertEquals(Combinator.SIBLING, sel.combinator)
        assertNotNull(sel.right)
    }

    // ===== 复杂表达式 =====

    @Test
    fun `parse real world zhihu rule`() {
        val sel = SelectorParser.parse("TextView[id=\"com.zhihu.android:id/btn_skip\"]")
        assertEquals("TextView", sel.tag)
        assertEquals(1, sel.attributes.size)
        assertEquals("id", sel.attributes[0].name)
        assertEquals(AttrOp.EQ, sel.attributes[0].op)
        assertEquals("com.zhihu.android:id/btn_skip", sel.attributes[0].value)
    }

    @Test
    fun `parse real world weibo rule`() {
        val sel = SelectorParser.parse("TextView[id=\"com.sina.weibo:id/splash_skip_btn\"]")
        assertEquals("TextView", sel.tag)
        assertEquals("id", sel.attributes[0].name)
        assertEquals("com.sina.weibo:id/splash_skip_btn", sel.attributes[0].value)
    }

    @Test
    fun `parse global skip rule`() {
        val sel = SelectorParser.parse("[text*=\"跳过\"][text.length<10][visibleToUser=true]")
        assertNull(sel.tag)
        assertEquals(3, sel.attributes.size)
        assertEquals("text", sel.attributes[0].name)
        assertEquals(AttrOp.CONTAINS, sel.attributes[0].op)
        assertEquals("跳过", sel.attributes[0].value)
    }

    // ===== 边界情况 =====

    @Test(expected = IllegalArgumentException::class)
    fun `reject empty selector`() {
        SelectorParser.parse("")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reject blank selector`() {
        SelectorParser.parse("   ")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reject unclosed bracket`() {
        SelectorParser.parse("[text=\"跳过\"")
    }

    @Test(expected = IllegalArgumentException::class)
    fun `reject empty attribute name`() {
        SelectorParser.parse("[=\"value\"]")
    }
}
