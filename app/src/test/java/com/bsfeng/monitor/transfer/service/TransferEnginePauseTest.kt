package com.bsfeng.monitor.transfer.service

import android.content.Context
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
 * 通知「暂停 5 分钟」的引擎级测试（Robolectric）。
 *
 * 暂停期间 `POST /offer` 直接回 `denied`（不建会话、不弹 PIN）；到期或手动恢复后回到正常流程。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferEnginePauseTest {

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

    private fun offerBody(senderDeviceId: String): JSONObject = JSONObject().apply {
        put("senderDeviceId", senderDeviceId)
        put("senderAlias", "Mac")
        put(
            "files",
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("fileId", "f1")
                        put("name", "a.bin")
                        put("size", 1024L)
                        put("sha256", "sha-$senderDeviceId")
                        put("mime", "application/octet-stream")
                    },
                )
            },
        )
    }

    private fun offer(senderDeviceId: String) =
        runBlocking { engine.offer(offerBody(senderDeviceId), null) }

    @Test
    fun `暂停窗口内 offer 直接拒绝且不弹 PIN`() {
        engine.pauseOffers()
        assertTrue(engine.isOffersPaused())

        val response = offer("mac-paused")

        assertEquals(0, response.code)
        assertEquals("denied", response.string("state"))
        assertEquals(0, response.data.getJSONArray("accepted").length())
        assertEquals(null, engine.pinPrompt.value)
    }

    @Test
    fun `暂停到期后恢复正常流程`() {
        // 暂停 5 分钟，但到期时刻已过
        engine.settings().pauseOffers(minutes = 5, nowMs = System.currentTimeMillis() - 6 * 60_000L)
        assertTrue(!engine.isOffersPaused())

        val response = offer("mac-after-pause")

        assertEquals("pending", response.string("state"))
    }

    @Test
    fun `手动恢复后立即可接收`() {
        engine.pauseOffers()
        engine.resumeOffers()
        assertTrue(!engine.isOffersPaused())

        assertEquals("pending", offer("mac-resumed").string("state"))
    }

    @Test
    fun `暂停期间不登记会话`() {
        engine.pauseOffers()
        offer("mac-no-session")
        // 暂停拒绝不应产生任务行
        assertEquals(0, engine.tasks.value.size)
    }
}
