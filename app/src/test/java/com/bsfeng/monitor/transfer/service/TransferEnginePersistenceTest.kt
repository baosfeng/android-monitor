package com.bsfeng.monitor.transfer.service

import android.content.Context
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.transfer.data.TransferTaskStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
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
import org.robolectric.shadows.ShadowStatFs
import java.io.File

/**
 * 任务历史持久化（`transfer_tasks`）：终态落库、删除 / 撤销同步、启动加载、只保留最近 N 条。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferEnginePersistenceTest {

    private lateinit var context: Context
    private var engine: TransferEngine? = null
    private lateinit var store: TransferTaskStore

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(TransferSettings.PREFS_NAME, 0).edit().clear().commit()
        File(context.cacheDir, "transfer").deleteRecursively()
        ShadowStatFs.registerStats(context.cacheDir, 4096, 1_048_576, 1_048_576)
        val helper = DatabaseHelper.getInstance(context)
        helper.writableDatabase.execSQL("DELETE FROM ${TransferTaskStore.TABLE}")
        store = TransferTaskStore(helper)
    }

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
        File(context.cacheDir, "transfer").deleteRecursively()
        ShadowStatFs.reset()
    }

    private fun newEngine(): TransferEngine = TransferEngine.createForTesting(
        context = context,
        noProgressTimeoutMs = 120L,
        tickMs = 20L,
    ).also { engine = it }

    private fun offerBody(senderDeviceId: String = "mac-persist"): JSONObject = JSONObject().apply {
        put("senderDeviceId", senderDeviceId)
        put("senderAlias", "Mac")
        put(
            "files",
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("fileId", "f1")
                        put("name", "照片.jpg")
                        put("size", 23L)
                        put("sha256", "sha-23")
                        put("mime", "image/jpeg")
                    },
                )
            },
        )
    }

    private fun await(timeoutMs: Long = 3_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test
    fun `终态任务落库 删除同步 撤销恢复`() {
        val transferEngine = newEngine()
        transferEngine.settings().startOpenPairingWindow()
        val approved = runBlocking { transferEngine.offer(offerBody(), null) }
        val sessionId = approved.string("sessionId")
        assertEquals("approved", approved.string("state"))

        // 创建即落库（创建时是 OFFERED；进终态才会再写一次）
        assertTrue(await { store.count() == 1 })
        assertEquals(TransferStatus.OFFERED, store.loadRecent().single().task.status)

        // 超时 → 终态写库
        assertTrue(
            await {
                runBlocking { transferEngine.offerStatus(sessionId) }
                store.loadRecent().firstOrNull()?.task?.status == TransferStatus.FAILED
            },
        )

        // 删除 → 库里同步移除（撤销的写回见下一个用例）
        assertNotNull(transferEngine.deleteTaskNow(sessionId, "f1"))
        assertTrue(await { store.count() == 0 })
    }

    @Test
    fun `撤销删除会把记录写回任务表`() {
        val transferEngine = newEngine()
        transferEngine.settings().startOpenPairingWindow()
        val approved = runBlocking { transferEngine.offer(offerBody("mac-undo"), null) }
        val sessionId = approved.string("sessionId")
        assertTrue(await { store.count() == 1 })
        assertTrue(
            await {
                runBlocking { transferEngine.offerStatus(sessionId) }
                store.loadRecent().firstOrNull()?.task?.status == TransferStatus.FAILED
            },
        )

        val deleted = transferEngine.deleteTaskNow(sessionId, "f1")
        assertNotNull(deleted)
        assertTrue(await { store.count() == 0 })

        val restored = transferEngine.undoDelete(deleted!!.deletionId)
        assertNotNull(restored)
        assertTrue(await { store.count() == 1 })
        assertEquals(TransferStatus.FAILED, store.loadRecent().single().task.status)
    }

    @Test
    fun `启动加载历史 且崩溃残留的非终态按失败展示`() {
        val transferEngine = newEngine()
        transferEngine.settings().startOpenPairingWindow()
        runBlocking { transferEngine.offer(offerBody("mac-load"), null) }
        assertTrue(await { store.count() == 1 })

        // 模拟进程被杀：库里留一条非终态（TRANSFERRING）的行
        val persisted = store.loadRecent().single().task.copy(
            status = TransferStatus.TRANSFERRING,
            updatedAtMs = System.currentTimeMillis(),
        )
        store.upsert(TransferTaskStore.Entry(persisted, null))

        // 新实例（模拟重启）启动加载
        val restarted = newEngine()
        runBlocking { restarted.loadPersistedHistory() }
        val loaded = restarted.tasks.value.first { it.file.fileId == "f1" }
        assertEquals(TransferStatus.FAILED, loaded.status)
        assertEquals("上次未完成", loaded.message)
    }

    @Test
    fun `历史只保留最近 N 条`() {
        repeat(TransferTaskStore.MAX_PERSISTED_TASKS + 5) { index ->
            val task = com.bsfeng.monitor.domain.transfer.TransferTask(
                sessionId = "old-$index",
                peer = com.bsfeng.monitor.domain.transfer.PeerDevice(
                    "mac", "Mac", com.bsfeng.monitor.domain.transfer.DeviceTypes.MAC, "", 0,
                ),
                direction = com.bsfeng.monitor.domain.transfer.TransferDirection.RECV,
                file = com.bsfeng.monitor.domain.transfer.TransferFile(
                    "f1", "a.bin", 1L, "", "application/octet-stream",
                ),
                status = TransferStatus.COMPLETED,
                createdAtMs = index.toLong() + 1,
            )
            store.upsert(TransferTaskStore.Entry(task, null))
        }
        assertEquals(TransferTaskStore.MAX_PERSISTED_TASKS, store.count())
    }
}
