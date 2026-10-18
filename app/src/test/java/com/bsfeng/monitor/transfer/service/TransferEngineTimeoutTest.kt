package com.bsfeng.monitor.transfer.service

import android.content.Context
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTaskGroups
import com.bsfeng.monitor.transfer.data.TransferTaskStore
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
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
 * 会话超时 → 任务行收敛为失败（真机 Bug 2：「进行中」里卡着一条已超时的接收任务）。
 *
 * - 已批准但长时间无字节进展 → `FAILED` + 4007，并从「进行中」移出；
 * - 待确认超时 → 同样收敛；
 * - `offerStatus` 触发一次超时检查，对端一查就能看到 `failed`。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferEngineTimeoutTest {

    private lateinit var context: Context
    private var engine: TransferEngine? = null

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(TransferSettings.PREFS_NAME, 0).edit().clear().commit()
        File(context.cacheDir, "transfer").deleteRecursively()
        ShadowStatFs.registerStats(context.cacheDir, 4096, 1_048_576, 1_048_576)
        DatabaseHelper.getInstance(context).writableDatabase
            .execSQL("DELETE FROM ${TransferTaskStore.TABLE}")
    }

    @After
    fun tearDown() {
        engine?.stop()
        engine = null
        File(context.cacheDir, "transfer").deleteRecursively()
        ShadowStatFs.reset()
    }

    private fun newEngine(
        confirmTimeoutMs: Long = 10_000L,
        noProgressTimeoutMs: Long = 200L,
        tickMs: Long = 20L,
    ): TransferEngine = TransferEngine.createForTesting(
        context = context,
        confirmTimeoutMs = confirmTimeoutMs,
        noProgressTimeoutMs = noProgressTimeoutMs,
        tickMs = tickMs,
    ).also { engine = it }

    private fun offerBody(senderDeviceId: String = "mac-timeout"): JSONObject = JSONObject().apply {
        put("senderDeviceId", senderDeviceId)
        put("senderAlias", "Mac")
        put(
            "files",
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("fileId", "f1")
                        put("name", "a.bin")
                        put("size", 23L)
                        put("sha256", "sha-23")
                        put("mime", "application/octet-stream")
                    },
                )
            },
        )
    }

    private fun await(timeoutMs: Long = 2_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(20)
        }
        return condition()
    }

    @Test
    fun `传输中长时间无进展会变为失败并移出进行中`() {
        val transferEngine = newEngine(noProgressTimeoutMs = 120L)
        // 免配对窗口 = 直接 approved，进入 TRANSFERRING 后不再有任何字节进展
        transferEngine.settings().startOpenPairingWindow()
        val approved = runBlocking { transferEngine.offer(offerBody(), null) }
        assertEquals("approved", approved.string("state"))
        val sessionId = approved.string("sessionId")
        assertEquals(TransferStatus.TRANSFERRING, transferEngine.tasks.value.single().status)

        // 轮询 offerStatus（对端就是这么查的）：超时检查会在其中触发
        val failed = await(3_000L) {
            runBlocking { transferEngine.offerStatus(sessionId) }
            transferEngine.tasks.value.single().status == TransferStatus.FAILED
        }
        assertTrue("任务应在超时后变为失败", failed)

        val task = transferEngine.tasks.value.single()
        assertEquals(TransferErrorCode.CONFIRM_TIMEOUT, task.errorCode)
        assertTrue(task.message.contains("无进展"))
        assertEquals(0, TransferTaskGroups.activeCount(transferEngine.tasks.value))
    }

    @Test
    fun `待确认超时同样收敛为失败且不留在进行中`() {
        val transferEngine = newEngine(confirmTimeoutMs = 80L)
        // 未配对、无窗口 → pending（弹 PIN）
        val pending = runBlocking { transferEngine.offer(offerBody("mac-pending"), null) }
        assertEquals("pending", pending.string("state"))
        val sessionId = pending.string("sessionId")

        val failed = await(3_000L) {
            runBlocking { transferEngine.offerStatus(sessionId) }
            transferEngine.tasks.value.single().status == TransferStatus.FAILED
        }
        assertTrue("待确认超时应变为失败", failed)
        assertEquals(TransferErrorCode.CONFIRM_TIMEOUT, transferEngine.tasks.value.single().errorCode)
        assertEquals(0, TransferTaskGroups.activeCount(transferEngine.tasks.value))
    }
}
