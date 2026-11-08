package com.bsfeng.monitor.transfer.service

import android.content.Context
import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import com.bsfeng.monitor.transfer.policy.InboundLimits
import com.bsfeng.monitor.transfer.transport.StreamCopier
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
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
 * 接收侧入站防护的引擎级测试（SEC-04 / SEC-05，Robolectric）。
 *
 * - SEC-04：同一来源 60 秒内第 4 次**新建会话**的 offer 被拒；未确认会话到 5 条后新 offer 被拒；
 *   而「同一会话的 PIN 重输 / 断线重提」不受限（那是复用，不是新建）。
 * - SEC-05：`/offer/status` 与 `/cancel` 必须带 `X-Transfer-Token`，`/cancel` 还要 sender 匹配，
 *   否则同网段嗅到 16 位 sessionId 就能读进度、更能把 `.part` 删光。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferEngineInboundGuardTest {

    private lateinit var context: Context
    private lateinit var engine: TransferEngine

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(TransferSettings.PREFS_NAME, 0).edit().clear().commit()
        File(context.cacheDir, "transfer").deleteRecursively()
        ShadowStatFs.registerStats(context.cacheDir, 4096, 1_048_576, 1_048_576)
        TransferEngine.resetForTesting()
        engine = TransferEngine.getInstance(context)
    }

    @After
    fun tearDown() {
        File(context.cacheDir, "transfer").deleteRecursively()
        ShadowStatFs.reset()
        TransferEngine.resetForTesting()
    }

    private fun offerBody(
        senderDeviceId: String,
        sha256: String = "sha-$senderDeviceId",
        size: Long = 10L,
        pin: String? = null,
        sessionId: String? = null,
    ): JSONObject = JSONObject().apply {
        put("senderDeviceId", senderDeviceId)
        put("senderAlias", "Mac")
        if (sessionId != null) put("sessionId", sessionId)
        put(
            "files",
            JSONArray().put(
                JSONObject().apply {
                    put("fileId", "f1")
                    put("name", "a.bin")
                    put("size", size)
                    put("sha256", sha256)
                    put("mime", "application/octet-stream")
                },
            ),
        )
        if (pin != null) put("pin", pin)
    }

    private fun offer(body: JSONObject, source: String = "10.0.0.2"): com.bsfeng.monitor.transfer.transport.ApiResponse =
        runBlocking { engine.offer(body, null, source) }

    // ───────── SEC-04 · 每来源冷却窗 ─────────

    @Test
    fun `同一来源第 4 次新建会话的 offer 被拒且回 4006`() {
        repeat(InboundLimits.OFFER_MAX_PER_WINDOW) { index ->
            val response = offer(offerBody("mac-rate-$index"))
            assertEquals("前 3 次应放行", 0, response.code)
        }
        val rejected = offer(offerBody("mac-rate-over"))
        assertEquals(TransferErrorCode.USER_DENIED, rejected.code)
        assertEquals("待确认的接收任务应只有前 3 条", 3, engine.tasks.value.size)
    }

    @Test
    fun `不同来源各自有额度`() {
        repeat(InboundLimits.OFFER_MAX_PER_WINDOW) { index ->
            offer(offerBody("mac-src-a-$index"), source = "10.0.0.2")
        }
        assertNotEquals(0, offer(offerBody("mac-src-a-over"), source = "10.0.0.2").code)
        assertEquals(0, offer(offerBody("mac-src-b"), source = "10.0.0.3").code)
    }

    @Test
    fun `同一会话重提 offer 不受冷却窗限制`() {
        // 首轮：建会话并弹 PIN
        val first = offer(offerBody("mac-pin-retry"))
        assertEquals("pending", first.string("state"))
        val sessionId = first.string("sessionId")
        val pin = requireNotNull(engine.pinPrompt.value?.pin)
        // 连输 5 次错误 PIN：都是**同一会话**的复用，不该被每来源冷却窗挡住
        repeat(4) {
            val wrong = offer(offerBody("mac-pin-retry", pin = "0000", sessionId = sessionId))
            assertEquals("PIN 重输不该被限流拦截：${wrong.message}", 0, wrong.code)
        }
        val ok = offer(offerBody("mac-pin-retry", pin = pin, sessionId = sessionId))
        assertEquals(0, ok.code)
    }

    // ───────── SEC-04 · 未确认会话硬上限 ─────────

    @Test
    fun `未确认会话达到上限后新 offer 被拒`() {
        repeat(InboundLimits.MAX_PENDING_SESSIONS) { index ->
            val response = offer(offerBody("mac-pending-$index"), source = "10.0.1.$index")
            assertEquals("前 ${InboundLimits.MAX_PENDING_SESSIONS} 个应放行", 0, response.code)
        }
        val rejected = offer(offerBody("mac-pending-over"), source = "10.0.1.99")
        assertEquals(TransferErrorCode.USER_DENIED, rejected.code)
        assertEquals(InboundLimits.MAX_PENDING_SESSIONS, engine.tasks.value.size)
    }

    // ───────── SEC-05 · /offer/status 与 /cancel 鉴权 ─────────

    private fun approvedSession(deviceId: String = "mac-auth"): Pair<String, String> {
        engine.settings().startOpenPairingWindow()
        val approved = offer(offerBody(deviceId))
        assertEquals("approved", approved.string("state"))
        return approved.string("sessionId") to approved.string("token")
    }

    @Test
    fun `offerStatus 不带 token 返回 4001`() {
        val (sessionId, _) = approvedSession()
        val noToken = runBlocking { engine.offerStatus(sessionId, null) }
        assertEquals(TransferErrorCode.TOKEN_MISMATCH, noToken.code)
        val wrongToken = runBlocking { engine.offerStatus(sessionId, "guess") }
        assertEquals(TransferErrorCode.TOKEN_MISMATCH, wrongToken.code)
    }

    @Test
    fun `offerStatus 带正确 token 正常返回`() {
        val (sessionId, token) = approvedSession()
        val ok = runBlocking { engine.offerStatus(sessionId, token) }
        assertEquals(0, ok.code)
        assertEquals(sessionId, ok.string("sessionId"))
    }

    @Test
    fun `cancel 不带 token 或 sender 不匹配时返回 4001 且不删分片`() {
        val (sessionId, token) = approvedSession("mac-cancel")
        val part = StreamCopier.partFile(
            File(context.cacheDir, "transfer").apply { mkdirs() },
            "f1",
            "sha-mac-cancel",
        )
        part.writeBytes(ByteArray(4) { 0x41 })

        assertEquals(TransferErrorCode.TOKEN_MISMATCH, runBlocking { engine.cancel(sessionId, null, "mac-cancel") }.code)
        assertEquals(
            TransferErrorCode.TOKEN_MISMATCH,
            runBlocking { engine.cancel(sessionId, token, "other-device") }.code,
        )
        assertTrue("鉴权失败不得删掉 .part", part.exists())
    }

    @Test
    fun `cancel 带正确 token 与 sender 会置取消并删分片`() {
        val (sessionId, token) = approvedSession("mac-cancel-ok")
        val part = StreamCopier.partFile(
            File(context.cacheDir, "transfer").apply { mkdirs() },
            "f1",
            "sha-mac-cancel-ok",
        )
        part.writeBytes(ByteArray(4) { 0x41 })
        // 会话内的分片引用要先落到 incoming.part，cancel 才删得到
        engine.sessions[sessionId]?.files?.get("f1")?.part = part

        val ok = runBlocking { engine.cancel(sessionId, token, "mac-cancel-ok") }
        assertEquals(0, ok.code)
        assertTrue("取消后应删除 .part", !part.exists())
    }
}
