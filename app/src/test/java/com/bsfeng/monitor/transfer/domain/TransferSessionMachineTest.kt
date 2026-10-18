package com.bsfeng.monitor.transfer.domain

import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import com.bsfeng.monitor.domain.transfer.TransferEvent
import com.bsfeng.monitor.domain.transfer.TransferSessionMachine
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTransition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [TransferSessionMachine] 状态机测试（纯 JVM）。
 *
 * 重点：**非法迁移必须被拒绝且不改状态**（协议 §4 的状态语义 + §4.1 错误码），
 * 以及进度单调 / 完成必须收满 / 终态只能 Reset 三条硬约束。
 */
class TransferSessionMachineTest {

    private fun machine(total: Long = 100L) = TransferSessionMachine("s1", total)

    @Test
    fun `正常路径 提交清单 待确认 通过 完成`() {
        val m = machine()
        assertEquals(TransferStatus.IDLE, m.status)
        assertTrue(m.transition(TransferEvent.Offer) is TransferTransition.Applied)
        assertEquals(TransferStatus.OFFERED, m.status)
        assertTrue(m.transition(TransferEvent.Pending) is TransferTransition.Applied)
        assertEquals(TransferStatus.AWAITING_CONFIRM, m.status)
        assertTrue(m.transition(TransferEvent.Approve(0L)) is TransferTransition.Applied)
        assertEquals(TransferStatus.TRANSFERRING, m.status)
        assertTrue(m.transition(TransferEvent.Progress(60L)) is TransferTransition.Applied)
        assertEquals(60L, m.receivedBytes)
        assertTrue(m.transition(TransferEvent.Progress(100L)) is TransferTransition.Applied)
        assertTrue(m.transition(TransferEvent.Complete) is TransferTransition.Applied)
        assertEquals(TransferStatus.COMPLETED, m.status)
        assertEquals(
            listOf(
                TransferStatus.IDLE,
                TransferStatus.OFFERED,
                TransferStatus.AWAITING_CONFIRM,
                TransferStatus.TRANSFERRING,
                TransferStatus.COMPLETED,
            ),
            m.history(),
        )
    }

    @Test
    fun `已配对时可直接从 OFFERED 通过`() {
        val m = machine()
        m.transition(TransferEvent.Offer)
        val result = m.transition(TransferEvent.Approve(0L))
        assertTrue(result is TransferTransition.Applied)
        assertEquals(TransferStatus.TRANSFERRING, m.status)
    }

    @Test
    fun `非法迁移被拒绝且状态不变`() {
        val m = machine()
        val rejected = m.transition(TransferEvent.Complete)
        assertTrue(rejected is TransferTransition.Rejected)
        assertEquals(TransferStatus.IDLE, m.status)
        assertEquals(0L, m.receivedBytes)
        assertTrue((rejected as TransferTransition.Rejected).reason.contains("TRANSFERRING"))
    }

    @Test
    fun `跳步迁移被拒绝 直接完成`() {
        val m = machine()
        m.transition(TransferEvent.Offer)
        m.transition(TransferEvent.Approve(0L))
        // 未推进度就直接 Complete：总量已知时必须先收满
        val result = m.transition(TransferEvent.Complete)
        assertTrue(result is TransferTransition.Rejected)
        assertEquals(TransferStatus.TRANSFERRING, m.status)
    }

    @Test
    fun `续传偏移不匹配被拒绝`() {
        val m = machine()
        m.transition(TransferEvent.Offer)
        m.transition(TransferEvent.Approve(0L))
        m.transition(TransferEvent.Progress(40L))
        val rejected = m.transition(TransferEvent.Approve(10L))
        assertTrue(rejected is TransferTransition.Rejected)
        assertEquals(40L, m.receivedBytes)
    }

    @Test
    fun `进度回退被拒绝`() {
        val m = machine()
        m.transition(TransferEvent.Offer)
        m.transition(TransferEvent.Approve(0L))
        m.transition(TransferEvent.Progress(50L))
        val rejected = m.transition(TransferEvent.Progress(49L))
        assertTrue(rejected is TransferTransition.Rejected)
        assertEquals(50L, m.receivedBytes)
    }

    @Test
    fun `进度越界被拒绝`() {
        val m = machine(total = 100L)
        m.transition(TransferEvent.Offer)
        m.transition(TransferEvent.Approve(0L))
        val rejected = m.transition(TransferEvent.Progress(101L))
        assertTrue(rejected is TransferTransition.Rejected)
        assertEquals(0L, m.receivedBytes)
    }

    @Test
    fun `进度相同幂等不报错`() {
        val m = machine()
        m.transition(TransferEvent.Offer)
        m.transition(TransferEvent.Approve(0L))
        m.transition(TransferEvent.Progress(30L))
        val again = m.transition(TransferEvent.Progress(30L))
        assertTrue(again is TransferTransition.Applied)
        assertEquals(30L, m.receivedBytes)
    }

    @Test
    fun `用户拒绝只能从待确认或已提交`() {
        val m = machine()
        m.transition(TransferEvent.Offer)
        val denied = m.transition(TransferEvent.Deny)
        assertTrue(denied is TransferTransition.Applied)
        assertEquals(TransferStatus.DENIED, m.status)
        assertEquals(TransferErrorCode.USER_DENIED, m.errorCode)
        // 终态再拒绝：非法
        assertTrue(m.transition(TransferEvent.Deny) is TransferTransition.Rejected)
    }

    @Test
    fun `IDLE 不能取消`() {
        val m = machine()
        assertTrue(m.transition(TransferEvent.Cancel) is TransferTransition.Rejected)
        assertEquals(TransferStatus.IDLE, m.status)
    }

    @Test
    fun `传输中可以取消 终态不能再失败`() {
        val m = machine()
        m.transition(TransferEvent.Offer)
        m.transition(TransferEvent.Approve(0L))
        assertTrue(m.transition(TransferEvent.Cancel) is TransferTransition.Applied)
        assertEquals(TransferStatus.CANCELLED, m.status)
        assertTrue(m.transition(TransferEvent.Fail(4005)) is TransferTransition.Rejected)
    }

    @Test
    fun `失败记录错误码`() {
        val m = machine()
        m.transition(TransferEvent.Offer)
        m.transition(TransferEvent.Approve(0L))
        m.transition(TransferEvent.Fail(TransferErrorCode.SHA256_MISMATCH))
        assertEquals(TransferStatus.FAILED, m.status)
        assertEquals(TransferErrorCode.SHA256_MISMATCH, m.errorCode)
    }

    @Test
    fun `只有终态能 reset 且清空进度与错误码`() {
        val m = machine()
        m.transition(TransferEvent.Offer)
        m.transition(TransferEvent.Approve(0L))
        m.transition(TransferEvent.Progress(10L))
        assertTrue(m.transition(TransferEvent.Reset) is TransferTransition.Rejected)
        m.transition(TransferEvent.Fail(4002))
        assertTrue(m.transition(TransferEvent.Reset) is TransferTransition.Applied)
        assertEquals(TransferStatus.IDLE, m.status)
        assertEquals(0L, m.receivedBytes)
        assertNull(m.errorCode)
    }

    @Test
    fun `总量未知时完成不校验收满`() {
        val m = TransferSessionMachine("s2", 0L)
        m.transition(TransferEvent.Offer)
        m.transition(TransferEvent.Approve(0L))
        assertTrue(m.transition(TransferEvent.Complete) is TransferTransition.Applied)
        assertEquals(TransferStatus.COMPLETED, m.status)
    }

    @Test
    fun `非法构造参数抛异常`() {
        assertTrue(
            runCatching { TransferSessionMachine("", 1L) }.exceptionOrNull() is IllegalArgumentException,
        )
        assertTrue(
            runCatching { TransferSessionMachine("s1", -1L) }.exceptionOrNull() is IllegalArgumentException,
        )
    }
}
