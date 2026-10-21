package com.bsfeng.monitor.ui.foundation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 卡顿检测的纯 JVM 回归：判定口径（[isJankyWindow]）与触发行为（[JankMonitor]）分开钉住。
 *
 * 帧时长直接从 `Choreographer` 的 vsync 时间戳差分出来，所以测试直接喂纳秒时间戳
 * （[JankMonitor.onFrame] 是 internal，同 module 测试可见），不需要 Robolectric。
 */
class JankMonitorTest {

    private class FakeStore(var tier: MotionTier? = null) : MotionTierStore {
        var downgraded = false
        override fun readTier(): MotionTier? = tier
        override fun writeTier(tier: MotionTier) { this.tier = tier }
        override fun readAutoDowngraded(): Boolean = downgraded
        override fun writeAutoDowngraded(downgraded: Boolean) { this.downgraded = downgraded }
    }

    private val frame60 = 1000.0 / 60.0

    /** 按给定每帧耗时（ms）依次喂时间戳。 */
    private fun JankMonitor.feed(frames: List<Double>) {
        var nanos = 1_000_000_000L
        onFrame(nanos)
        frames.forEach { ms ->
            nanos += (ms * 1_000_000).toLong()
            onFrame(nanos)
        }
    }

    // ── 1 · 纯函数：单帧判定 ──

    @Test
    fun `超过两倍刷新间隔才算超标帧`() {
        assertFalse("16.7ms 正常帧", isJankFrame(frame60))
        assertFalse("31ms 未超 33ms", isJankFrame(31.0))
        assertTrue("34ms 超过 2×16.7", isJankFrame(34.0))
        assertTrue("一帧卡 100ms", isJankFrame(100.0))
        // 显式给刷新间隔时口径跟着变（120Hz 屏 8.3ms → 16.7ms 即超标）
        assertTrue(isJankFrame(17.0, refreshIntervalMs = 1000.0 / 120.0))
    }

    @Test
    fun `统计口径是超标帧数比总帧数`() {
        val stats = jankStatsOf(listOf(frame60, frame60, 40.0, 50.0))
        assertEquals(2, stats.overBudgetFrames)
        assertEquals(4, stats.totalFrames)
        assertEquals(0.5f, stats.ratio, 0.0001f)
        assertEquals(JankStats.EMPTY, jankStatsOf(emptyList()))
        assertEquals(0f, JankStats.EMPTY.ratio, 0f)
    }

    // ── 2 · 纯函数：窗口判定 ──

    @Test
    fun `正常序列不判定卡顿`() {
        assertFalse(isJankyWindow(List(JANK_WINDOW_FRAMES) { frame60 }))
        // 偶发单帧长帧（GC）不足以判定
        assertFalse(isJankyWindow(List(JANK_WINDOW_FRAMES) { frame60 }.toMutableList().also { it[10] = 40.0 }))
    }

    @Test
    fun `样本不足时不判定卡顿`() {
        val fewFrames = List(JANK_MIN_SAMPLES - 1) { 40.0 }
        assertTrue("这些帧全是超标帧", jankStatsOf(fewFrames).ratio == 1f)
        assertFalse("样本数不够就不判", isJankyWindow(fewFrames))
    }

    @Test
    fun `持续超标判定卡顿`() {
        // 40% 帧超标 > 15% 阈值
        val frames = List(JANK_WINDOW_FRAMES) { i -> if (i % 10 < 4) 40.0 else frame60 }
        assertTrue(isJankyWindow(frames))
    }

    @Test
    fun `超标占比恰好等于阈值不判定`() {
        // 18/120 = 15%，阈值是严格大于
        val frames = List(JANK_WINDOW_FRAMES) { i -> if (i < 18) 40.0 else frame60 }
        assertEquals(JANK_RATIO_THRESHOLD, jankStatsOf(frames).ratio, 0.0001f)
        assertFalse(isJankyWindow(frames))
    }

    @Test
    fun `只有一帧超标不判定`() {
        val frames = List(JANK_MIN_SAMPLES) { i -> if (i == 0) 100.0 else frame60 }
        assertTrue(jankStatsOf(frames).ratio > 0f)
        assertFalse("单帧长帧不足以代表持续卡顿", isJankyWindow(frames))
    }

    // ── 3 · 触发行为：只降不升、只降一次 ──

    @Test
    fun `正常帧序列不触发降档`() {
        var downgrades = 0
        val config = MotionConfig(FakeStore())
        val monitor = JankMonitor(config = config, onDowngrade = { downgrades++ })

        monitor.feed(List(JANK_WINDOW_FRAMES) { frame60 })

        assertEquals(0, downgrades)
        assertEquals(MotionTier.FULL, config.current())
        assertFalse(config.autoDowngraded.value)
    }

    @Test
    fun `持续卡顿把 FULL 降到 NORMAL 一次并落盘`() {
        var downgrades = 0
        val statsSeen = mutableListOf<JankStats>()
        val store = FakeStore()
        val config = MotionConfig(store)
        val monitor = JankMonitor(
            config = config,
            onDowngrade = { statsSeen += it; downgrades++ },
        )

        monitor.feed(List(JANK_WINDOW_FRAMES) { 40.0 })

        assertEquals("只降一次", 1, downgrades)
        assertEquals(MotionTier.NORMAL, config.current())
        assertEquals(MotionTier.NORMAL, store.tier)
        assertTrue(config.autoDowngraded.value)
        assertEquals(1, statsSeen.size)
        assertTrue("回调里能拿到引发降档的统计", statsSeen[0].ratio > JANK_RATIO_THRESHOLD)
    }

    @Test
    fun `已降级后继续卡顿不再降档也不再回调`() {
        var downgrades = 0
        val config = MotionConfig(FakeStore(tier = MotionTier.NORMAL))
        val monitor = JankMonitor(config = config, onDowngrade = { downgrades++ })

        monitor.feed(List(JANK_WINDOW_FRAMES) { 40.0 })
        monitor.feed(List(JANK_WINDOW_FRAMES) { 40.0 })

        assertEquals(0, downgrades)
        assertEquals(MotionTier.NORMAL, config.current())
    }

    @Test
    fun `REDUCED 档不参与自动降档`() {
        var downgrades = 0
        val config = MotionConfig(FakeStore(tier = MotionTier.REDUCED))
        val monitor = JankMonitor(config = config, onDowngrade = { downgrades++ })

        monitor.feed(List(JANK_WINDOW_FRAMES) { 40.0 })

        assertEquals(0, downgrades)
        assertEquals(MotionTier.REDUCED, config.current())
    }

    @Test
    fun `没有 config 时只统计不触发`() {
        var downgrades = 0
        val monitor = JankMonitor(onDowngrade = { downgrades++ })

        monitor.feed(List(JANK_WINDOW_FRAMES) { 40.0 })

        assertEquals(0, downgrades)
        assertTrue(monitor.stats().ratio > 0f)
    }

    // ── 4 · 采样边界 ──

    @Test
    fun `窗口只保留最近 N 帧`() {
        val monitor = JankMonitor()
        monitor.feed(List(JANK_WINDOW_FRAMES * 2) { frame60 })

        assertEquals("统计窗口封顶 120 帧", JANK_WINDOW_FRAMES, monitor.stats().totalFrames)
    }

    @Test
    fun `空闲间隔不计入样本`() {
        val monitor = JankMonitor()
        monitor.feed(List(JANK_MIN_SAMPLES) { frame60 })
        val before = monitor.stats().totalFrames

        // 切后台 5 秒后回前台：这一帧不算「卡了 5 秒」
        monitor.onFrame(9_999_999_999L)

        assertEquals("空闲帧必须被丢弃", before, monitor.stats().totalFrames)
    }

    @Test
    fun `时间戳回退被丢弃`() {
        val monitor = JankMonitor()
        var nanos = 1_000_000_000L
        monitor.onFrame(nanos)
        nanos += 16_666_667L
        monitor.onFrame(nanos)
        monitor.onFrame(nanos - 5_000_000L) // 回退

        assertEquals(JankStats.EMPTY, monitor.stats())
    }

    @Test
    fun `reset 清空窗口与统计`() {
        val monitor = JankMonitor()
        monitor.feed(List(JANK_MIN_SAMPLES) { frame60 })
        assertTrue(monitor.stats().totalFrames > 0)

        monitor.reset()

        assertEquals(JankStats.EMPTY, monitor.stats())
    }

    @Test
    fun `reset 后重新累计而不是沿用旧样本`() {
        var downgrades = 0
        val config = MotionConfig(FakeStore())
        val monitor = JankMonitor(config = config, onDowngrade = { downgrades++ })

        monitor.feed(List(JANK_WINDOW_FRAMES) { 40.0 })
        assertEquals(1, downgrades)
        monitor.reset()
        monitor.feed(List(JANK_WINDOW_FRAMES) { 40.0 })

        assertEquals("已降级到 NORMAL，第二次不该再降", 1, downgrades)
        assertTrue(monitor.stats().totalFrames > 0)
    }

    @Test
    fun `未调用 start 时 onFrame 不产生任何副作用`() {
        val monitor = JankMonitor()
        assertFalse(monitor.isRunning())
        monitor.feed(List(JANK_MIN_SAMPLES) { frame60 })
        // onFrame 本身是采样入口（供单测直喂），start/stop 只管 Choreographer 回调注册
        assertEquals(JANK_MIN_SAMPLES, monitor.stats().totalFrames)
    }
}
