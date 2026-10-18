package com.bsfeng.monitor.transfer.domain

import com.bsfeng.monitor.domain.transfer.DeletedTask
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferFile
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.domain.transfer.TransferTaskDeletion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 任务删除规则（纯 JVM）。
 *
 * 覆盖：已终结任务删除（含接收文件清理）、进行中任务不可删除、失败任务只移除记录、
 * 发送任务不删用户源文件、撤销按原下标恢复。
 */
class TransferTaskDeletionTest {

    private fun task(
        status: TransferStatus,
        direction: TransferDirection = TransferDirection.RECV,
        sessionId: String = "s1",
        fileId: String = "f1",
    ): TransferTask = TransferTask(
        sessionId = sessionId,
        peer = PeerDevice(
            deviceId = "mac-1",
            alias = "bsfeng 的 Mac",
            deviceType = "macos",
            hostAddress = "192.168.1.2",
            port = 53319,
        ),
        direction = direction,
        file = TransferFile(fileId, "a.bin", 10L, "sha-$fileId", "application/octet-stream"),
        status = status,
        receivedBytes = if (status == TransferStatus.COMPLETED) 10L else 0L,
        updatedAtMs = 1L,
    )

    @Test
    fun `已完成接收任务删除时同时删除已落盘文件并可撤销恢复记录`() {
        val original = task(TransferStatus.COMPLETED)
        var removed = 0
        val deleted = TransferTaskDeletion.delete(
            tasks = listOf(original),
            sessionId = "s1",
            fileId = "f1",
            deletionId = "d1",
            removeReceivedFile = {
                removed++
                true
            },
        )

        requireNotNull(deleted) { "已完成任务应允许删除" }
        assertEquals("d1", deleted.deletionId)
        assertEquals(0, deleted.index)
        assertTrue("接收文件应随记录一起删除", deleted.fileDeleted)
        assertEquals(1, removed)

        val remaining = TransferTaskDeletion.without(listOf(original), deleted)
        assertTrue(remaining.isEmpty())

        val restored = TransferTaskDeletion.restore(remaining, deleted)
        assertEquals(listOf(original), restored)
    }

    @Test
    fun `进行中的任务不可删除且不触发文件清理`() {
        listOf(
            TransferStatus.IDLE,
            TransferStatus.OFFERED,
            TransferStatus.AWAITING_CONFIRM,
            TransferStatus.TRANSFERRING,
        ).forEach { status ->
            var removed = false
            val deleted = TransferTaskDeletion.delete(
                tasks = listOf(task(status)),
                sessionId = "s1",
                fileId = "f1",
                deletionId = "d-$status",
                removeReceivedFile = {
                    removed = true
                    true
                },
            )
            assertNull("$status 不应允许滑动删除", deleted)
            assertFalse("$status 不应触发文件清理", removed)
        }
    }

    @Test
    fun `失败与已取消的任务只移除记录不碰文件`() {
        listOf(TransferStatus.FAILED, TransferStatus.CANCELLED, TransferStatus.DENIED).forEach { status ->
            var removed = false
            val deleted = TransferTaskDeletion.delete(
                tasks = listOf(task(status)),
                sessionId = "s1",
                fileId = "f1",
                deletionId = "d-$status",
                removeReceivedFile = {
                    removed = true
                    true
                },
            )
            requireNotNull(deleted)
            assertFalse("$status 不应删除文件（.part 按协议 §6 保留）", deleted.fileDeleted)
            assertFalse(removed)
        }
    }

    @Test
    fun `发送任务即使已完成也不删除用户源文件`() {
        var removed = false
        val deleted = TransferTaskDeletion.delete(
            tasks = listOf(task(TransferStatus.COMPLETED, direction = TransferDirection.SEND)),
            sessionId = "s1",
            fileId = "f1",
            deletionId = "d-send",
            removeReceivedFile = {
                removed = true
                true
            },
        )

        requireNotNull(deleted)
        assertFalse(deleted.fileDeleted)
        assertFalse("发送任务的文件属于用户本机，不能删", removed)
    }

    @Test
    fun `任务不存在时返回 null`() {
        assertNull(
            TransferTaskDeletion.delete(
                tasks = listOf(task(TransferStatus.COMPLETED)),
                sessionId = "s1",
                fileId = "f2",
                deletionId = "d-missing",
                removeReceivedFile = { true },
            ),
        )
    }

    @Test
    fun `撤销按原下标恢复且重复恢复不插入第二份`() {
        val first = task(TransferStatus.COMPLETED, fileId = "f1")
        val second = task(TransferStatus.COMPLETED, fileId = "f2")
        val third = task(TransferStatus.COMPLETED, fileId = "f3")
        val tasks = listOf(first, second, third)
        val deleted: DeletedTask = requireNotNull(
            TransferTaskDeletion.delete(tasks, "s1", "f2", "d2") { true },
        )

        val remaining = TransferTaskDeletion.without(tasks, deleted)
        assertEquals(listOf(first, third), remaining)
        val restored = TransferTaskDeletion.restore(remaining, deleted)
        assertEquals(tasks, restored)
        assertEquals("重复撤销不应产生重复项", restored, TransferTaskDeletion.restore(restored, deleted))
    }
}
