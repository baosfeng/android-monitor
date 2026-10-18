package com.bsfeng.monitor.transfer.service

import android.content.Context
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs
import java.io.File

/**
 * 重启后续传偏移测试（Robolectric，真实文件 + 真实引擎会话）。
 *
 * 缺陷背景：接收端进程被系统回收重启后，内存里 `received = 0`，但磁盘上的 `.part` 还有部分字节。
 * 若 `/offer` 按内存值应答 `offset = 0`，发送端会从头传、接收端严格校验随后返回 4003，
 * 协议 §6 的「断点续传」在重启场景下失效。修复口径：`accepted[].offset` 一律取 `.part` 实际大小，
 * 与 Mac 端 `UploadPlanner.resumableOffset(partSize:expectedSize:)` 同源。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferEngineOfferResumeTest {

    private lateinit var context: Context
    private lateinit var engine: TransferEngine

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(TransferSettings.PREFS_NAME, 0).edit().clear().commit()
        File(context.cacheDir, "transfer").deleteRecursively()
        // Robolectric 的 StatFs 默认 0 可用空间，会让 offer 直接走 4004；这里登记 4GB 可用
        ShadowStatFs.registerStats(context.cacheDir, 4096, 1_048_576, 1_048_576)
        // Robolectric 每个用例新建 Application：先复位单例，避免复用上一个用例的 context
        TransferEngine.resetForTesting()
        engine = TransferEngine.getInstance(context)
    }

    @After
    fun tearDown() {
        File(context.cacheDir, "transfer").deleteRecursively()
        ShadowStatFs.reset()
        TransferEngine.resetForTesting()
    }

    private fun partFile(fileId: String): File =
        File(File(context.cacheDir, "transfer").apply { mkdirs() }, fileId + ".part")

    private fun offerBody(
        senderDeviceId: String,
        fileId: String = "f1",
        size: Long = 10L,
        sha256: String = "sha-$senderDeviceId-$fileId",
        pin: String? = null,
    ): JSONObject = JSONObject().apply {
        put("senderDeviceId", senderDeviceId)
        put("senderAlias", "Mac")
        put(
            "files",
            JSONArray().apply {
                put(
                    JSONObject().apply {
                        put("fileId", fileId)
                        put("name", "a.bin")
                        put("size", size)
                        put("sha256", sha256)
                        put("mime", "application/octet-stream")
                    },
                )
            },
        )
        if (pin != null) put("pin", pin)
    }

    /** 首次 offer 得到 pending（生成 PIN），带 PIN 重提得到 approved，返回 `accepted[]`。 */
    private fun approvedAccepted(body: JSONObject): JSONArray {
        val first = runBlocking { engine.offer(body, null) }
        assertEquals(0, first.code)
        val pin = engine.pinPrompt.value?.pin
        requireNotNull(pin) { "未配对时首次 offer 应生成 PIN" }

        val second = body.apply { put("pin", pin) }
        val approved = runBlocking { engine.offer(second, null) }
        assertEquals(0, approved.code)
        assertEquals("approved", approved.string("state"))
        return approved.data.getJSONArray("accepted")
    }

    @Test
    fun `进程重启后 part 有 5 字节时 offer 的 accepted offset 返回 5`() {
        // 模拟「上次传到 5 字节、进程被杀」：磁盘留 .part，内存会话从零开始
        partFile("f1").writeBytes(ByteArray(5) { 0x41 })

        val accepted = approvedAccepted(offerBody(senderDeviceId = "mac-resume-5"))

        assertEquals(1, accepted.length())
        assertEquals("f1", accepted.getJSONObject(0).getString("fileId"))
        assertEquals(5L, accepted.getJSONObject(0).getLong("offset"))
    }

    @Test
    fun `没有 part 文件时 offset 为 0`() {
        val accepted = approvedAccepted(offerBody(senderDeviceId = "mac-resume-0"))
        assertEquals(0L, accepted.getJSONObject(0).getLong("offset"))
    }

    @Test
    fun `part 超过声明大小时按脏数据处理 offset 为 0 并清理`() {
        partFile("f1").writeBytes(ByteArray(20) { 0x42 })

        val accepted = approvedAccepted(offerBody(senderDeviceId = "mac-resume-oversize", size = 10L))

        assertEquals(0L, accepted.getJSONObject(0).getLong("offset"))
        assertFalse("超长 .part 应被清理", partFile("f1").exists())
    }
}
