package com.bsfeng.monitor.domain.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferSessionMachineTest {

    @Test
    fun `happy path transfer`() {
        val m = TransferSessionMachine("sid", 10L)
        assertEquals(TransferStatus.IDLE, m.status)
        assertEquals(0L, m.receivedBytes)
        assertTrue(m.history().size == 1 && m.history().first() == TransferStatus.IDLE)

        var r = m.transition(TransferEvent.Offer)
        assertTrue(r is TransferTransition.Applied)
        assertEquals(TransferStatus.OFFERED, m.status)

        m.transition(TransferEvent.Pending)
        assertEquals(TransferStatus.AWAITING_CONFIRM, m.status)

        val a = m.transition(TransferEvent.Approve(0L))
        assertTrue(a is TransferTransition.Applied)
        assertEquals(TransferStatus.TRANSFERRING, m.status)

        val pr = m.transition(TransferEvent.Progress(5L))
        assertTrue(pr is TransferTransition.Applied)
        assertEquals(5L, m.receivedBytes)
        assertEquals(TransferStatus.TRANSFERRING, m.status)

        val back = m.transition(TransferEvent.Progress(4L))
        assertTrue(back is TransferTransition.Rejected)
        assertEquals(5L, m.receivedBytes)

        val over = m.transition(TransferEvent.Progress(11L))
        assertTrue(over is TransferTransition.Rejected)
        assertEquals(5L, m.receivedBytes)

        val early = m.transition(TransferEvent.Complete)
        assertTrue(early is TransferTransition.Rejected)

        m.transition(TransferEvent.Progress(10L))
        assertEquals(10L, m.receivedBytes)
        val done = m.transition(TransferEvent.Complete)
        assertTrue(done is TransferTransition.Applied)
        assertEquals(TransferStatus.COMPLETED, m.status)

        val reset = m.transition(TransferEvent.Reset)
        assertTrue(reset is TransferTransition.Applied)
        assertEquals(TransferStatus.IDLE, m.status)
        assertEquals(0L, m.receivedBytes)
        assertNull(m.errorCode)
    }

    @Test
    fun `approve offset mismatch reject`() {
        val m = TransferSessionMachine("sid", 10L)
        m.transition(TransferEvent.Offer)
        val a = m.transition(TransferEvent.Approve(5L))
        assertTrue(a is TransferTransition.Rejected)
        assertEquals(TransferStatus.OFFERED, m.status)
    }

    @Test
    fun `invalid transitions do not mutate`() {
        val m = TransferSessionMachine("sid", 10L)
        m.transition(TransferEvent.Offer)
        val beforeStatus = m.status
        val beforeReceived = m.receivedBytes
        val rej = m.transition(TransferEvent.Offer)
        assertTrue(rej is TransferTransition.Rejected)
        assertEquals(beforeStatus, m.status)
        assertEquals(beforeReceived, m.receivedBytes)
    }

    @Test
    fun `deny and cancel rules`() {
        val m1 = TransferSessionMachine("sid1", 10L)
        m1.transition(TransferEvent.Offer)
        val d = m1.transition(TransferEvent.Deny)
        assertTrue(d is TransferTransition.Applied)
        assertEquals(TransferStatus.DENIED, m1.status)
        assertEquals(TransferErrorCode.USER_DENIED, m1.errorCode)

        val m2 = TransferSessionMachine("sid2", 10L)
        m2.transition(TransferEvent.Offer)
        val c = m2.transition(TransferEvent.Cancel)
        assertTrue(c is TransferTransition.Applied)
        assertEquals(TransferStatus.CANCELLED, m2.status)

        val m3 = TransferSessionMachine("sid3", 10L)
        val c2 = m3.transition(TransferEvent.Cancel)
        assertTrue(c2 is TransferTransition.Rejected)

        val r = m3.transition(TransferEvent.Reset)
        assertTrue(r is TransferTransition.Rejected)
    }

    @Test
    fun `fail terminal rules`() {
        val m = TransferSessionMachine("sid", 10L)
        m.transition(TransferEvent.Offer)
        val f = m.transition(TransferEvent.Fail(TransferErrorCode.DISK_FULL))
        assertTrue(f is TransferTransition.Applied)
        assertEquals(TransferStatus.FAILED, m.status)
        assertEquals(TransferErrorCode.DISK_FULL, m.errorCode)
        val f2 = m.transition(TransferEvent.Fail())
        assertTrue(f2 is TransferTransition.Rejected)
        val c = m.transition(TransferEvent.Cancel)
        assertTrue(c is TransferTransition.Rejected)
    }

    @Test
    fun `deny only offered or awaiting`() {
        val m = TransferSessionMachine("sid", 10L)
        val d = m.transition(TransferEvent.Deny)
        assertTrue(d is TransferTransition.Rejected)
    }

    @Test
    fun `terminal resets received bytes and code`() {
        val m = TransferSessionMachine("sid", 10L)
        m.transition(TransferEvent.Offer)
        m.transition(TransferEvent.Approve(0L))
        m.transition(TransferEvent.Progress(3L))
        m.transition(TransferEvent.Fail(TransferErrorCode.SHA256_MISMATCH))
        assertEquals(3L, m.receivedBytes)
        m.transition(TransferEvent.Reset)
        assertEquals(0L, m.receivedBytes)
        assertNull(m.errorCode)
        assertEquals(TransferStatus.IDLE, m.status)
    }

    @Test
    fun `initial status and history`() {
        val m = TransferSessionMachine("sid", 0L, TransferStatus.TRANSFERRING)
        assertEquals(TransferStatus.TRANSFERRING, m.status)
        assertEquals(listOf(TransferStatus.TRANSFERRING), m.history())
        m.transition(TransferEvent.Complete)
        assertEquals(TransferStatus.COMPLETED, m.status)
    }

    @Test
    fun `invalid constructor`() {
        var thrown = false
        try { TransferSessionMachine(" ", 0L) } catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
        thrown = false
        try { TransferSessionMachine("sid", -1L) } catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
    }
}
