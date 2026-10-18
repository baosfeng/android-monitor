package com.bsfeng.monitor.transfer.policy

import com.bsfeng.monitor.domain.transfer.CONFIRM_TIMEOUT_MS
import com.bsfeng.monitor.domain.transfer.DeviceTypes
import com.bsfeng.monitor.domain.transfer.NO_PROGRESS_TIMEOUT_MS
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferFile
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 发送侧超时判定的纯 JVM 测试（无 Robolectric）。
 *
 * 钉住真机 Bug 的收敛口径：发送任务在 `OFFERED`（已提交清单）与
 * `TRANSFERRING`（已在上传但没进展）两种卡死下都要能判出超时，
 * 且默认值与接收侧同一个常量（协议 §4.1：60s → 4007）。
 */
class SendTaskTimeoutPolicyTest {

    private val peer = PeerDevice(
        deviceId = "mac-1",
        alias = "Mac",
        deviceType = DeviceTypes.MAC,
        hostAddress = "192.168.1.5",
        port = 53319,
    )

    private fun task(
        status: TransferStatus,
        createdAtMs: Long,
        updatedAtMs: Long = createdAtMs,
        direction: TransferDirection = TransferDirection.SEND,
    ): TransferTask = TransferTask(
        sessionId = "s-1",
        peer = peer,
        direction = direction,
        file = TransferFile("f1", "mmexport.jpg", 2_700_000L, "sha", "image/jpeg"),
        status = status,
        receivedBytes = 0L,
        updatedAtMs = updatedAtMs,
        createdAtMs = createdAtMs,
    )

    private fun reason(
        task: TransferTask,
        nowMs: Long,
        confirm: Long = CONFIRM_TIMEOUT_MS,
        noProgress: Long = NO_PROGRESS_TIMEOUT_MS,
    ) = SendTaskTimeoutPolicy.reasonOf(task, nowMs, confirm, noProgress)

    @Test
    fun `已提交清单超过 60s 判对端未确认`() {
        val now = 1_000_000L
        val offered = task(TransferStatus.OFFERED, createdAtMs = now - CONFIRM_TIMEOUT_MS - 1L)
        assertEquals(SendStallReason.NOT_ACKNOWLEDGED, reason(offered, now))
        assertEquals("对端未确认", SendStallReason.NOT_ACKNOWLEDGED.message)
    }

    @Test
    fun `已提交清单未到 60s 不收敛`() {
        val now = 1_000_000L
        val offered = task(TransferStatus.OFFERED, createdAtMs = now - CONFIRM_TIMEOUT_MS + 1L)
        assertNull(reason(offered, now))
        assertFalse(SendTaskTimeoutPolicy.isStalled(offered, now, CONFIRM_TIMEOUT_MS, NO_PROGRESS_TIMEOUT_MS))
    }

    @Test
    fun `超时判定包含边界时刻（恰好 60s 就收敛）`() {
        val now = 1_000_000L
        val offered = task(TransferStatus.OFFERED, createdAtMs = now - CONFIRM_TIMEOUT_MS)
        assertEquals(SendStallReason.NOT_ACKNOWLEDGED, reason(offered, now))
    }

    @Test
    fun `传输中长时间无进展收敛`() {
        val now = 1_000_000L
        val transferring = task(
            TransferStatus.TRANSFERRING,
            createdAtMs = now - 5 * NO_PROGRESS_TIMEOUT_MS,
            updatedAtMs = now - NO_PROGRESS_TIMEOUT_MS - 1L,
        )
        assertEquals(SendStallReason.NO_PROGRESS, reason(transferring, now))
        assertEquals("长时间无进展", SendStallReason.NO_PROGRESS.message)
    }

    @Test
    fun `传输中刚有进度不收敛`() {
        val now = 1_000_000L
        val transferring = task(
            TransferStatus.TRANSFERRING,
            createdAtMs = now - 5 * NO_PROGRESS_TIMEOUT_MS,
            updatedAtMs = now - 1_000L,
        )
        assertNull(reason(transferring, now))
    }

    @Test
    fun `终态任务不收敛`() {
        val now = 1_000_000L
        val old = now - 10 * CONFIRM_TIMEOUT_MS
        listOf(
            TransferStatus.COMPLETED,
            TransferStatus.FAILED,
            TransferStatus.CANCELLED,
            TransferStatus.DENIED,
        ).forEach { status ->
            assertNull("$status 不应再被收敛", reason(task(status, createdAtMs = old, updatedAtMs = old), now))
        }
    }

    @Test
    fun `接收方向任务交给接收侧会话超时处理`() {
        val now = 1_000_000L
        val recv = task(
            TransferStatus.TRANSFERRING,
            createdAtMs = now - 10 * NO_PROGRESS_TIMEOUT_MS,
            updatedAtMs = now - 10 * NO_PROGRESS_TIMEOUT_MS,
            direction = TransferDirection.RECV,
        )
        assertNull(reason(recv, now))
    }

    @Test
    fun `时间戳缺失的历史脏数据不收敛`() {
        val now = 1_000_000L
        val noStamp = task(TransferStatus.OFFERED, createdAtMs = 0L, updatedAtMs = 0L)
        assertNull(reason(noStamp, now))
    }

    @Test
    fun `createdAt 缺失时用 updatedAt 兜底判 OFFERED`() {
        val now = 1_000_000L
        val offered = task(
            TransferStatus.OFFERED,
            createdAtMs = 0L,
            updatedAtMs = now - CONFIRM_TIMEOUT_MS - 1L,
        )
        assertEquals(SendStallReason.NOT_ACKNOWLEDGED, reason(offered, now))
    }

    @Test
    fun `剩余时间按同一口径计算`() {
        val now = 1_000_000L
        val offered = task(TransferStatus.OFFERED, createdAtMs = now - 20_000L)
        assertEquals(40_000L, SendTaskTimeoutPolicy.remainingMs(offered, now, CONFIRM_TIMEOUT_MS, NO_PROGRESS_TIMEOUT_MS))

        val expired = task(TransferStatus.OFFERED, createdAtMs = now - CONFIRM_TIMEOUT_MS - 5L)
        assertEquals(0L, SendTaskTimeoutPolicy.remainingMs(expired, now, CONFIRM_TIMEOUT_MS, NO_PROGRESS_TIMEOUT_MS))

        val transfer = task(TransferStatus.TRANSFERRING, createdAtMs = now - 90_000L, updatedAtMs = now - 10_000L)
        assertEquals(
            50_000L,
            SendTaskTimeoutPolicy.remainingMs(transfer, now, CONFIRM_TIMEOUT_MS, NO_PROGRESS_TIMEOUT_MS),
        )
    }

    @Test
    fun `可注入短超时（测试用小窗口同样生效）`() {
        val now = 1_000L
        val offered = task(TransferStatus.OFFERED, createdAtMs = 850L)
        assertNull(reason(offered, now, confirm = 200L))
        assertTrue(
            SendTaskTimeoutPolicy.isStalled(
                task = offered,
                nowMs = now + 51L,
                confirmTimeoutMs = 200L,
                noProgressTimeoutMs = 200L,
            ),
        )
    }
}
