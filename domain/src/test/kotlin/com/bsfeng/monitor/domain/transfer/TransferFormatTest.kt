package com.bsfeng.monitor.domain.transfer

import org.junit.Assert.assertEquals
import org.junit.Test

class TransferFormatTest {

    @Test
    fun `fileSize`() {
        assertEquals("0 B", TransferFormat.fileSize(0L))
        assertEquals("512 B", TransferFormat.fileSize(512L))
        assertEquals("1023 B", TransferFormat.fileSize(1023L))
        assertEquals("1.0 KB", TransferFormat.fileSize(1024L))
        assertEquals("1.5 KB", TransferFormat.fileSize(1536L))
        assertEquals("1.0 MB", TransferFormat.fileSize(1024L * 1024L))
        assertEquals("2.0 GB", TransferFormat.fileSize(2L * 1024 * 1024 * 1024))
        assertEquals("0 B", TransferFormat.fileSize(-5L))
    }

    @Test
    fun `progressPair`() {
        assertEquals("0 / 0 B", TransferFormat.progressPair(0L, 0L))
        assertEquals("512 / 1023 B", TransferFormat.progressPair(512L, 1023L))
        assertEquals("1.0 / 2.0 KB", TransferFormat.progressPair(1024L, 2048L))
        assertEquals("0.0 / 1.0 MB", TransferFormat.progressPair(0L, 1024L * 1024L))
        assertEquals("2.0 / 2.0 KB", TransferFormat.progressPair(5000L, 2048L))
    }

    @Test
    fun `speed`() {
        assertEquals("", TransferFormat.speed(0L))
        assertEquals("", TransferFormat.speed(-1L))
        assertEquals("1.0 KB/s", TransferFormat.speed(1024L))
        assertEquals("512 B/s", TransferFormat.speed(512L))
    }

    @Test
    fun `windowRemaining`() {
        assertEquals("00:00", TransferFormat.windowRemaining(-1L))
        assertEquals("00:00", TransferFormat.windowRemaining(0L))
        assertEquals("00:01", TransferFormat.windowRemaining(1000L))
        assertEquals("01:22", TransferFormat.windowRemaining(82_000L))
        assertEquals("30:00", TransferFormat.windowRemaining(30L * 60 * 1000))
    }

    @Test
    fun `threshold`() {
        assertEquals("关闭", TransferFormat.threshold(0))
        assertEquals("关闭", TransferFormat.threshold(-3))
        assertEquals("10 MB", TransferFormat.threshold(10))
    }
}
