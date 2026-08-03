package com.bsfeng.monitor.skip

import android.content.Context
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.domain.model.MatchRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

/**
 * SkipRuleEngine 单元测试（Robolectric）。
 *
 * 重点覆盖 matchDelay 延迟任务的执行与清理（修复：停止/切包后不得误触）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkipRuleEngineTest {

    private lateinit var engine: SkipRuleEngine

    /** 虚拟时钟（注入 SkipRuleEngine，控制监控超时/事件节流判断） */
    private var virtualClockMs = 0L

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication() as Context
        engine = SkipRuleEngine(SkipRuleManager(context))
        virtualClockMs = 0L
        engine.currentTimeMillis = { virtualClockMs }
    }

    private fun matchRule(matchDelay: Long = 0L, matches: List<String> = listOf("[text=\"跳过\"]")) =
        MatchRule(matchDelay = matchDelay, actionCd = 0L, matches = matches)

    /** 推进主线程虚拟时钟 + 引擎时钟 */
    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
        virtualClockMs += ms
    }

    @Test
    fun `matchDelay 到期后执行延迟匹配`() {
        var providerCalled = false
        engine.setRootNodeProvider {
            providerCalled = true
            null
        }

        // rootNode 必须非空才会注册延迟任务(executeRule 对 null 根提前返回)
        engine.executeRule("r1", matchRule(matchDelay = 500), AccessibilityNodeInfo.obtain())
        assertFalse("延迟任务不应立即执行", providerCalled)

        advance(600)
        assertTrue("延迟任务应在到期后执行", providerCalled)
    }

    @Test
    fun `stopWatching 清除挂起的延迟任务`() {
        var providerCalled = false
        engine.setRootNodeProvider {
            providerCalled = true
            null
        }

        engine.executeRule("r1", matchRule(matchDelay = 5000), AccessibilityNodeInfo.obtain())
        engine.stopWatching()

        advance(10_000)
        assertFalse("停止监控后延迟任务不得执行", providerCalled)
    }

    @Test
    fun `新延迟任务替换旧的挂起任务`() {
        var providerCalled = false
        engine.setRootNodeProvider {
            providerCalled = true
            null
        }

        engine.executeRule("r1", matchRule(matchDelay = 5000), AccessibilityNodeInfo.obtain())
        engine.executeRule("r2", matchRule(matchDelay = 10_000), AccessibilityNodeInfo.obtain())

        advance(6_000)
        assertFalse("被替换的旧任务不得执行", providerCalled)

        advance(6_000)
        assertTrue("新任务应在到期后执行", providerCalled)
    }

    @Test
    fun `updateCooldown 后进入冷却`() {
        val rule = matchRule().copy(actionCd = 30_000L)
        assertFalse(engine.isOnCooldown(rule))
        engine.updateCooldown(rule)
        assertTrue(engine.isOnCooldown(rule))
    }

    @Test
    fun `actionCd 为 0 时不进入冷却`() {
        val rule = matchRule()  // actionCd = 0
        engine.updateCooldown(rule)
        // actionCd=0 → 无冷却,isOnCooldown 始终 false
        assertFalse(engine.isOnCooldown(rule))
    }

    // ========== CONTENT_CHANGED 事件驱动扫描 ==========

    /**
     * 启动监控：rootNodeProvider 返回 null，performWatchScan 走最短路径
     * （freshRoot 为 null → 重新安排下一次扫描），以 provider 调用次数观测扫描触发。
     */
    private fun startWatchingWithNullProvider(): Int {
        providerCallCount = 0
        engine.setRootNodeProvider {
            providerCallCount++
            null
        }
        engine.startWatching("com.test.app", "com.test.app.SplashActivity")
        advance(0)  // 执行 startWatching post 的首次扫描
        return providerCallCount
    }

    @Test
    fun `监控中收到 CONTENT_CHANGED 立即触发扫描`() {
        val callsBefore = startWatchingWithNullProvider()
        assertTrue("startWatching 应立即触发一次扫描", callsBefore >= 1)

        engine.onContentChanged("com.test.app")
        assertTrue("CONTENT_CHANGED 应触发一次新扫描", providerCalls() > callsBefore)
    }

    @Test
    fun `非监控包名的 CONTENT_CHANGED 被忽略`() {
        startWatchingWithNullProvider()
        val callsBefore = providerCalls()

        engine.onContentChanged("com.other.app")
        assertFalse("非监控包名事件不得触发扫描", providerCalls() > callsBefore)
    }

    @Test
    fun `CONTENT_CHANGED 节流_节流窗口内重复事件不触发`() {
        startWatchingWithNullProvider()
        engine.onContentChanged("com.test.app")
        val callsAfterFirst = providerCalls()

        // 时间未推进，紧接的第二次事件应被节流拦截
        engine.onContentChanged("com.test.app")
        assertEquals("节流窗口内重复事件不得触发扫描", callsAfterFirst, providerCalls())

        // 推进 50ms（仍 < 100ms 节流窗口），仍应拦截
        advance(50)
        engine.onContentChanged("com.test.app")
        assertEquals("50ms 后仍处于节流窗口，不得触发扫描", callsAfterFirst, providerCalls())

        // 再推进 60ms（累计 110ms > 100ms），应触发
        advance(60)
        engine.onContentChanged("com.test.app")
        assertTrue("超过节流窗口后事件应触发扫描", providerCalls() > callsAfterFirst)
    }

    @Test
    fun `监控超时后 CONTENT_CHANGED 停止监控且不再扫描`() {
        startWatchingWithNullProvider()

        // 推进超过 WATCH_TIMEOUT_MS(5s)，期间挂起的扫描任务会自行触发并超时停止
        advance(5_100)
        val callsAtTimeout = providerCalls()

        engine.onContentChanged("com.test.app")
        advance(1_000)
        assertEquals("超时停止监控后事件不得再触发扫描", callsAtTimeout, providerCalls())
    }

    @Test
    fun `stopWatching 后 CONTENT_CHANGED 被忽略`() {
        startWatchingWithNullProvider()
        engine.stopWatching()
        val callsBefore = providerCalls()

        engine.onContentChanged("com.test.app")
        advance(1_000)
        assertEquals("停止监控后事件不得触发扫描", callsBefore, providerCalls())
    }

    private fun providerCalls(): Int = providerCallCount

    private var providerCallCount: Int = 0

}
