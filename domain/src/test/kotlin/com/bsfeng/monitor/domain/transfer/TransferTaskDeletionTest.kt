package com.bsfeng.monitor.domain.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferTaskDeletionTest {

    private fun task(status: TransferStatus, dir: TransferDirection = TransferDirection.SEND, id: String = "f1"): TransferTask {
        val peer = PeerDevice("d", "a", DeviceTypes.ANDROID, "1.1.1.1", 1)
        val file = TransferFile(id, "n", 10L, "s", "m")
        return TransferTask("sid", peer, dir, file, status)
    }

    @Test
    fun `isDeletable`() {
        assertTrue(TransferTaskDeletion.isDeletable(task(TransferStatus.COMPLETED)))
        assertTrue(TransferTaskDeletion.isDeletable(task(TransferStatus.FAILED)))
        assertTrue(TransferTaskDeletion.isDeletable(task(TransferStatus.CANCELLED)))
        assertTrue(TransferTaskDeletion.isDeletable(task(TransferStatus.DENIED)))
        assertFalse(TransferTaskDeletion.isDeletable(task(TransferStatus.TRANSFERRING)))
        assertFalse(TransferTaskDeletion.isDeletable(task(TransferStatus.OFFERED)))
    }

    @Test
    fun `delete null cases`() {
        val tasks = listOf(task(TransferStatus.TRANSFERRING, id = "f1"))
        assertNull(TransferTaskDeletion.delete(tasks, "sid", "f1", "del", { false }))
        assertNull(TransferTaskDeletion.delete(tasks, "sid", "no", "del", { false }))
        val done = listOf(task(TransferStatus.COMPLETED, id = "f1"))
        val snap = TransferTaskDeletion.delete(done, "sid", "f1", "del", { true })
        assertTrue(snap != null)
    }

    @Test
    fun `delete fileDeleted logic`() {
        val recvDone = task(TransferStatus.COMPLETED, TransferDirection.RECV, "r")
        val snap = TransferTaskDeletion.delete(listOf(recvDone), "sid", "r", "del1", { true })
        assertEquals(true, snap?.fileDeleted)
        val snap2 = TransferTaskDeletion.delete(listOf(recvDone), "sid", "r", "del2", { false })
        assertEquals(false, snap2?.fileDeleted)

        val sendDone = task(TransferStatus.COMPLETED, TransferDirection.SEND, "s")
        val snap3 = TransferTaskDeletion.delete(listOf(sendDone), "sid", "s", "del3", { true })
        assertEquals(false, snap3?.fileDeleted)
    }

    @Test
    fun `without and restore`() {
        val t1 = task(TransferStatus.COMPLETED, id = "a")
        val t2 = task(TransferStatus.COMPLETED, id = "b")
        val tasks = listOf(t1, t2)
        val snap = TransferTaskDeletion.delete(tasks, "sid", "a", "del", { false })!!
        val removed = TransferTaskDeletion.without(tasks, snap)
        assertEquals(1, removed.size)
        assertEquals("b", removed[0].file.fileId)

        val restored = TransferTaskDeletion.restore(removed, snap)
        assertEquals(2, restored.size)
        assertEquals("a", restored[0].file.fileId)

        val same = TransferTaskDeletion.restore(tasks, snap)
        assertEquals(2, same.size)

        val snap2 = snap.copy(index = 100)
        val clamped = TransferTaskDeletion.restore(emptyList(), snap2)
        assertEquals(1, clamped.size)
    }

    @Test
    fun `DeletedTask data`() {
        val d = DeletedTask("id", task(TransferStatus.COMPLETED), 0, false)
        assertEquals("id", d.deletionId)
        assertEquals(0, d.index)
        assertFalse(d.fileDeleted)
    }
}
