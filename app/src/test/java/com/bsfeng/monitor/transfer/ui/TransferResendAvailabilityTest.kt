package com.bsfeng.monitor.transfer.ui

// P1-3 回归：s22 传送两处死路。
//
// 死路一：TransferSheets.kt 的 canResend 恒 false → C-17「重新发送」永不渲染，
//         连置灰态都没有。
// 死路二：TransferReceiptBar 没有 C-08 要求的「重试失败项」→ 失败项在本屏无任何出口。
//
// 根因（修复前）：TransferEngine 没有 resend 入口，源 Uri 只活在 sendFiles 的协程里，
// 落库时按 publishedFiles（只有接收侧写）取，于是发送任务 fileUri 恒 null。
// 修复：发送时登记 sourceFiles 并按方向落库，resendTask 按原 Uri 重发；
// 「重试」的可用性由 resendAvailabilityOf 这个纯函数决定（本用例锁住它）。
//
// 数据用占位值（设备名「Pixel 8」、文件名「IMG_0001.jpg」），不写真实设备信息。

import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.transfer.service.RESEND_BLOCK_PEER_OFFLINE
import com.bsfeng.monitor.transfer.service.RESEND_BLOCK_SOURCE_GONE
import com.bsfeng.monitor.transfer.service.ResendAvailability
import com.bsfeng.monitor.transfer.service.isResendReady
import com.bsfeng.monitor.transfer.service.resendAvailabilityOf
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferResendAvailabilityTest {

    @Test
    fun `发送方向终态且源可读对方在线时可重发`() {
        assertEquals(
            ResendAvailability.Ready,
            resendAvailabilityOf(
                direction = TransferDirection.SEND,
                isTerminal = true,
                hasReadableSource = true,
                peerOnline = true,
            ),
        )
    }

    @Test
    fun `接收方向不显示重发入口`() {
        // 契约 s22 C-17 边界逐字：「接收方向不显示本项」
        assertEquals(
            ResendAvailability.Hidden,
            resendAvailabilityOf(
                direction = TransferDirection.RECV,
                isTerminal = true,
                hasReadableSource = true,
                peerOnline = true,
            ),
        )
    }

    @Test
    fun `进行中任务不显示重发入口`() {
        assertEquals(
            ResendAvailability.Hidden,
            resendAvailabilityOf(
                direction = TransferDirection.SEND,
                isTerminal = false,
                hasReadableSource = true,
                peerOnline = true,
            ),
        )
    }

    @Test
    fun `源文件没了置灰并说明源文件已不在本机`() {
        val state = resendAvailabilityOf(
            direction = TransferDirection.SEND,
            isTerminal = true,
            hasReadableSource = false,
            peerOnline = true,
        )
        assertTrue("必须是置灰态而不是隐藏：用户要看到为什么不能点", state is ResendAvailability.Blocked)
        assertEquals(RESEND_BLOCK_SOURCE_GONE, (state as ResendAvailability.Blocked).reason)
        assertEquals("源文件已不在本机", RESEND_BLOCK_SOURCE_GONE)
    }

    @Test
    fun `对方不在线置灰并说明对方不在线`() {
        val state = resendAvailabilityOf(
            direction = TransferDirection.SEND,
            isTerminal = true,
            hasReadableSource = true,
            peerOnline = false,
        )
        assertEquals(RESEND_BLOCK_PEER_OFFLINE, (state as ResendAvailability.Blocked).reason)
        assertEquals("对方不在线", RESEND_BLOCK_PEER_OFFLINE)
    }

    @Test
    fun `源文件缺失优先于对方离线报出`() {
        // 文件没了，对方在不在线都没意义：先说更能指导用户动作的那条
        val state = resendAvailabilityOf(
            direction = TransferDirection.SEND,
            isTerminal = true,
            hasReadableSource = false,
            peerOnline = false,
        )
        assertEquals(RESEND_BLOCK_SOURCE_GONE, (state as ResendAvailability.Blocked).reason)
    }

    @Test
    fun `只有 Ready 才算可点重发`() {
        // C-17 渲染判据：Blocked 要渲染（置灰），但不是「可点」
        val hidden = resendAvailabilityOf(TransferDirection.RECV, true, true, true)
        val blocked = resendAvailabilityOf(TransferDirection.SEND, true, false, true)
        val ready = resendAvailabilityOf(TransferDirection.SEND, true, true, true)

        assertFalse("Hidden 不可重发", isResendReady(hidden))
        assertFalse("Blocked 不可重发（只渲染置灰态）", isResendReady(blocked))
        assertTrue("Ready 可重发", isResendReady(ready))
    }
}
