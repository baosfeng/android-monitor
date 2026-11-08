package com.bsfeng.monitor.backup

import android.content.Context
import android.os.Looper
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 还原准备阶段的线程口径（R4：整段跑在主线程，几十 MB 会卡死界面）。
 *
 * `rememberCoroutineScope()` 给的是 Main 调度器，而 `prepareRestore` 内部是阻塞 IO
 * （SAF 流 copyTo 落盘 → 整包 pre-restore 备份 → 解包 + 全量校验），不自己切调度器。
 * 这里用「注入的 openStream 在哪个线程被执行」作探针——它就是 prepareRestore 的第一段 IO。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class RestorePreparationTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun `准备阶段不在主线程执行磁盘与校验`() = runBlocking {
        var observed: Thread? = null

        val outcome = prepareRestoreOnIo(context) {
            observed = Thread.currentThread()
            // 非 zip 字节：走到 verify 就被拒，不会触发 pre-restore 备份（用例只关心线程）
            ByteArrayInputStream("not-a-zip".toByteArray())
        }

        val thread = requireNotNull(observed) { "准备阶段必须真的读了流" }
        assertFalse(
            "还原准备跑在主线程上会把界面冻住（实际线程 " + thread.name + "）",
            thread === Looper.getMainLooper().thread,
        )
        assertTrue("内容不是备份包，应被拒绝：$outcome", outcome is RestoreOutcome.Rejected)
    }

    @Test
    fun `拒绝原因可展示 不抛异常`() = runBlocking {
        val outcome = prepareRestoreOnIo(context) { null }

        assertTrue(outcome is RestoreOutcome.Rejected)
        assertTrue(
            "空流要给用户可读原因",
            (outcome as RestoreOutcome.Rejected).reason.isNotBlank(),
        )
    }
}
