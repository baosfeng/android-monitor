package com.bsfeng.monitor.skip

import android.content.Context
import android.os.Looper
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.domain.model.MatchRule
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

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication() as Context
        engine = SkipRuleEngine(SkipRuleManager(context))
    }

    private fun matchRule(matchDelay: Long = 0L, matches: List<String> = listOf("[text=\"跳过\"]")) =
        MatchRule(matchDelay = matchDelay, actionCd = 0L, matches = matches)

    /** 推进主线程虚拟时钟 */
    private fun advance(ms: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
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
}
