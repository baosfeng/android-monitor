package com.bsfeng.monitor.transfer.service

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File

/**
 * 免确认策略的引擎级测试（Robolectric + 真实会话）。
 *
 * 覆盖两条**或**关系：
 * 1. 免配对窗口内 → 未配对 offer 直接 `approved`（不弹 PIN）；窗口过期 → 回到 `pending`；
 * 2. 体积阈值：本次 offer 总大小 ≤ 阈值 → `approved`；超阈值 → `pending`；阈值 0 = 一律 PIN。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferEngineAutoApproveTest {

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

    private fun offerBody(senderDeviceId: String, size: Long): JSONObject = JSONObject().apply {
        put("senderDeviceId", senderDeviceId)
        put("senderAlias", "Mac")
        put(
            "files",
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("fileId", "f1")
                        put("name", "a.bin")
                        put("size", size)
                        put("sha256", "sha-$senderDeviceId-$size")
                        put("mime", "application/octet-stream")
                    },
                )
            },
        )
    }

    private fun offer(senderDeviceId: String, size: Long): com.bsfeng.monitor.transfer.transport.ApiResponse =
        runBlocking { engine.offer(offerBody(senderDeviceId, size), null) }

    @Test
    fun `免配对窗口内未配对 offer 直接 approved`() {
        engine.settings().startOpenPairingWindow()
        val response = offer("mac-window", 1024L)

        assertEquals(0, response.code)
        assertEquals("approved", response.string("state"))
        assertEquals(1, response.data.getJSONArray("accepted").length())
        // 免确认同时建立配对，便于后续免 PIN
        assertEquals(true, engine.settings().pairingKey("mac-window").isNullOrBlank().not())
    }

    @Test
    fun `免配对窗口过期后回到 pending`() {
        // 10 分钟窗口，但到期时刻已过
        engine.settings().startOpenPairingWindow(
            nowMs = System.currentTimeMillis() - 11 * 60_000L,
            minutes = 10,
        )
        val response = offer("mac-window-expired", 1024L)

        assertEquals("pending", response.string("state"))
        assertEquals(0, response.data.getJSONArray("accepted").length())
    }

    @Test
    fun `文件总大小不超过阈值时免确认`() {
        engine.settings().autoApproveThresholdMb = 10
        val response = offer("mac-small", 5L * 1024 * 1024)

        assertEquals("approved", response.string("state"))
    }

    @Test
    fun `超过阈值仍需 PIN`() {
        engine.settings().autoApproveThresholdMb = 10
        val response = offer("mac-big", 11L * 1024 * 1024)

        assertEquals("pending", response.string("state"))
    }

    @Test
    fun `阈值关闭时默认需要 PIN`() {
        engine.settings().autoApproveThresholdMb = 0
        val response = offer("mac-default", 1L)

        assertEquals("pending", response.string("state"))
    }
}
