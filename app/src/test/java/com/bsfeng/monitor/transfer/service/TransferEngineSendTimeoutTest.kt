package com.bsfeng.monitor.transfer.service

import android.content.Context
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.domain.transfer.DeviceTypes
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import com.bsfeng.monitor.domain.transfer.TransferFile
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.domain.transfer.TransferTaskGroups
import com.bsfeng.monitor.transfer.data.TransferTaskStore
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 发送侧超时收敛（真机 Bug：「进行中」卡着一条发送任务两小时以上不收敛）。
 *
 * 发送会话没有超时路径：对端失联 / 不回话时任务永远停在 `OFFERED`（已提交清单）或
 * `TRANSFERRING`。修复口径与接收侧完全一致——`FAILED` + 4007，
 * 并把 `outgoing` 条目摘掉（否则内存里一直留着已死的会话）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferEngineSendTimeoutTest {

    private lateinit var context: Context
    private var engine: TransferEngine? = null

    private val peer = PeerDevice(
        deviceId = "mac-1",
        alias = "Mac",
        deviceType = DeviceTypes.MAC,
        hostAddress = "192.168.1.5",
        port = 53319,
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(TransferSettings.PREFS_NAME, 0).edit().clear().commit()
        File(context.cacheDir, "transfer").deleteRecursively()
        TransferEngine.resetForTesting()
        DatabaseHelper.getInstance(context).writableDatabase
            .execSQL("DELETE FROM ${TransferTaskStore.TABLE}")
    }

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
        File(context.cacheDir, "transfer").deleteRecursively()
    }

    private fun newEngine(
        confirmTimeoutMs: Long = 1_000L,
        noProgressTimeoutMs: Long = 1_000L,
    ): TransferEngine = TransferEngine.createForTesting(
        context = context,
        confirmTimeoutMs = confirmTimeoutMs,
        noProgressTimeoutMs = noProgressTimeoutMs,
        tickMs = 20L,
    ).also { engine = it }

    private fun sendTask(
        status: TransferStatus,
        createdAtMs: Long,
        updatedAtMs: Long = createdAtMs,
        sessionId: String = "send-1",
        fileName: String = "mmexport1792300440454.jpg",
        direction: TransferDirection = TransferDirection.SEND,
    ): TransferTask = TransferTask(
        sessionId = sessionId,
        peer = peer,
        direction = direction,
        file = TransferFile("f1", fileName, 2_700_000L, "sha-$sessionId", "image/jpeg"),
        status = status,
        receivedBytes = 0L,
        updatedAtMs = updatedAtMs,
        createdAtMs = createdAtMs,
    )

    @Test
    fun `发送任务停在已提交清单超时后收敛为失败并移出进行中`() {
        val transferEngine = newEngine()
        val stale = sendTask(TransferStatus.OFFERED, createdAtMs = System.currentTimeMillis() - 5_000L)
        transferEngine.injectSendTaskForTesting(stale)
        assertEquals(TransferStatus.OFFERED, transferEngine.tasks.value.single().status)
        assertEquals(1, transferEngine.outgoingSessionCountForTesting())

        transferEngine.enforceTimeoutsForTesting()

        val task = transferEngine.tasks.value.single()
        assertEquals(TransferStatus.FAILED, task.status)
        assertEquals(TransferErrorCode.CONFIRM_TIMEOUT, task.errorCode)
        assertEquals("对端未确认", task.message)
        assertEquals(0, TransferTaskGroups.activeCount(transferEngine.tasks.value))
        assertEquals("收敛后应摘掉 outgoing 条目", 0, transferEngine.outgoingSessionCountForTesting())
    }

    @Test
    fun `发送任务传输中长时间无进展收敛为失败`() {
        val transferEngine = newEngine()
        val stale = sendTask(
            TransferStatus.TRANSFERRING,
            createdAtMs = System.currentTimeMillis() - 60_000L,
            updatedAtMs = System.currentTimeMillis() - 30_000L,
        )
        transferEngine.injectSendTaskForTesting(stale)

        transferEngine.enforceTimeoutsForTesting()

        val task = transferEngine.tasks.value.single()
        assertEquals(TransferStatus.FAILED, task.status)
        assertEquals(TransferErrorCode.CONFIRM_TIMEOUT, task.errorCode)
        assertEquals("长时间无进展", task.message)
        assertEquals(0, TransferTaskGroups.activeCount(transferEngine.tasks.value))
        assertEquals(0, transferEngine.outgoingSessionCountForTesting())
    }

    @Test
    fun `未超时的发送任务不被动且保留 outgoing 条目`() {
        val transferEngine = newEngine()
        val now = System.currentTimeMillis()
        transferEngine.injectSendTaskForTesting(
            sendTask(TransferStatus.OFFERED, createdAtMs = now, sessionId = "send-offered"),
        )
        transferEngine.injectSendTaskForTesting(
            sendTask(TransferStatus.TRANSFERRING, createdAtMs = now, updatedAtMs = now, sessionId = "send-active"),
        )

        transferEngine.enforceTimeoutsForTesting()

        val byId = transferEngine.tasks.value.associateBy { it.sessionId }
        assertEquals(TransferStatus.OFFERED, byId.getValue("send-offered").status)
        assertEquals(TransferStatus.TRANSFERRING, byId.getValue("send-active").status)
        assertEquals(2, transferEngine.outgoingSessionCountForTesting())
        assertEquals(2, TransferTaskGroups.activeCount(transferEngine.tasks.value))
    }

    @Test
    fun `收敛幂等：重复检查不出错也不改动终态`() {
        val transferEngine = newEngine()
        transferEngine.injectSendTaskForTesting(
            sendTask(TransferStatus.OFFERED, createdAtMs = System.currentTimeMillis() - 5_000L),
        )

        transferEngine.enforceTimeoutsForTesting()
        transferEngine.enforceTimeoutsForTesting()
        transferEngine.enforceTimeoutsForTesting()

        val task = transferEngine.tasks.value.single()
        assertEquals(TransferStatus.FAILED, task.status)
        assertEquals(TransferErrorCode.CONFIRM_TIMEOUT, task.errorCode)
        assertEquals(0, transferEngine.outgoingSessionCountForTesting())
    }

    @Test
    fun `历史加载把陈旧的发送僵尸任务收敛为失败`() = runBlocking {
        val store = TransferTaskStore(DatabaseHelper.getInstance(context))
        val now = System.currentTimeMillis()
        // 陈旧：已提交清单 10 分钟还没被确认（真机就是卡了两小时以上）
        store.upsert(TransferTaskStore.Entry(sendTask(TransferStatus.OFFERED, createdAtMs = now - 600_000L), null))
        // 新鲜：刚提交，不应被当成超时（但仍按「上次未完成」处理）
        store.upsert(
            TransferTaskStore.Entry(
                sendTask(TransferStatus.OFFERED, createdAtMs = now, sessionId = "send-fresh"),
                null,
            ),
        )
        // 终态：原样保留
        store.upsert(
            TransferTaskStore.Entry(
                sendTask(TransferStatus.COMPLETED, createdAtMs = now - 600_000L, sessionId = "send-done"),
                null,
            ),
        )
        assertEquals(3, store.count())

        val transferEngine = newEngine()
        transferEngine.loadPersistedHistory()

        val byId = transferEngine.tasks.value.associateBy { it.sessionId }
        val stale = byId.getValue("send-1")
        assertEquals(TransferStatus.FAILED, stale.status)
        assertEquals(TransferErrorCode.CONFIRM_TIMEOUT, stale.errorCode)
        assertEquals("对端未确认", stale.message)

        val fresh = byId.getValue("send-fresh")
        assertEquals(TransferStatus.FAILED, fresh.status)
        assertEquals(TransferErrorCode.SESSION_EXPIRED, fresh.errorCode)
        assertEquals("上次未完成", fresh.message)

        val done = byId.getValue("send-done")
        assertEquals(TransferStatus.COMPLETED, done.status)
        assertTrue(TransferTaskGroups.activeCount(transferEngine.tasks.value) == 0)
        assertNotNull(engine)
    }
}
