package com.bsfeng.monitor.transfer.ui

// C-16「清空已结束」执行计划的自测（纯 JVM，不依赖 Compose 运行时）。
//
// P1：先删一条（5s 撤销条还在）→ 5s 内清空已结束 → 旧撤销条仍在，
// 点「撤销」会把已清空的行插回来，「分组变空态」不成立。修复口径 = 清空前先把窗口内快照
// commit 掉（与连续删除分支一致）。这里锁住两件事：
//  1. 清空前必须落定待撤销快照（commitUndoId）；
//  2. 批量删除的目标只有终态记录，进行中任务不受影响（契约 C-16 边界）。
// 数据用占位值（设备名「Pixel 8」、文件名「IMG_0001.jpg」），不写真实设备信息。

import com.bsfeng.monitor.domain.transfer.DeletedTask
import com.bsfeng.monitor.domain.transfer.DeviceTypes
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferFile
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferClearFinishedPlanTest {

    private fun device(): PeerDevice = PeerDevice(
        deviceId = "peer-1",
        alias = "Pixel 8",
        deviceType = DeviceTypes.ANDROID,
        hostAddress = "192.168.1.x",
        port = 53319,
        online = true,
        lastSeenMs = 0L,
    )

    private fun task(
        sessionId: String = "session-1",
        fileId: String = "file-1",
        status: TransferStatus = TransferStatus.COMPLETED,
        direction: TransferDirection = TransferDirection.SEND,
    ): TransferTask = TransferTask(
        sessionId = sessionId,
        peer = device(),
        direction = direction,
        file = TransferFile(
            fileId = fileId,
            name = "IMG_0001.jpg",
            size = 4L * 1024L * 1024L,
            sha256 = "0".repeat(64),
            mime = "image/jpeg",
        ),
        status = status,
        receivedBytes = 4L * 1024L * 1024L,
        updatedAtMs = 0L,
    )

    private fun undoOf(task: TransferTask, deletionId: String): TransferUndo = TransferUndo(
        deletion = DeletedTask(
            deletionId = deletionId,
            task = task,
            index = 0,
            fileDeleted = false,
        ),
        startedAtMs = 0L,
    )

    @Test
    fun `清空前先落定窗口内的撤销快照`() {
        val finished = task(fileId = "file-1", status = TransferStatus.COMPLETED)
        val plan = planClearFinished(
            tasks = listOf(finished, task(fileId = "file-2", status = TransferStatus.FAILED)),
            pendingUndo = undoOf(finished, deletionId = "del-1"),
        )
        assertEquals("清空前必须 commit 这张快照", "del-1", plan.commitUndoId)
    }

    @Test
    fun `没有撤销条时不需要落定`() {
        val plan = planClearFinished(
            tasks = listOf(task(status = TransferStatus.COMPLETED)),
            pendingUndo = null,
        )
        assertNull(plan.commitUndoId)
    }

    @Test
    fun `清空目标只含终态记录`() {
        val tasks = listOf(
            task(fileId = "done", status = TransferStatus.COMPLETED),
            task(fileId = "failed", status = TransferStatus.FAILED),
            task(fileId = "cancelled", status = TransferStatus.CANCELLED),
            task(fileId = "running", status = TransferStatus.TRANSFERRING),
            task(fileId = "offered", status = TransferStatus.OFFERED),
        )
        val plan = planClearFinished(tasks = tasks, pendingUndo = null)
        assertEquals(
            listOf("done", "failed", "cancelled"),
            plan.targets.map { it.file.fileId },
        )
        assertTrue("进行中任务不能被清空", plan.targets.none { it.status == TransferStatus.TRANSFERRING })
    }

    @Test
    fun `已删除的行不在清空目标里但仍要落定撤销快照`() {
        // 复现场景：先删掉 completed 那条（列表里已没有它），撤销条还开着，
        // 这时清空已结束 —— 目标只剩列表里的终态行，而快照必须先 commit，
        // 否则点「撤销」会把它插回刚清空的列表。
        val removed = task(fileId = "removed", status = TransferStatus.COMPLETED)
        val plan = planClearFinished(
            tasks = listOf(
                task(fileId = "failed", status = TransferStatus.FAILED),
                task(fileId = "running", status = TransferStatus.TRANSFERRING),
            ),
            pendingUndo = undoOf(removed, deletionId = "del-removed"),
        )
        assertEquals("del-removed", plan.commitUndoId)
        assertEquals(listOf("failed"), plan.targets.map { it.file.fileId })
    }

    @Test
    fun `空分组清空不产生任何目标`() {
        val plan = planClearFinished(tasks = emptyList(), pendingUndo = null)
        assertTrue(plan.targets.isEmpty())
        assertNull(plan.commitUndoId)
    }
}
