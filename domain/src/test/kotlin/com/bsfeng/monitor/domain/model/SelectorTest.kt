package com.bsfeng.monitor.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorTest {

    @Test
    fun `SelectorChain requires nonempty`() {
        var thrown = false
        try {
            SelectorChain(emptyList())
        } catch (e: IllegalArgumentException) {
            thrown = true
        }
        assertTrue(thrown)
    }

    @Test
    fun `targetIndex default and marked`() {
        val parts = listOf(
            SelectorPart(AttrSegment(nameTag = "A")),
            SelectorPart(AttrSegment(nameTag = "B", isTarget = true)),
            SelectorPart(AttrSegment(nameTag = "C")),
        )
        val chain = SelectorChain(parts)
        assertEquals(1, chain.targetIndex)

        val defaultChain = SelectorChain(listOf(SelectorPart(AttrSegment(nameTag = "A"))))
        assertEquals(0, defaultChain.targetIndex)
    }

    @Test
    fun `enum properties`() {
        assertEquals(">", RelationOp.ANCESTOR.symbol)
        assertEquals("祖先", RelationOp.ANCESTOR.display)
        assertTrue(CompareOp.STARTS_WITH.isStringOp)
        assertFalse(CompareOp.EQ.isStringOp)
        assertTrue(CompareOp.GT.isOrderOp)
        assertFalse(CompareOp.EQ.isOrderOp)
    }

    @Test
    fun `RelationRange parse and contains`() {
        val any = RelationRange.parse("n")
        assertTrue(any.contains(1))
        assertTrue(any.contains(100))
        assertFalse(any.contains(0))

        val tuple = RelationRange.parse("2,3,5")
        assertTrue(tuple is RelationRange.Tuple)
        assertTrue(tuple.contains(3))
        assertFalse(tuple.contains(4))

        val c = RelationRange.parse("3")
        assertTrue(c.contains(3))
        assertFalse(c.contains(2))

        val c2 = RelationRange.parse("+3")
        assertTrue(c2.contains(3))

        val p = RelationRange.parse("2n-1")
        assertTrue(p.contains(1))
        assertTrue(p.contains(3))
        assertFalse(p.contains(2))

        val p2 = RelationRange.parse("-n+4")
        assertTrue(p2.contains(3))
        assertTrue(p2.contains(1))

        val arith = RelationRange.Arithmetic(2, 1)
        assertTrue(arith.contains(3))
        assertFalse(arith.contains(2))
        assertFalse(arith.contains(0))

        val arith0 = RelationRange.Arithmetic(0, 5)
        assertTrue(arith0.contains(5))
        assertFalse(arith0.contains(6))
    }

    @Test
    fun `RelationRange parse edge cases`() {
        assertTrue(RelationRange.parse("+n") is RelationRange.Arithmetic)
        var thrown = false
        try { RelationRange.parse(" ") } catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
        thrown = false
        try { RelationRange.parse("0,2") } catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
        thrown = false
        try { RelationRange.parse("-n") } catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
        thrown = false
        try { RelationRange.parse("-1") } catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
    }

    @Test
    fun `data class equality sealed expr`() {
        val a = LiteralValue(1)
        assertEquals(a, LiteralValue(1))
        assertEquals(IdentValue("x"), IdentValue("x"))
        assertEquals(MemberValue(IdentValue("t"), "length"), MemberValue(IdentValue("t"), "length"))
        val orExpr = SelectorOr(listOf(SelectorChain(listOf(SelectorPart(AttrSegment())))))
        assertEquals(orExpr, SelectorOr(listOf(SelectorChain(listOf(SelectorPart(AttrSegment()))))))
        val not = SelectorNot(SelectorChain(listOf(SelectorPart(AttrSegment()))))
        assertEquals(not, SelectorNot(SelectorChain(listOf(SelectorPart(AttrSegment())))))
        val andExpr = AndExpr(listOf(CompareExpr(IdentValue("a"), CompareOp.EQ, LiteralValue(1))))
        assertEquals(andExpr, andExpr.copy())
    }

    @Test
    fun `FastHint and AttrSegment defaults`() {
        val h = FastHint(FastAttr.ID, CompareOp.EQ, "v")
        assertEquals(FastAttr.ID, h.attr)
        val seg = AttrSegment()
        assertEquals(null, seg.nameTag)
        assertEquals(false, seg.isTarget)
        assertEquals(emptyList<FastHint>(), seg.fastHints)
    }

    @Test
    fun `BoolExpr classes`() {
        val n = NotExpr(CompareExpr(IdentValue("x"), CompareOp.GT, LiteralValue(1)))
        assertEquals(n, n.copy())
        val or = OrExpr(listOf(n))
        val and = AndExpr(listOf(n))
        assertEquals(1, or.items.size)
        assertEquals(1, and.items.size)
    }

    @Test
    fun `CallValue`() {
        val c = CallValue(null, "fn", listOf(LiteralValue(1)))
        val c2 = CallValue(IdentValue("o"), "m", emptyList())
        assertEquals("fn", c.name)
        assertEquals(null, c.target)
        assertEquals("m", c2.name)
    }

    @Test
    fun `Relation sealed classes`() {
        val r = Relation(RelationOp.CHILD, RelationRange.Any)
        assertEquals(RelationOp.CHILD, r.op)
        val part = SelectorPart(AttrSegment(), r)
        assertEquals(r, part.relation)
    }
}
