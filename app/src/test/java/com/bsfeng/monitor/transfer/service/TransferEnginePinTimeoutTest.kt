package com.bsfeng.monitor.transfer.service

import android.content.Context
import com.bsfeng.monitor.domain.transfer.CONFIRM_TIMEOUT_MS
import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.UNKNOWN_PEER_ALIAS
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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
 * PIN 确认超时与别名链路（Robolectric）。
 *
 * - 协议 §4.1：确认超时默认 60s（4007）；超时后 PIN 弹层自动收起，任务转 FAILED 且不播失败音；
 * - 别名：`offer.senderAlias` → `PinPrompt.peerAlias` → 配对弹窗，缺失/空白才回落「未知设备」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferEnginePinTimeoutTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(TransferSettings.PREFS_NAME, 0).edit().clear().commit()
        File(context.cacheDir, "transfer").deleteRecursively()
        ShadowStatFs.registerStats(context.cacheDir, 4096, 1_048_576, 1_048_576)
        TransferEngine.resetForTesting()
    }

    @After
    fun tearDown() {
        File(context.cacheDir, "transfer").deleteRecursively()
        ShadowStatFs.reset()
        TransferEngine.resetForTesting()
    }

    private fun offerBody(deviceId: String, alias: String?): JSONObject = JSONObject().apply {
        put("senderDeviceId", deviceId)
        if (alias != null) put("senderAlias", alias)
        put(
            "files",
            JSONArray().put(
                JSONObject().apply {
                    put("fileId", "f1")
                    put("name", "a.bin")
                    put("size", 10L)
                    put("sha256", "sha-$deviceId")
                    put("mime", "application/octet-stream")
                },
            ),
        )
    }

    @Test
    fun `协议确认超时常量为 60 秒`() {
        assertEquals(60_000L, CONFIRM_TIMEOUT_MS)
    }

    @Test
    fun `确认超时后 PIN 弹层自动消失且任务标记 4007`() = runBlocking {
        val engine = TransferEngine.createForTesting(context, confirmTimeoutMs = 120L)
        val before = System.currentTimeMillis()

        val response = engine.offer(offerBody("mac-timeout", alias = "bsfeng 的 Mac"), null)
        assertEquals(0, response.code)

        val prompt = requireNotNull(engine.pinPrompt.value) { "未配对首轮 offer 应弹 PIN" }
        assertNotNull(prompt.expiresAtMs)
        val deadline = requireNotNull(prompt.expiresAtMs)
        val remaining = deadline - before
        assertTrue("截止时间应接近注入的 120ms，实际 $remaining", remaining in 100L..1_000L)

        // 超时前：弹层仍在
        assertEquals("pending", response.string("state"))

        withTimeout(3_000L) { engine.pinPrompt.first { it == null } }

        val task = engine.tasks.value.single()
        assertEquals(TransferStatus.FAILED, task.status)
        assertEquals(TransferErrorCode.CONFIRM_TIMEOUT, task.errorCode)
        assertEquals("failed", task.message)
        assertNull(engine.pinPrompt.value)
    }

    @Test
    fun `offer 的 senderAlias 原样进入 PIN 弹层`() = runBlocking {
        val engine = TransferEngine.getInstance(context)
        engine.offer(offerBody("mac-alias", alias = "bsfeng 的 Mac"), null)

        assertEquals("bsfeng 的 Mac", engine.pinPrompt.value?.peerAlias)
    }

    @Test
    fun `offer 缺少别名为空白时回落未知设备`() = runBlocking {
        val engine = TransferEngine.getInstance(context)
        engine.offer(offerBody("mac-alias-blank", alias = "   "), null)

        assertEquals(UNKNOWN_PEER_ALIAS, engine.pinPrompt.value?.peerAlias)
    }
}
