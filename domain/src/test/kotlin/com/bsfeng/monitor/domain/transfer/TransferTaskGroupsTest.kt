package com.bsfeng.monitor.domain.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferTaskGroupsTest {

    private fun task(status: TransferStatus, id: String = "t"): TransferTask {
        val peer = PeerDevice("d", "a", DeviceTypes.ANDROID, "1.1.1.1", 1)
        val file = TransferFile(id, "n", 10L, "s", "m")
        return TransferTask("sid-$id", peer, TransferDirection.SEND, file, status)
    }

    @Test
    fun `grouping`() {
        val tasks = listOf(
            task(TransferStatus.TRANSFERRING, "1"),
            task(TransferStatus.COMPLETED, "2"),
            task(TransferStatus.FAILED, "3"),
            task(TransferStatus.OFFERED, "4"),
        )
        assertEquals(2, TransferTaskGroups.active(tasks).size)
        assertEquals(2, TransferTaskGroups.finished(tasks).size)
        assertEquals(2, TransferTaskGroups.activeCount(tasks))
        assertEquals("进行中 · 2", TransferTaskGroups.activeTitle(tasks))
    }

    @Test
    fun `no active`() {
        val tasks = listOf(task(TransferStatus.COMPLETED, "1"))
        assertEquals(0, TransferTaskGroups.active(tasks).size)
        assertEquals(1, TransferTaskGroups.finished(tasks).size)
        assertEquals(0, TransferTaskGroups.activeCount(tasks))
        assertEquals("进行中", TransferTaskGroups.activeTitle(tasks))
    }
}
