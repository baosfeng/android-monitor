package com.bsfeng.monitor.transfer.service

import android.content.Context
import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import com.bsfeng.monitor.domain.transfer.TransferFile
import com.bsfeng.monitor.domain.transfer.TransferStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 失败传输的分片清理（R15）。
 *
 * 回归点：只有成功落盘与用户主动取消/拒绝才删 `.part`；「确认超时 / 长时间无进展」
 * 走 failSession → FAILED，分片留在 cacheDir/transfer 永久堆积，UI 又没有清理入口。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferFailPartCleanupTest {

    private lateinit var context: Context
    private lateinit var engine: TransferEngine

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        engine = TransferEngine.createForTesting(context)
    }

    @After
    fun tearDown() {
        engine.stop()
        engine.disposeForTesting()
        File(context.cacheDir, "transfer").deleteRecursively()
    }

    private fun partFile(name: String): File =
        File(engine.downloads.partDir(), name).apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun session(vararg files: Pair<String, File?>): IncomingSession {
        val map = mutableMapOf<String, IncomingFile>()
        files.forEach { (fileId, part) ->
            val file = TransferFile(
                fileId = fileId,
                name = fileId + ".bin",
                size = 3L,
                sha256 = "sha-" + fileId,
                mime = "application/octet-stream",
            )
            map[fileId] = IncomingFile(file).apply { this.part = part }
        }
        return IncomingSession(
            sessionId = "session-1",
            token = "token",
            senderDeviceId = "peer-1",
            senderAlias = "Mac",
            files = map,
            state = TransferStatus.TRANSFERRING,
            createdAtMs = System.currentTimeMillis(),
        )
    }

    @Test
    fun `超时失败后分片被删除`() {
        val part = partFile("f1.part")
        val target = session("f1" to part)

        engine.timeouts.failSession(target, TransferErrorCode.CONFIRM_TIMEOUT, "长时间无进展")

        assertEquals(TransferStatus.FAILED, target.state)
        assertEquals(TransferErrorCode.CONFIRM_TIMEOUT, target.failureCode)
        assertFalse("失败会话不再续传，分片必须清掉", part.exists())
    }

    @Test
    fun `多文件分片全部清理 空 part 不崩`() {
        val first = partFile("f1.part")
        val second = partFile("f2.part")
        val target = session("f1" to first, "f2" to second, "f3" to null)

        engine.timeouts.failSession(target, TransferErrorCode.CONFIRM_TIMEOUT, "")

        assertFalse(first.exists())
        assertFalse(second.exists())
        assertEquals(TransferStatus.FAILED, target.state)
    }

    @Test
    fun `失败收敛幂等 重复调用不崩且分片仍不存在`() {
        val part = partFile("f1.part")
        val target = session("f1" to part)

        engine.timeouts.failSession(target, TransferErrorCode.CONFIRM_TIMEOUT, "超时")
        engine.timeouts.failSession(target, TransferErrorCode.CONFIRM_TIMEOUT, "超时")

        assertEquals(TransferStatus.FAILED, target.state)
        assertFalse(part.exists())
        assertTrue(engine.downloads.partDir().listFiles().orEmpty().isEmpty())
    }
}
