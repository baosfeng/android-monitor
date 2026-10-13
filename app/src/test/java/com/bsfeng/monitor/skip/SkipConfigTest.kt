package com.bsfeng.monitor.skip

import android.content.Context
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

/**
 * [SkipConfig] 单元测试（Robolectric）。
 *
 * 覆盖监控窗口（P2-2）的默认值、持久化往返与非法值回落，
 * 以及弱网顺延策略 [WindowExtendPolicy] 的默认值、上限算术与关闭条件。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SkipConfigTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = RuntimeEnvironment.getApplication() as Context
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        SkipConfig.init(context)
    }

    @After
    fun tearDown() {
        // 静态单例跨用例共享，恢复默认避免污染其它测试
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        SkipConfig.init(context)
    }

    // ==================== 正常路径 ====================

    @Test
    fun `未配置时使用默认监控窗口`() {
        assertEquals(SkipConfig.DEFAULT_WATCH_WINDOW_MS, SkipConfig.watchWindowMs)
        assertTrue(SkipConfig.WINDOW_OPTIONS_MS.contains(SkipConfig.DEFAULT_WATCH_WINDOW_MS))
    }

    @Test
    fun `设置后重新读取仍是所选窗口`() {
        SkipConfig.setWatchWindowMs(15_000L)
        assertEquals(15_000L, SkipConfig.watchWindowMs)

        SkipConfig.init(context)
        assertEquals("配置必须持久化", 15_000L, SkipConfig.watchWindowMs)
    }

    @Test
    fun `所有可选窗口都能保存且不被篡改`() {
        SkipConfig.WINDOW_OPTIONS_MS.forEach { option ->
            SkipConfig.setWatchWindowMs(option)
            SkipConfig.init(context)
            assertEquals(option, SkipConfig.watchWindowMs)
        }
    }

    // ==================== 边界条件 ====================

    @Test
    fun `窗口取值为首尾可选值时正常`() {
        SkipConfig.setWatchWindowMs(SkipConfig.WINDOW_OPTIONS_MS.first())
        assertEquals(5_000L, SkipConfig.watchWindowMs)
        SkipConfig.setWatchWindowMs(SkipConfig.WINDOW_OPTIONS_MS.last())
        assertEquals(15_000L, SkipConfig.watchWindowMs)
    }

    // ==================== 异常场景 ====================

    @Test
    fun `非法取值一律回落默认`() {
        listOf(0L, -1L, 1L, 9_999L, 10_001L, 60_000L, Long.MAX_VALUE).forEach { illegal ->
            assertEquals("非法值 " + illegal + " 应回落默认", SkipConfig.DEFAULT_WATCH_WINDOW_MS, SkipConfig.normalize(illegal))
        }
    }

    @Test
    fun `手改 prefs 成非法值后读取回落默认`() {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong("watch_window_ms", 999_999L).commit()

        SkipConfig.init(context)
        assertEquals(SkipConfig.DEFAULT_WATCH_WINDOW_MS, SkipConfig.watchWindowMs)
    }

    // ==================== 弱网顺延策略（P2-2 增强） ====================

    @Test
    fun `顺延参数默认值符合策略`() {
        val policy = WindowExtendPolicy()
        assertEquals(2_000L, SkipConfig.WINDOW_EXTEND_STEP_MS)
        assertEquals(2, SkipConfig.WINDOW_MAX_EXTENSIONS)
        assertEquals(1_000L, SkipConfig.WINDOW_STILL_RENDERING_MS)
        assertEquals(1_500L, SkipConfig.WINDOW_EXTEND_MAX_SCAN_GAP_MS)
        assertEquals(4_000L, SkipConfig.WINDOW_MAX_EXTENSION_MS)

        assertEquals(SkipConfig.WINDOW_EXTEND_STEP_MS, policy.stepMs)
        assertEquals(SkipConfig.WINDOW_MAX_EXTENSIONS, policy.maxExtensions)
        assertEquals(SkipConfig.WINDOW_STILL_RENDERING_MS, policy.stillRenderingMs)
        assertEquals(SkipConfig.WINDOW_EXTEND_MAX_SCAN_GAP_MS, policy.maxScanGapMs)
        assertEquals(SkipConfig.WINDOW_MAX_EXTENSION_MS, policy.maxExtensionMs)
        assertTrue("默认应开启弱网顺延", policy.enabled)
    }

    @Test
    fun `顺延上限保证最长窗口不超过配置窗口的两倍`() {
        val policy = WindowExtendPolicy()
        SkipConfig.WINDOW_OPTIONS_MS.forEach { window ->
            val total = policy.maxTotalWindowMs(window)
            assertEquals("最长窗口 = 基础窗口 + 顺延上限", window + SkipConfig.WINDOW_MAX_EXTENSION_MS, total)
            assertTrue(
                "窗口 " + window + "ms 顺延后为 " + total + "ms，不得达到 2 倍（否则误触风险过高）",
                total < window * 2
            )
        }
    }

    @Test
    fun `顺延参数可注入替换步长与上限`() {
        val policy = WindowExtendPolicy(stepMs = 500L, maxExtensions = 3, stillRenderingMs = 200L, maxScanGapMs = 400L)
        assertTrue(policy.enabled)
        assertEquals(1_500L, policy.maxExtensionMs)
        assertEquals(2_500L, policy.maxTotalWindowMs(1_000L))
        assertEquals(200L, policy.stillRenderingMs)
        assertEquals(400L, policy.maxScanGapMs)
    }

    @Test
    fun `步长或次数非正数时视为关闭顺延`() {
        listOf(
            WindowExtendPolicy(maxExtensions = 0),
            WindowExtendPolicy(maxExtensions = -1),
            WindowExtendPolicy(stepMs = 0L),
            WindowExtendPolicy(stepMs = -1_000L)
        ).forEach { policy ->
            assertFalse(policy.toString() + " 应视为关闭顺延", policy.enabled)
            assertEquals(policy.toString() + " 关闭时不得额外延长", 0L, policy.maxExtensionMs)
            assertEquals(5_000L, policy.maxTotalWindowMs(5_000L))
        }
    }

    private companion object {
        const val PREFS_NAME = "skip_config"
    }
}
