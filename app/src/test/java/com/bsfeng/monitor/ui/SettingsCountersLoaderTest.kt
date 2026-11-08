package com.bsfeng.monitor.ui

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext

/**
 * 设置页取数必须发生在 IO 调度器（A2）。
 *
 * 回归点：旧闭包在 LifecycleResumeEffect / LaunchedEffect 里同步调 `getAllRules()`
 * （读文件 + JSON 解析）与 `listBackups()`（列目录），主线程卡首帧。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SettingsCountersLoaderTest {

    /** 记录「有没有被 withContext 切过来」的调度器；实际执行转给真实 IO。 */
    private class RecordingDispatcher(
        private val delegate: CoroutineDispatcher = Dispatchers.IO,
    ) : CoroutineDispatcher() {
        var dispatched = false
            private set

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched = true
            delegate.dispatch(context, block)
        }
    }

    @Test
    fun `取数整体切到注入的 IO 调度器`() = runBlocking {
        val recorder = RecordingDispatcher()
        val context = RuntimeEnvironment.getApplication()

        val snapshot = loadSettingsCounters(context, recorder)

        assertTrue("getAllRules / listBackups 必须离开主线程执行", recorder.dispatched)
        assertTrue("规则数不应为负", snapshot.ruleCount >= 0)
        assertTrue("订阅源数不应为负", snapshot.subscriptionCount >= 0)
        assertTrue("白名单数不应为负", snapshot.whitelistCount >= 0)
        assertTrue("备份份数不应为负", snapshot.backupCount >= 0)
        assertNotNull(snapshot.backupFrequency)
    }

    @Test
    fun `备份份数与目录实际文件数一致`() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        val snapshot = loadSettingsCounters(context, Dispatchers.IO)
        val expected = com.bsfeng.monitor.backup.BackupManager(context).listBackups().size

        assertTrue(
            "计数来自 BackupManager.listBackups：$snapshot vs $expected",
            snapshot.backupCount == expected,
        )
    }
}
