package com.bsfeng.monitor.autobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「支付结果页稳定 1s 后再弹」门控测试（纯 JVM）。
 *
 * 核心场景来自真机：支付结果页会连续抛多个 CONTENT_CHANGED，若每个事件都重置 1s 计时，
 * 浮窗会被无限推迟；而 1s 内用户返回 / 切走 / 又付一笔时，也不能把上一笔弹出来。
 */
class AutoBookShowGateTest {

    @Test
    fun `首次识别需要新建延迟任务`() {
        val gate = AutoBookShowGate()
        assertTrue(gate.schedule("order:alipay:A"))
        assertTrue(gate.isPending)
        assertEquals("order:alipay:A", gate.pending)
    }

    @Test
    fun `同一笔重复事件不重置计时`() {
        val gate = AutoBookShowGate()
        assertTrue(gate.schedule("order:alipay:A"))
        // 支付结果页连续抛事件：返回 false 表示调用方不应重新 postDelayed
        assertFalse(gate.schedule("order:alipay:A"))
        assertEquals("order:alipay:A", gate.pending)
    }

    @Test
    fun `到点消费同一笔才允许弹窗`() {
        val gate = AutoBookShowGate()
        gate.schedule("order:alipay:A")
        assertTrue(gate.consume("order:alipay:A"))
        assertFalse("消费后队列必须清空，避免重复弹", gate.isPending)
    }

    @Test
    fun `到点发现换了一笔则不弹`() {
        val gate = AutoBookShowGate()
        gate.schedule("order:alipay:A")
        assertFalse("页面已经换成另一笔，不能把旧的那笔弹出来", gate.consume("order:alipay:B"))
        assertFalse(gate.isPending)
    }

    @Test
    fun `页面变化取消后到点也不弹`() {
        val gate = AutoBookShowGate()
        gate.schedule("order:alipay:A")
        gate.cancel()
        assertFalse(gate.isPending)
        assertFalse(gate.consume("order:alipay:A"))
    }

    @Test
    fun `重复到点不会二次弹窗`() {
        val gate = AutoBookShowGate()
        gate.schedule("order:alipay:A")
        assertTrue(gate.consume("order:alipay:A"))
        // 同一笔又抛一次事件 → 重新排队；但旧的到点回调再跑一次必须被挡住
        assertFalse(gate.consume("order:alipay:A"))
    }

    @Test
    fun `新的账单会替换队列里的旧账单`() {
        val gate = AutoBookShowGate()
        gate.schedule("order:alipay:A")
        assertEquals("order:alipay:A", gate.pending)
        assertTrue("不同账单需要重建延迟任务", gate.schedule("order:alipay:B"))
        assertFalse("旧账单到点时不能再弹", gate.consume("order:alipay:A"))
        assertNull(gate.pending)
    }

    @Test
    fun `从没排过队时到点什么都不做`() {
        val gate = AutoBookShowGate()
        assertFalse(gate.consume("order:alipay:A"))
    }
}
