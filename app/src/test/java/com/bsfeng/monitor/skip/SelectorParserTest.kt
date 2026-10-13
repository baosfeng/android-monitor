package com.bsfeng.monitor.skip

import com.bsfeng.monitor.domain.model.AndExpr
import com.bsfeng.monitor.domain.model.AttrSegment
import com.bsfeng.monitor.domain.model.CallValue
import com.bsfeng.monitor.domain.model.CompareExpr
import com.bsfeng.monitor.domain.model.CompareOp
import com.bsfeng.monitor.domain.model.FastAttr
import com.bsfeng.monitor.domain.model.FastHint
import com.bsfeng.monitor.domain.model.IdentValue
import com.bsfeng.monitor.domain.model.LiteralValue
import com.bsfeng.monitor.domain.model.MemberValue
import com.bsfeng.monitor.domain.model.NotExpr
import com.bsfeng.monitor.domain.model.OrExpr
import com.bsfeng.monitor.domain.model.Relation
import com.bsfeng.monitor.domain.model.RelationOp
import com.bsfeng.monitor.domain.model.RelationRange
import com.bsfeng.monitor.domain.model.SelectorAnd
import com.bsfeng.monitor.domain.model.SelectorChain
import com.bsfeng.monitor.domain.model.SelectorNot
import com.bsfeng.monitor.domain.model.SelectorOr
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 选择器解析器测试（GKD 语义，纯 JVM 无需 Robolectric）。
 *
 * 覆盖：属性段（类名/通配/@/并列 []）、关系算子与关系表达式、属性布尔表达式
 * （14 个比较符、逻辑运算、成员/方法调用、字符串转义）、快速查询提示提取、异常路径。
 */
class SelectorParserTest {

    // ===== 辅助方法 =====

    private fun chainOf(selector: String): SelectorChain {
        val expr = SelectorParser.parse(selector)
        assertTrue("期望链式选择器，实际为: $expr", expr is SelectorChain)
        return expr as SelectorChain
    }

    private fun segmentOf(selector: String): AttrSegment {
        val parts = chainOf(selector).parts
        assertEquals("单段选择器应当只有一段: $selector", 1, parts.size)
        return parts[0].segment
    }

    private fun compareOf(selector: String): CompareExpr {
        val expr = segmentOf(selector).expr
        assertTrue("期望比较表达式，实际为: $expr", expr is CompareExpr)
        return expr as CompareExpr
    }

    private fun assertParseFails(selector: String) {
        assertNull("tryParse 应当容错返回 null: $selector", SelectorParser.tryParse(selector))
        assertThrows(IllegalArgumentException::class.java) { SelectorParser.parse(selector) }
    }

    // ===== 属性段：类名 / 通配 / @ =====

    @Test
    fun `单段类名简写等价于名称相等或名称后缀`() {
        val chain = chainOf("TextView")
        assertEquals(1, chain.parts.size)
        assertEquals(0, chain.targetIndex)
        assertNull(chain.parts[0].relation)
        val segment = chain.parts[0].segment
        assertEquals("TextView", segment.nameTag)
        assertFalse(segment.isTarget)
        assertTrue(segment.fastHints.isEmpty())
        assertEquals(
            OrExpr(
                listOf(
                    CompareExpr(IdentValue("name"), CompareOp.EQ, LiteralValue("TextView")),
                    CompareExpr(IdentValue("name"), CompareOp.ENDS_WITH, LiteralValue(".TextView"))
                )
            ),
            segment.expr
        )
    }

    @Test
    fun `全限定类名保留原名并追加后缀条件`() {
        val segment = segmentOf("android.widget.TextView")
        assertEquals("android.widget.TextView", segment.nameTag)
        val expr = segment.expr as OrExpr
        assertEquals(LiteralValue(".android.widget.TextView"), (expr.items[1] as CompareExpr).right)
    }

    @Test
    fun `星号通配任意类名但必须附带条件`() {
        val segment = segmentOf("*[clickable=true]")
        assertNull(segment.nameTag)
        assertEquals(CompareExpr(IdentValue("clickable"), CompareOp.EQ, LiteralValue(true)), segment.expr)
    }

    @Test
    fun `AT 标记单段为点击目标`() {
        val chain = chainOf("@TextView[vid=\"skip\"]")
        assertEquals(0, chain.targetIndex)
        assertTrue(chain.parts[0].segment.isTarget)
        assertEquals("TextView", chain.parts[0].segment.nameTag)
    }

    @Test
    fun `AT 标记中间段时目标下标指向该段`() {
        val chain = chainOf("FrameLayout > @TextView[text=\"跳过广告\"] > ImageView")
        assertEquals(3, chain.parts.size)
        assertEquals(1, chain.targetIndex)
        assertFalse(chain.parts[0].segment.isTarget)
        assertTrue(chain.parts[1].segment.isTarget)
        assertFalse(chain.parts[2].segment.isTarget)
    }

    // ===== 关系算子 =====

    @Test
    fun `大于号表示直接父级并可用数字限定代数`() {
        assertEquals(Relation(RelationOp.ANCESTOR, RelationRange.Tuple(setOf(1))), chainOf("FrameLayout > TextView").parts[1].relation)
        assertEquals(Relation(RelationOp.ANCESTOR, RelationRange.Tuple(setOf(2))), chainOf("FrameLayout >2 TextView").parts[1].relation)
        assertEquals(Relation(RelationOp.ANCESTOR, RelationRange.Any), chainOf("FrameLayout >n TextView").parts[1].relation)
    }

    @Test
    fun `小于号表示直接子级`() {
        assertEquals(Relation(RelationOp.CHILD, RelationRange.Tuple(setOf(1))), chainOf("FrameLayout < TextView").parts[1].relation)
        assertEquals(Relation(RelationOp.CHILD, RelationRange.Tuple(setOf(3))), chainOf("FrameLayout <3 TextView").parts[1].relation)
    }

    @Test
    fun `双小于表示子孙关系`() {
        assertEquals(Relation(RelationOp.DESCENDANT, RelationRange.Tuple(setOf(1))), chainOf("FrameLayout << TextView").parts[1].relation)
        assertEquals(Relation(RelationOp.DESCENDANT, RelationRange.Tuple(setOf(2))), chainOf("FrameLayout <<2 TextView").parts[1].relation)
        assertEquals(Relation(RelationOp.DESCENDANT, RelationRange.Any), chainOf("FrameLayout <<n TextView").parts[1].relation)
    }

    @Test
    fun `加号减号表示前置与后置兄弟`() {
        assertEquals(Relation(RelationOp.PRECEDING_SIBLING, RelationRange.Tuple(setOf(1))), chainOf("ImageView + TextView").parts[1].relation)
        assertEquals(Relation(RelationOp.PRECEDING_SIBLING, RelationRange.Tuple(setOf(3))), chainOf("ImageView +3 TextView").parts[1].relation)
        assertEquals(Relation(RelationOp.FOLLOWING_SIBLING, RelationRange.Tuple(setOf(1))), chainOf("ImageView - TextView").parts[1].relation)
    }

    @Test
    fun `空白表示任意祖先关系`() {
        assertEquals(Relation(RelationOp.ANCESTOR, RelationRange.Any), chainOf("FrameLayout TextView").parts[1].relation)
    }

    // ===== 关系表达式 =====

    @Test
    fun `关系表达式元组限定多个距离`() {
        val range = chainOf("FrameLayout >(1,2,3) TextView").parts[1].relation!!.range
        assertEquals(RelationRange.Tuple(setOf(1, 2, 3)), range)
        assertTrue(range.contains(2))
        assertFalse(range.contains(4))
    }

    @Test
    fun `关系表达式负多项式展开为有限集合`() {
        val range = chainOf("FrameLayout <<(-n+4) TextView").parts[1].relation!!.range
        assertTrue("(-n+4) 应解析为多项式: $range", range is RelationRange.Arithmetic)
        assertTrue(range.contains(1))
        assertTrue(range.contains(3))
        assertFalse(range.contains(4))
    }

    @Test
    fun `关系表达式括号 n 与多项式 n`() {
        assertEquals(RelationRange.Any, chainOf("FrameLayout <<(n) TextView").parts[1].relation!!.range)
        val arithmetic = chainOf("FrameLayout >(2n) TextView").parts[1].relation!!.range
        assertTrue(arithmetic.contains(2))
        assertTrue(arithmetic.contains(4))
        assertFalse(arithmetic.contains(3))
    }

    // ===== 属性布尔表达式 =====

    @Test
    fun `属性表达式支持全部十四个比较符`() {
        val cases = listOf(
            "=" to CompareOp.EQ,
            "!=" to CompareOp.NOT_EQ,
            ">" to CompareOp.GT,
            ">=" to CompareOp.GTE,
            "<" to CompareOp.LT,
            "<=" to CompareOp.LTE,
            "^=" to CompareOp.STARTS_WITH,
            "!^=" to CompareOp.NOT_STARTS_WITH,
            "*=" to CompareOp.CONTAINS,
            "!*=" to CompareOp.NOT_CONTAINS,
            "$=" to CompareOp.ENDS_WITH,
            "!$=" to CompareOp.NOT_ENDS_WITH,
            "~=" to CompareOp.MATCHES,
            "!~=" to CompareOp.NOT_MATCHES
        )
        assertEquals(14, cases.size)
        for ((symbol, expected) in cases) {
            val compare = compareOf("[text$symbol\"跳\"]")
            assertEquals("操作符 $symbol", expected, compare.op)
            assertEquals(IdentValue("text"), compare.left)
            assertEquals(LiteralValue("跳"), compare.right)
        }
    }

    @Test
    fun `属性表达式支持或运算与与运算且与优先`() {
        val or = segmentOf("[clickable=true||visibleToUser=false]").expr
        assertTrue(or is OrExpr)
        assertEquals(2, (or as OrExpr).items.size)

        val mixed = segmentOf("[clickable=true||text.length>1&&visibleToUser=true]").expr as OrExpr
        assertEquals(2, mixed.items.size)
        assertTrue("&& 优先级高于 ||", mixed.items[1] is AndExpr)
    }

    @Test
    fun `属性表达式支持括号与取反`() {
        val not = segmentOf("[!(vid=\"skip\")]").expr
        assertTrue(not is NotExpr)
        assertTrue((not as NotExpr).inner is CompareExpr)

        val grouped = segmentOf("[(clickable=true||enabled=true)&&visibleToUser=true]").expr as AndExpr
        assertEquals(2, grouped.items.size)
        assertTrue(grouped.items[0] is OrExpr)
    }

    @Test
    fun `并列属性段等价于与运算且提示取首个可加速条件`() {
        val segment = segmentOf("TextView[id=\"com.example:id/skip\"][clickable=true]")
        val expr = segment.expr as AndExpr
        assertEquals(2, expr.items.size)
        assertTrue("第一项为类名条件", expr.items[0] is OrExpr)
        val merged = expr.items[1] as AndExpr
        assertEquals(2, merged.items.size)
        assertEquals(
            CompareExpr(IdentValue("clickable"), CompareOp.EQ, LiteralValue(true)),
            merged.items[1]
        )
        assertEquals(listOf(FastHint(FastAttr.ID, CompareOp.EQ, "com.example:id/skip")), segment.fastHints)
    }

    @Test
    fun `成员表达式支持文本长度`() {
        val compare = compareOf("[text.length<10]")
        assertEquals(MemberValue(IdentValue("text"), "length"), compare.left)
        assertEquals(CompareOp.LT, compare.op)
        assertEquals(LiteralValue(10), compare.right)
        assertTrue(segmentOf("[text.length<10]").fastHints.isEmpty())
    }

    @Test
    fun `方法调用与成员链解析`() {
        val compare = compareOf("[parent.getChild(0).clickable=true]")
        assertEquals(
            MemberValue(CallValue(IdentValue("parent"), "getChild", listOf(LiteralValue(0))), "clickable"),
            compare.left
        )
        assertEquals(CompareOp.EQ, compare.op)
        assertEquals(LiteralValue(true), compare.right)
    }

    @Test
    fun `字符串支持单引号双引号与反引号`() {
        assertEquals(LiteralValue("跳过"), compareOf("[text='跳过']").right)
        assertEquals(LiteralValue("跳过"), compareOf("[text=\"跳过\"]").right)
        assertEquals(LiteralValue("跳过"), compareOf("[text=`跳过`]").right)
    }

    @Test
    fun `字符串支持转义与十六进制转义`() {
        assertEquals(LiteralValue("a\"b"), compareOf("[text=\"a\\\"b\"]").right)
        assertEquals(LiteralValue("a'b"), compareOf("[text='a\\'b']").right)
        assertEquals(LiteralValue("跳\n过"), compareOf("[text=\"跳\\n过\"]").right)
        assertEquals(LiteralValue("中"), compareOf("[text=\"\\u4e2d\"]").right)
    }

    // ===== 快速查询提示 =====

    @Test
    fun `视图 ID 与短 ID 产生快速查询提示`() {
        assertEquals(listOf(FastHint(FastAttr.VID, CompareOp.EQ, "skip")), segmentOf("[vid=\"skip\"]").fastHints)
        assertEquals(
            listOf(FastHint(FastAttr.ID, CompareOp.EQ, "com.example:id/skip")),
            segmentOf("[id=\"com.example:id/skip\"]").fastHints
        )
    }

    @Test
    fun `文本包含条件产生快速查询提示`() {
        assertEquals(listOf(FastHint(FastAttr.TEXT, CompareOp.CONTAINS, "跳过")), segmentOf("[text*=\"跳过\"]").fastHints)
        assertEquals(listOf(FastHint(FastAttr.TEXT, CompareOp.EQ, "跳过")), segmentOf("[text=\"跳过\"]").fastHints)
    }

    @Test
    fun `取反条件不产生快速查询提示`() {
        assertTrue(segmentOf("[!(vid=\"skip\")]").fastHints.isEmpty())
    }

    @Test
    fun `尺寸条件不产生快速查询提示`() {
        assertTrue(segmentOf("[width<500&&height<300]").fastHints.isEmpty())
        assertTrue(segmentOf("TextView[clickable=true]").fastHints.isEmpty())
    }

    @Test
    fun `或分支条件不齐时放弃快速查询提示`() {
        assertTrue(segmentOf("[clickable=true||vid=\"skip\"]").fastHints.isEmpty())
        assertEquals(
            listOf(
                FastHint(FastAttr.TEXT, CompareOp.EQ, "跳过"),
                FastHint(FastAttr.VID, CompareOp.EQ, "skip")
            ),
            segmentOf("[text=\"跳过\"||vid=\"skip\"]").fastHints
        )
    }

    // ===== 选择器级逻辑 =====

    @Test
    fun `选择器级支持或与与及取反`() {
        assertTrue(SelectorParser.parse("TextView||Button") is SelectorOr)
        assertTrue(SelectorParser.parse("FrameLayout&&TextView") is SelectorAnd)
        assertTrue(SelectorParser.parse("!(TextView > Button)") is SelectorNot)

        val and = SelectorParser.parse("FrameLayout&&TextView") as SelectorAnd
        assertTrue(and.items[0] is SelectorChain)
        assertTrue(and.items[1] is SelectorChain)
    }

    // ===== 异常路径 =====

    @Test
    fun `空选择器解析失败`() {
        assertParseFails("")
        assertParseFails("   ")
    }

    @Test
    fun `未闭合的引号与括号解析失败`() {
        assertParseFails("[text=\"跳过\"")
        assertParseFails("[!(vid=\"skip\"]")
        assertParseFails("[text='跳过]")
    }

    @Test
    fun `非法操作符与缺失操作数解析失败`() {
        assertParseFails("[text≈\"skip\"]")
        assertParseFails("[text]")
        assertParseFails("[=value]")
        assertParseFails("[text=\"skip\"&&]")
    }

    @Test
    fun `箭头算子与多余内容解析失败`() {
        assertParseFails("TextView -> Button")
        assertParseFails("TextView )")
        assertParseFails("[text=\"跳过\"] ]more")
        assertParseFails("*")
    }

    @Test
    fun `逻辑运算符两侧允许空白`() {
        // GKD 订阅规则普遍写成 "a || b" / "[width<500 && height<300]"，解析器必须容忍空白
        assertTrue(SelectorParser.parse("TextView || Button") is SelectorOr)
        assertTrue(SelectorParser.parse("TextView||Button") is SelectorOr)
        val segment = segmentOf("[clickable=true || enabled=true]")
        assertTrue(segment.expr is OrExpr)
        val andSegment = segmentOf("[clickable=true && enabled=true]")
        assertTrue(andSegment.expr is AndExpr)
    }
}
