package com.bsfeng.monitor.finance.ui

// 一级格子点击的展开口径自测（纯 JVM）。
//
// 契约 docs/交互设计/s15-自动记账浮窗-交互契约.md s15-l1-0…17 的边界写「点同一格收起面板；
// 同级只展开一个，展开新的会收起旧的」。改前恒 expanded = true，点同一格收不起面板（本批 P1）。

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CategoryPickerTopLevelTapTest {

    @Test
    fun `点同一格收起面板`() {
        val next = topLevelTap(current = "餐饮", tapped = "餐饮", expanded = true)
        assertEquals("餐饮", next.activeTopName)
        assertFalse("点已展开的同一格必须收起", next.expanded)
    }

    @Test
    fun `收起后再点同一格重新展开`() {
        val next = topLevelTap(current = "餐饮", tapped = "餐饮", expanded = false)
        assertEquals("餐饮", next.activeTopName)
        assertTrue(next.expanded)
    }

    @Test
    fun `点新的一级切过去并展开`() {
        val next = topLevelTap(current = "餐饮", tapped = "购物", expanded = true)
        assertEquals("购物", next.activeTopName)
        assertTrue("换成新的一级时必须展开", next.expanded)
    }

    @Test
    fun `面板收起时点新的一级也展开`() {
        val next = topLevelTap(current = "餐饮", tapped = "交通", expanded = false)
        assertEquals("交通", next.activeTopName)
        assertTrue(next.expanded)
    }

    @Test
    fun `收起只改展开态不改当前一级`() {
        val collapsed = topLevelTap(current = "购物", tapped = "购物", expanded = true)
        val expandedAgain = topLevelTap(collapsed.activeTopName, "购物", collapsed.expanded)
        assertEquals("购物", collapsed.activeTopName)
        assertEquals("购物", expandedAgain.activeTopName)
        assertTrue(expandedAgain.expanded)
    }
}
