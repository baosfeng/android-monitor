package com.bsfeng.monitor.transfer.ui

// 「传送」页文案 / 分支的纯函数自测（不依赖 Compose 运行时，不引入 compose-ui-test）。
//
// 这些断言锁住的是**账本里的逐字文案与分支**（C-02 N=1 / C-05 焦点去向 / C-08 回执三态 /
// C-16 后果说明 / 行摊平分组）：改文案时静默坏掉是最常见的回归方式。
// 数据用占位值（设备名「Pixel 8」、文件名「IMG_0001.jpg」、网段 192.168.1.x），不写真实设备信息。

import com.bsfeng.monitor.domain.transfer.DeviceTypes
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferFile
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TransferPageStateTest {

    private val clock: (Long) -> String = { "14:32" }

    private fun device(
        id: String = "peer-1",
        online: Boolean = true,
        lastSeenMs: Long = 0L,
    ): PeerDevice = PeerDevice(
        deviceId = id,
        alias = "Pixel 8",
        deviceType = DeviceTypes.ANDROID,
        hostAddress = "192.168.1.x",
        port = 53319,
        online = online,
        lastSeenMs = lastSeenMs,
    )

    private fun task(
        sessionId: String = "session-1",
        fileId: String = "file-1",
        status: TransferStatus = TransferStatus.TRANSFERRING,
        direction: TransferDirection = TransferDirection.SEND,
        receivedBytes: Long = 0L,
        size: Long = 4L * 1024L * 1024L,
        updatedAtMs: Long = 0L,
    ): TransferTask = TransferTask(
        sessionId = sessionId,
        peer = device(),
        direction = direction,
        file = TransferFile(
            fileId = fileId,
            name = "IMG_0001.jpg",
            size = size,
            sha256 = "0".repeat(64),
            mime = "image/jpeg",
        ),
        status = status,
        receivedBytes = receivedBytes,
        updatedAtMs = updatedAtMs,
    )

    // ───────── C-08 回执条三态 ─────────

    @Test
    fun `回执条：全部送达写完整送达文案并带时间后缀`() {
        val tasks = listOf(
            task(fileId = "f1", status = TransferStatus.COMPLETED),
            task(fileId = "f2", status = TransferStatus.COMPLETED),
            task(fileId = "f3", status = TransferStatus.COMPLETED),
        )
        assertEquals("3 个文件已全部送达 · 14:32", receiptText(tasks, clock))
        assertTrue(receiptDelivered(tasks))
    }

    @Test
    fun `回执条：部分失败写已送达数与失败数`() {
        val tasks = listOf(
            task(fileId = "f1", status = TransferStatus.COMPLETED),
            task(fileId = "f2", status = TransferStatus.COMPLETED),
            task(fileId = "f3", status = TransferStatus.FAILED),
        )
        assertEquals("已送达 2 / 3，1 个失败", receiptText(tasks, clock))
        assertFalse(receiptDelivered(tasks))
    }

    @Test
    fun `回执条：进行中写文件数与已送达数且不带时间`() {
        val tasks = listOf(
            task(fileId = "f1", status = TransferStatus.COMPLETED),
            task(fileId = "f2", status = TransferStatus.TRANSFERRING),
            task(fileId = "f3", status = TransferStatus.TRANSFERRING),
        )
        assertEquals("3 个文件 · 已送达 1 / 3", receiptText(tasks, clock))
    }

    @Test
    fun `回执条：单文件批次整条隐藏`() {
        assertFalse(showsReceipt(listOf(task(fileId = "f1", status = TransferStatus.COMPLETED))))
        assertTrue(showsReceipt(listOf(task(fileId = "f1"), task(fileId = "f2"))))
    }

    // ───────── C-02 / C-09 取消面板文案 ─────────

    @Test
    fun `取消面板：单批次与全部的标题不同`() {
        val single = CancelTarget(listOf("s1"), 3, 0L, 3L * 1024 * 1024, all = false)
        val all = CancelTarget(listOf("s1", "s2"), 5, 0L, 5L * 1024 * 1024, all = true)
        assertEquals("取消 3 个传输？", cancelTitle(single))
        assertEquals("取消全部 5 个传输？", cancelTitle(all))
    }

    @Test
    fun `取消面板：N=1 时按钮写「取消这个文件」`() {
        val one = CancelTarget(listOf("s1"), 1, 0L, 1024L, all = false)
        val many = CancelTarget(listOf("s1"), 3, 0L, 3L * 1024, all = false)
        val all = CancelTarget(listOf("s1"), 5, 0L, 5L * 1024, all = true)
        assertEquals("取消这个文件", cancelConfirmLabel(one))
        assertEquals("取消 3 个文件", cancelConfirmLabel(many))
        assertEquals("取消全部 5 个文件", cancelConfirmLabel(all))
    }

    @Test
    fun `取消面板：已传 0 B 时不出现「已传」行`() {
        val fresh = CancelTarget(listOf("s1"), 2, 0L, 2L * 1024 * 1024, all = false)
        assertFalse(cancelBody(fresh).contains("已传"))
        assertTrue(cancelBody(fresh).contains("取消后需要重新发送"))
        val started = fresh.copy(sentBytes = 1024L * 1024)
        assertTrue(cancelBody(started).contains("已传"))
    }

    @Test
    fun `取消面板：只挑活跃文件，全部终态时不给面板`() {
        val tasks = listOf(
            task(fileId = "f1", status = TransferStatus.TRANSFERRING, receivedBytes = 1024L),
            task(fileId = "f2", status = TransferStatus.COMPLETED),
        )
        val target = cancelTargetOf(tasks, sessionId = "session-1")
        assertNotNull(target)
        assertEquals(1, target?.fileCount)
        assertEquals(1024L, target?.sentBytes)
        assertNull(cancelTargetOf(listOf(task(status = TransferStatus.COMPLETED)), sessionId = "session-1"))
    }

    // ───────── C-16 清空确认必须写清后果 ─────────

    @Test
    fun `清空确认：标题带条数，副行必须写清「无法恢复」与将删文件数`() {
        assertEquals("清空 24 条记录？", clearConfirmTitle(24))
        val body = clearConfirmBody(recordCount = 24, fileCount = 12)
        assertTrue(body.contains("12 条"))
        assertTrue(body.contains("清空后无法恢复"))
        assertTrue(clearConfirmBody(recordCount = 3, fileCount = 0).contains("不会动本机文件"))
    }

    // ───────── C-05 删除后焦点去向 ─────────

    @Test
    fun `删除后焦点：落到下一条；最后一条落到列表标题`() {
        val first = task(fileId = "f1", status = TransferStatus.COMPLETED)
        val second = task(fileId = "f2", status = TransferStatus.COMPLETED)
        val tasks = listOf(first, second)
        assertEquals(taskFocusId(second), nextFocusAfterDelete(tasks, first))
        assertEquals("title-finished", nextFocusAfterDelete(tasks, second))
    }

    @Test
    fun `删除确认：只有「接收 + 已完成」才需要二次确认`() {
        assertTrue(needsDeleteConfirm(task(status = TransferStatus.COMPLETED, direction = TransferDirection.RECV)))
        assertFalse(needsDeleteConfirm(task(status = TransferStatus.COMPLETED, direction = TransferDirection.SEND)))
        assertFalse(needsDeleteConfirm(task(status = TransferStatus.FAILED, direction = TransferDirection.RECV)))
    }

    // ───────── 行摊平 / 分组 ─────────

    @Test
    fun `行摊平：空设备出空态行，有设备出逐台设备行`() {
        val empty = buildTransferRows(devices = emptyList(), tasks = emptyList())
        assertEquals(TransferRow.Kind.DEVICES_EMPTY, empty.first { it.key == "devices-empty" }.kind)

        val rows = buildTransferRows(devices = listOf(device("a"), device("b")), tasks = emptyList())
        assertEquals(2, rows.count { it.kind == TransferRow.Kind.DEVICE })
        assertTrue(rows.any { it.kind == TransferRow.Kind.SEND })
    }

    @Test
    fun `行摊平：同组同会话多文件出一条回执，单文件不出`() {
        val batch = listOf(
            task(fileId = "f1", status = TransferStatus.TRANSFERRING),
            task(fileId = "f2", status = TransferStatus.AWAITING_CONFIRM),
        )
        val rows = buildTransferRows(devices = emptyList(), tasks = batch)
        val receipts = rows.filter { it.kind == TransferRow.Kind.RECEIPT }
        assertEquals(1, receipts.size)
        assertEquals(2, receipts.first().tasks.size)
        // 回执条必须排在同一批任务卡之前
        val receiptIndex = rows.indexOfFirst { it.kind == TransferRow.Kind.RECEIPT }
        val firstTaskIndex = rows.indexOfFirst { it.kind == TransferRow.Kind.TASK }
        assertTrue(receiptIndex in 0 until firstTaskIndex)

        val single = buildTransferRows(devices = emptyList(), tasks = listOf(task(status = TransferStatus.TRANSFERRING)))
        assertTrue(single.none { it.kind == TransferRow.Kind.RECEIPT })

        // 同一会话跨分组（1 条已完成 + 1 条进行中）：各组只剩 1 条，不出回执条
        // —— 源码是逐文件任务模型、没有批次实体，回执按「同组同会话」汇总（见交接文档处置表）
        val split = listOf(
            task(fileId = "f1", status = TransferStatus.COMPLETED),
            task(fileId = "f2", status = TransferStatus.TRANSFERRING),
        )
        assertTrue(buildTransferRows(devices = emptyList(), tasks = split).none { it.kind == TransferRow.Kind.RECEIPT })
    }

    @Test
    fun `行摊平：分组标题动作的出现条件`() {
        // 活跃会话 ≥ 2 才出「全部取消」（C-09 边界：1 个时隐藏，避免与卡内 C-01 重复）
        val oneSession = buildTransferRows(
            devices = emptyList(),
            tasks = listOf(task(sessionId = "s1", fileId = "f1"), task(sessionId = "s1", fileId = "f2")),
        )
        assertNull(oneSession.first { it.key == "title-active" }.titleAction)

        val twoSessions = buildTransferRows(
            devices = emptyList(),
            tasks = listOf(task(sessionId = "s1", fileId = "f1"), task(sessionId = "s2", fileId = "f2")),
        )
        assertEquals(TransferRow.TitleAction.CANCEL_ALL, twoSessions.first { it.key == "title-active" }.titleAction)

        // 终态记录 ≥ 2 才出「清空已结束」（C-16 边界）
        val twoFinished = buildTransferRows(
            devices = emptyList(),
            tasks = listOf(
                task(fileId = "f1", status = TransferStatus.COMPLETED),
                task(fileId = "f2", status = TransferStatus.CANCELLED),
            ),
        )
        assertEquals(
            TransferRow.TitleAction.CLEAR_FINISHED,
            twoFinished.first { it.key == "title-finished" }.titleAction,
        )
        assertNull(buildTransferRows(devices = emptyList(), tasks = emptyList()).first { it.key == "title-finished" }.titleAction)
    }

    @Test
    fun `分组空态文案逐字`() {
        val rows = buildTransferRows(devices = emptyList(), tasks = emptyList())
        assertEquals(ACTIVE_EMPTY_HINT, rows.first { it.key == "hint-active" }.hint)
        assertEquals(FINISHED_EMPTY_HINT, rows.first { it.key == "hint-finished" }.hint)
        assertEquals("暂无进行中的传输", ACTIVE_EMPTY_HINT)
        assertEquals("完成的传输会留在这里", FINISHED_EMPTY_HINT)
    }

    // ───────── C-13 / C-11 发送按钮三态 ─────────

    @Test
    fun `发送按钮：无选中、选中离线、可发送三态文案`() {
        val online = listOf(device("a", online = true), device("b", online = false))
        assertEquals(SendButtonState.NO_DEVICE, sendButtonState(online, null))
        assertEquals(SendButtonState.OFFLINE, sendButtonState(online, "b"))
        assertEquals(SendButtonState.READY, sendButtonState(online, "a"))
        // 选中的设备已从列表消失（超时移除）也按离线处理：不能让按钮回到「可发送」
        assertEquals(SendButtonState.OFFLINE, sendButtonState(online, "gone"))
        assertEquals("先选择一台设备", sendButtonLabel(SendButtonState.NO_DEVICE))
        assertEquals("对方已离线，请重新选择", sendButtonLabel(SendButtonState.OFFLINE))
        assertEquals("选择文件发送", sendButtonLabel(SendButtonState.READY))
    }

    // ───────── 终态副行 / 设备副行 ─────────

    @Test
    fun `终态副行：发送「对方已收到」、接收「已保存到 Downloads」`() {
        val at = 1_700_000_000_000L
        val send = task(status = TransferStatus.COMPLETED, direction = TransferDirection.SEND, updatedAtMs = at)
        val recv = task(status = TransferStatus.COMPLETED, direction = TransferDirection.RECV, updatedAtMs = at)
        assertTrue(finishedSubtitle(send, clock).startsWith("对方已收到 · "))
        assertTrue(finishedSubtitle(recv, clock).startsWith("已保存到 Downloads/局域网互传 · "))
        assertTrue(finishedSubtitle(task(status = TransferStatus.CANCELLED, updatedAtMs = at), clock).startsWith("已取消 · "))
    }

    @Test
    fun `设备副行：离线写上次在线时分`() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val calendar = Calendar.getInstance(zone).apply {
            set(2026, Calendar.OCTOBER, 27, 14, 2, 0)
            set(Calendar.MILLISECOND, 0)
        }
        val offline = device(online = false, lastSeenMs = calendar.timeInMillis)
        assertEquals("离线 · 上次 14:02", deviceSubtitle(offline) { formatClock(it, zone) })
        assertTrue(deviceSubtitle(device(online = true)).startsWith("在线 · 192.168.1.x:"))
    }

    @Test
    fun `formatClock：本地时区写时分，未记录时占位`() {
        val zone = TimeZone.getTimeZone("Asia/Shanghai")
        val calendar = Calendar.getInstance(zone).apply {
            set(2026, Calendar.OCTOBER, 27, 14, 32, 0)
            set(Calendar.MILLISECOND, 0)
        }
        assertEquals("14:32", formatClock(calendar.timeInMillis, zone))
        assertEquals("--:--", formatClock(0L, zone))
    }
}
