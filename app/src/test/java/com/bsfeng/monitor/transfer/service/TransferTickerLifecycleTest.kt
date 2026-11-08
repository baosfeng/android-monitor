package com.bsfeng.monitor.transfer.service

import android.content.Context
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureFlags
import kotlinx.coroutines.Job
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

/**
 * 后台 ticker 生命周期（R8）。
 *
 * 回归点：旧实现每次 start 都 `scope.launch { tickerLoop() }`，循环条件却是
 * `while (scope.isActive)`（引擎 scope 进程级永不取消），stop 也不 cancel ——
 * 反复开关后每 5 秒叠一条 ticker。这里钉住 start 幂等、stop 取消、重复开关不叠加。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TransferTickerLifecycleTest {

    private lateinit var context: Context
    private var engine: TransferEngine? = null
    /** 用例开始前的开关状态：结束后原样恢复，避免把 TRANSPORT 强制成 false 影响同批其它类。 */
    private var transportBefore: Boolean = false

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(TransferSettings.PREFS_NAME, 0).edit().clear().commit()
        File(context.cacheDir, "transfer").deleteRecursively()
        transportBefore = FeatureFlags.get(context).isEnabled(Feature.TRANSPORT)
        FeatureFlags.get(context).setEnabled(Feature.TRANSPORT, true)
    }

    @After
    fun tearDown() {
        engine?.stop()
        // 测试结束时必须收掉 ticker 与进程级 scope：只 stop 不 cancel 的话，
        // 排队中的 persistTask / ticker 会在同批其它类里继续跑（共享静态与数据库）。
        engine?.disposeForTesting()
        engine = null
        FeatureFlags.get(context).setEnabled(Feature.TRANSPORT, transportBefore)
        File(context.cacheDir, "transfer").deleteRecursively()
    }

    private fun newEngine(): TransferEngine =
        TransferEngine.createForTesting(context = context, tickMs = 40L).also { engine = it }

    // 5s：全量并行跑时 IO 调度可能被拖慢，判活不能用固定小窗口（曾出现增量为 0 的假红）
    private fun await(timeoutMs: Long = 5_000L, condition: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return true
            Thread.sleep(10)
        }
        return condition()
    }

    @Test
    fun `start 幂等 只保留一条 ticker 句柄`() {
        val e = newEngine()
        assertTrue(e.start() > 0)
        val firstJob = e.tickerJob
        assertNotNull(firstJob)

        e.start()

        assertSame("重复 start 不得再起一条 ticker", firstJob, e.tickerJob)
        assertTrue(await { e.tickerTicks.get() >= 1 })
    }

    @Test
    fun `stop 取消 ticker 且不再回调`() {
        val e = newEngine()
        e.start()
        val job = e.tickerJob
        assertNotNull(job)
        assertTrue(await { e.tickerTicks.get() >= 1 })

        e.stop()

        assertNull("stop 后句柄必须清空", e.tickerJob)
        assertTrue("在途 ticker 必须被取消", job!!.isCancelled)
        val ticksAtStop = e.tickerTicks.get()
        Thread.sleep(160L)
        // 允许 stop 与「已醒来但尚未检查 started」的一拍竞争，最多多 1 次
        assertTrue("stop 后不得继续 40ms 一拍地跑", e.tickerTicks.get() - ticksAtStop <= 1)
    }

    @Test
    fun `连续 5 次开关后只有一条 ticker 在跑`() {
        val e = newEngine()
        val cancelledJobs = mutableListOf<Job>()
        repeat(5) {
            assertTrue(e.start() > 0)
            e.tickerJob?.let { cancelledJobs += it }
            e.stop()
        }
        assertTrue("每次 stop 都应取消对应 ticker", cancelledJobs.all { it.isCancelled })
        assertNull(e.tickerJob)

        assertTrue(e.start() > 0)
        val before = e.tickerTicks.get()
        // 先用「活着」证明 ticker 在跑：并行跑时调度可能慢，不能用固定窗口判活
        assertTrue("ticker 必须能跑起来", await { e.tickerTicks.get() > before })
        val afterLiveness = e.tickerTicks.get()
        Thread.sleep(300L)
        val delta = e.tickerTicks.get() - afterLiveness
        // 40ms 一拍 ≈ 7 次；旧实现 5 条叠加会是 ~37 次
        assertTrue("只应有一条 ticker，300ms 实际增量 " + delta, delta <= 10)
    }
}
