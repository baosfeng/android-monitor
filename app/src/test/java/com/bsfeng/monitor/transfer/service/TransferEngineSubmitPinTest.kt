package com.bsfeng.monitor.transfer.service

import android.content.Context
import android.net.Uri
import com.bsfeng.monitor.domain.transfer.DeviceTypes
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.transfer.transport.MiniHttpClient
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
 * 发送端 PIN 流程（R1）：输完 PIN 必须真的再发一次 `POST /offer`。
 *
 * 回归的缺陷：`TransferSendCoordinator` 里另有一个同名的 `pendingSend` 字段，
 * 写入点写的是 `engine.pendingSend`、读取点读的是本类字段，而本类字段全仓没有写入点 →
 * `submitPin` 首行的空值提前返回恒成立，发一次 offer 之后彻底静默（只能等 60 秒超时）。
 *
 * 断言口径：用只记录请求的 [RecordingClient] 替身（不发真实网络），
 * 数「发送端到底发了几次 offer、请求体里有没有 pin」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferEngineSubmitPinTest {

    /** 只记录、不落网的 HTTP 替身：每次 offer 都回 pending，避免进入上传分支。 */
    private class RecordingClient : MiniHttpClient() {
        val posts = mutableListOf<Pair<String, String>>()

        override fun postJson(
            url: String,
            body: String,
            headers: Map<String, String>,
        ): HttpResponse {
            posts += url to body
            val data = JSONObject().apply {
                put("sessionId", "sess-1")
                put("token", "tok-1")
                put("state", "pending")
                put("accepted", JSONArray())
            }
            val payload = JSONObject().apply {
                put("code", 0)
                put("message", "成功")
                put("data", data)
            }.toString()
            return HttpResponse(200, payload)
        }
    }

    private lateinit var context: Context
    private var engine: TransferEngine? = null

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
        engine?.stop()
        // 测试结束即取消进程级 scope：排队中的 persistTask 不能在下一个用例里落库
        engine?.disposeForTesting()
        engine = null
        File(context.cacheDir, "transfer").deleteRecursively()
        ShadowStatFs.reset()
        TransferEngine.resetForTesting()
    }

    private fun newEngine(client: MiniHttpClient): TransferEngine =
        TransferEngine.createForTesting(context = context, client = client).also { engine = it }

    private fun peer() = PeerDevice(
        deviceId = "mac-pin",
        alias = "bsfeng 的 Mac",
        deviceType = DeviceTypes.MAC,
        hostAddress = "10.0.0.2",
        port = 53319,
    )

    private fun pendingSend(sessionId: String = "sess-1") = PendingSend(
        peer = peer(),
        sources = listOf(
            SendSource(
                uri = Uri.parse("content://demo/f1"),
                name = "a.bin",
                size = 10L,
                mime = "application/octet-stream",
                sha256 = "sha-1",
            ),
        ),
        sessionId = sessionId,
    )

    private fun await(timeoutMs: Long = 2_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Test
    fun `submitPin 会发出第二次 offer 并带上 pin`() {
        val client = RecordingClient()
        val transferEngine = newEngine(client)
        transferEngine.pendingSend = pendingSend()

        transferEngine.submitPin("1234")

        assertTrue("必须真的发出 offer，否则 PIN 流程静默", await { client.posts.size == 1 })
        val (url, body) = client.posts.single()
        assertTrue("应打 /offer 路由：$url", url.endsWith("/offer"))
        assertEquals("1234", JSONObject(body).optString("pin"))
    }

    @Test
    fun `没有待确认发送时 submitPin 不发请求`() {
        val client = RecordingClient()
        val transferEngine = newEngine(client)
        transferEngine.pendingSend = null

        transferEngine.submitPin("1234")

        Thread.sleep(200)
        assertEquals(0, client.posts.size)
    }

    @Test
    fun `PIN 仍不正确时保留待确认状态等待重输`() {
        val client = RecordingClient()
        val transferEngine = newEngine(client)
        transferEngine.pendingSend = pendingSend()

        transferEngine.submitPin("1234")

        assertTrue(await { client.posts.size == 1 })
        // 对端仍回 pending：不能把 pendingSend 清掉，否则用户没有第二次输入的入口
        assertTrue("待确认的发送必须保留", transferEngine.pendingSend != null)
    }
}