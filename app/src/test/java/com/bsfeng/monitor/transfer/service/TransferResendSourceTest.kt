package com.bsfeng.monitor.transfer.service

// P1-3 引擎侧回归：重发能力的两条硬要求。
//  1. 源 Uri 要能**从任务记录里拿回来**（发送时登记 → 按方向落库 → 重启回填）；
//  2. 拿不回时给**明确失败**，不静默。
//
// 修复前的根因：落库只取 publishedFiles（只有接收侧写），发送任务的 fileUri 恒 null，
// 于是 resend 无从谈起、canResend 只能恒 false。
//
// 数据用占位值（设备名「Pixel 8」、文件名「IMG_0001.jpg」、Uri authority "test"），
// 不写真实设备信息。

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.domain.transfer.DeviceTypes
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferFile
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.transfer.data.TransferTaskStore
import com.bsfeng.monitor.transfer.transport.StreamCopier
import kotlinx.coroutines.runBlocking
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferResendSourceTest {

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

    private fun device(online: Boolean = true): PeerDevice = PeerDevice(
        deviceId = "peer-1",
        alias = "Pixel 8",
        deviceType = DeviceTypes.ANDROID,
        hostAddress = "192.168.1.x",
        port = 53319,
        online = online,
    )

    private fun task(
        direction: TransferDirection = TransferDirection.SEND,
        status: TransferStatus = TransferStatus.FAILED,
    ): TransferTask = TransferTask(
        sessionId = "session-1",
        peer = device(),
        direction = direction,
        file = TransferFile("file-1", "IMG_0001.jpg", 4L, "0".repeat(64), "image/jpeg"),
        status = status,
        updatedAtMs = 0L,
        createdAtMs = 1L,
    )

    /** 造一个真的能读出内容的 content Uri（Robolectric 的 ContentResolver 支持 file://）。 */
    private fun readableUri(): Uri {
        val file = File(context.cacheDir, "source.jpg").apply { writeBytes(byteArrayOf(1, 2, 3, 4)) }
        return Uri.fromFile(file)
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
    fun `发送任务按方向落库源 Uri 而不是 publishedFiles`() {
        val transferEngine = newEngine()
        val target = task()
        val uri = readableUri()
        transferEngine.sourceFiles[transferEngine.taskKey(target.sessionId, target.file.fileId)] = uri

        transferEngine.upsertTask(target)

        assertTrue("发送任务的源 Uri 必须落库", await { store.loadRecent().singleOrNull()?.fileUri != null })
        assertEquals(uri.toString(), store.loadRecent().single().fileUri)
    }

    @Test
    fun `接收任务仍然落库落盘产物而不是源文件`() {
        val transferEngine = newEngine()
        val target = task(direction = TransferDirection.RECV, status = TransferStatus.COMPLETED)
        val uri = "content://downloads/局域网互传/IMG_0001.jpg".toUri()
        transferEngine.publishedFiles[transferEngine.taskKey(target.sessionId, target.file.fileId)] = uri

        transferEngine.upsertTask(target)

        assertTrue(await { store.loadRecent().singleOrNull()?.fileUri != null })
        assertEquals(uri.toString(), store.loadRecent().single().fileUri)
    }

    @Test
    fun `重启后发送任务的源 Uri 回填到 sourceFiles`() {
        val first = newEngine()
        val target = task()
        val uri = readableUri()
        first.sourceFiles[first.taskKey(target.sessionId, target.file.fileId)] = uri
        first.upsertTask(target)
        assertTrue(await { store.loadRecent().singleOrNull()?.fileUri != null })
        assertEquals(uri.toString(), store.loadRecent().single().fileUri)

        // 模拟进程重启：新实例从库里加载历史
        val restarted = newEngine()
        runBlocking { restarted.loadPersistedHistory() }

        val loaded = restarted.tasks.value.first { it.sessionId == target.sessionId }
        assertNotNull("源 Uri 必须能拿回来（这是重发的前提）", restarted.sourceUriOf(loaded))
        assertEquals(uri.toString(), restarted.sourceUriOf(loaded).toString())
    }

    @Test
    fun `源文件读不出来时给明确失败而不是静默`() {
        val transferEngine = newEngine()
        val target = task()
        // 不登记 / 登记了但文件不存在，都必须走到「源文件已不在本机」
        val reasons = mutableListOf<String>()
        runBlocking {
            transferEngine.sourceUriForResend(target) { reasons += RESEND_BLOCK_SOURCE_GONE }
        }
        assertEquals(listOf(RESEND_BLOCK_SOURCE_GONE), reasons)
    }

    @Test
    fun `接收方向重发直接拒绝`() {
        val transferEngine = newEngine()
        val recv = task(direction = TransferDirection.RECV)
        val reasons = mutableListOf<String>()
        transferEngine.sourceUriForResend(recv) { reasons += RESEND_BLOCK_SOURCE_GONE }
        assertEquals(1, reasons.size)
        assertNull(transferEngine.sourceUriForResend(recv) {})
    }

    @Test
    fun `对方不在线时拿不到重发目标`() {
        val offline = device(online = false)
        val target = task().copy(peer = offline)
        assertNull("离线设备不能作为重发目标", resendPeerFor(target, listOf(offline)))
        assertNull("发现列表里没有、快照也离线 → 拿不到目标", resendPeerFor(task().copy(peer = offline), emptyList()))
    }

    @Test
    fun `对方在线时优先用当前发现到的设备信息`() {
        val stale = device().copy(hostAddress = "10.0.0.9", port = 1)
        val live = device().copy(hostAddress = "192.168.1.y", port = 53320)
        val chosen = resendPeerFor(task().copy(peer = stale), listOf(live))
        assertEquals("要用当前的 IP / 端口，不是任务里的旧快照", 53320, chosen?.port)
    }
}
