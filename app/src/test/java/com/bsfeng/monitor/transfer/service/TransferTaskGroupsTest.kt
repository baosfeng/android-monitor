package com.bsfeng.monitor.transfer.service

import com.bsfeng.monitor.domain.transfer.DeviceTypes
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferFile
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.domain.transfer.TransferTaskGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务分区 / 接收状态展示口径（纯 JVM）。
 *
 * 真机问题：超时失败的任务仍留在「进行中 · 1」。本用例钉住「终态一律进已完成、count 只算 active」。
 */
class TransferTaskGroupsTest {

    private fun task(status: TransferStatus, fileId: String = "f1"): TransferTask = TransferTask(
        sessionId = "s1",
        peer = PeerDevice("mac-1", "Mac", DeviceTypes.MAC, "192.168.1.2", 53319),
        direction = TransferDirection.RECV,
        file = TransferFile(fileId, "a.bin", 100L, "sha", "application/octet-stream"),
        status = status,
    )

    @Test
    fun `失败与完成的任务不属于进行中`() {
        val tasks = listOf(
            task(TransferStatus.TRANSFERRING, "f1"),
            task(TransferStatus.FAILED, "f2"),
            task(TransferStatus.COMPLETED, "f3"),
            task(TransferStatus.CANCELLED, "f4"),
            task(TransferStatus.DENIED, "f5"),
        )
        assertEquals(listOf("f1"), TransferTaskGroups.active(tasks).map { it.file.fileId })
        assertEquals(4, TransferTaskGroups.finished(tasks).size)
        assertEquals(1, TransferTaskGroups.activeCount(tasks))
    }

    @Test
    fun `超时失败后计数归零并显示空态标题`() {
        val failed = listOf(task(TransferStatus.FAILED))
        assertEquals(0, TransferTaskGroups.activeCount(failed))
        assertTrue(TransferTaskGroups.active(failed).isEmpty())
        assertEquals("进行中", TransferTaskGroups.activeTitle(failed))
        assertEquals("进行中 · 2", TransferTaskGroups.activeTitle(listOf(
            task(TransferStatus.TRANSFERRING, "f1"),
            task(TransferStatus.AWAITING_CONFIRM, "f2"),
        )))
    }

    @Test
    fun `接收状态只有一套真值源`() {
        val off = ReceivingState.of(running = false, port = 53319, onlineDevices = 0)
        assertFalse(off.running)
        assertTrue(off.canStart)
        assertEquals("未开启接收 · 点此开启", off.statusText)

        val on = ReceivingState.of(running = true, port = 53319, onlineDevices = 3)
        assertTrue(on.running)
        assertFalse(on.canStart)
        assertEquals("端口 53319 · 在线 3 台", on.statusText)

        val noDevice = ReceivingState.of(running = true, port = 53320, onlineDevices = 0)
        assertEquals("端口 53320 · 等待设备出现", noDevice.statusText)
    }
}
