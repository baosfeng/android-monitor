package com.bsfeng.monitor.domain.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TransferModelsTest {

    @Test
    fun `TransferErrorCode messages`() {
        assertEquals("成功", TransferErrorCode.message(TransferErrorCode.SUCCESS))
        assertEquals("配对令牌不匹配", TransferErrorCode.message(TransferErrorCode.TOKEN_MISMATCH))
        assertEquals("会话不存在或已过期", TransferErrorCode.message(TransferErrorCode.SESSION_EXPIRED))
        assertEquals("续传偏移不匹配", TransferErrorCode.message(TransferErrorCode.OFFSET_MISMATCH))
        assertEquals("接收端磁盘空间不足", TransferErrorCode.message(TransferErrorCode.DISK_FULL))
        assertEquals("文件校验失败", TransferErrorCode.message(TransferErrorCode.SHA256_MISMATCH))
        assertEquals("对方拒绝接收", TransferErrorCode.message(TransferErrorCode.USER_DENIED))
        assertEquals("确认超时", TransferErrorCode.message(TransferErrorCode.CONFIRM_TIMEOUT))
        assertEquals("传输失败（999）", TransferErrorCode.message(999))
    }

    @Test
    fun `TransferStatus properties`() {
        for (s in TransferStatus.values()) {
            val round = TransferStatus.fromWire(s.wire)
            assertEquals(s, round)
        }
        assertNull(TransferStatus.fromWire("nope"))
        assertNull(TransferStatus.fromWire(null))
        assertTrue(TransferStatus.COMPLETED.isTerminal)
        assertTrue(TransferStatus.FAILED.isTerminal)
        assertTrue(TransferStatus.CANCELLED.isTerminal)
        assertTrue(TransferStatus.DENIED.isTerminal)
        assertFalse(TransferStatus.TRANSFERRING.isTerminal)
        assertEquals("pending", TransferStatus.AWAITING_CONFIRM.wire)
        assertEquals("approved", TransferStatus.TRANSFERRING.wire)
    }

    @Test
    fun `TransferFile init and acceptedJson`() {
        val f = TransferFile("id", "name.txt", 10L, "sha", "text/plain")
        assertEquals("{\"fileId\":\"id\",\"offset\":5}", f.acceptedJson(5L))
        var thrown = false
        try { TransferFile("", "name", 0L, "sha", "m") } catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
        thrown = false
        try { TransferFile("id", "  ", 0L, "sha", "m") } catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
        thrown = false
        try { TransferFile("id", "name", -1L, "sha", "m") } catch (e: IllegalArgumentException) { thrown = true }
        assertTrue(thrown)
    }

    @Test
    fun `PeerDevice baseUrl and caps`() {
        val p = PeerDevice("d", "alias", DeviceTypes.ANDROID, "192.168.1.2", 8080, caps = listOf("send"))
        assertEquals("http://192.168.1.2:8080", p.baseUrl)
        assertFalse(p.canReceive())
        val p2 = PeerDevice("d", "alias", DeviceTypes.UNKNOWN, "1.2.3.4", 1, caps = emptyList())
        assertTrue(p2.canReceive())
        val p3 = PeerDevice("d", "alias", DeviceTypes.MAC, "1.2.3.4", 1, caps = listOf("recv"))
        assertTrue(p3.canReceive())
    }

    @Test
    fun `TransferTask fraction and isActive`() {
        val peer = PeerDevice("d", "a", DeviceTypes.ANDROID, "1.1.1.1", 1)
        val file = TransferFile("f", "n", 10L, "s", "m")
        val t = TransferTask("s", peer, TransferDirection.SEND, file, TransferStatus.TRANSFERRING, receivedBytes = 5L, bytesPerSecond = 10L)
        assertEquals(0.5f, t.fraction, 0.001f)
        assertTrue(t.isActive)
        val zero = t.copy(file = TransferFile("f2", "n2", 0L, "s", "m"), status = TransferStatus.COMPLETED)
        assertEquals(0f, zero.fraction, 0.001f)
        assertFalse(zero.isActive)
    }

    @Test
    fun `constants and enums`() {
        assertEquals(1, TRANSFER_PROTOCOL_VERSION)
        assertEquals("send", TransferDirection.SEND.name.lowercase())
        assertEquals("android", DeviceTypes.ANDROID)
        assertEquals("局域网互传", DOWNLOAD_RELATIVE_DIR)
        assertEquals(".part", PART_SUFFIX)
    }
}
