package com.bsfeng.monitor.ui.foundation

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 一帧窗口的卡顿统计，供设置页展示与单测断言。
 *
 * `ratio` 是**超标帧占本窗口的比例**，不是平均帧率：一次长帧会连带丢掉若干 vsync，
 * 按超标帧计数更能表达「用户感到一顿」的频次。
 */
data class JankStats(val overBudgetFrames: Int, val totalFrames: Int) {
    /** 超标帧占比（0..1）；无样本时为 0。 */
    val ratio: Float
        get() = if (totalFrames <= 0) 0f else overBudgetFrames.toFloat() / totalFrames

    companion object {
        val EMPTY = JankStats(0, 0)

        /** 默认刷新间隔：按 60Hz（16.7ms）取值。120Hz 屏沿用此值 = 判定更松，宁可漏判也不误降档。 */
        const val DEFAULT_REFRESH_INTERVAL_MS: Double = 1000.0 / 60.0
    }
}

/** 超标帧判定：一帧耗时超过 **2 倍刷新间隔**（60Hz ≈ 33ms）即算掉帧。 */
const val JANK_FRAME_FACTOR: Double = 2.0

/** 判定窗口：120 帧 ≈ 2s @60Hz，短于此的抖动不触发降档。 */
const val JANK_WINDOW_FRAMES: Int = 120

/** 窗口内至少要有这么多帧才判定，避免冷启动头几帧就把档位降掉。 */
const val JANK_MIN_SAMPLES: Int = 30

/** 超标占比阈值：窗口内超过 15% 的帧掉帧即判定卡顿。 */
const val JANK_RATIO_THRESHOLD: Float = 0.15f

/** 两帧间隔超过这个值视为「切后台 / 锁屏」而非卡顿，这段样本直接丢弃。 */
const val JANK_IDLE_GAP_MS: Double = 1000.0

/** 单帧是否超标（纯函数）。`refreshIntervalMs` 传屏幕刷新间隔，60Hz 即 16.7。 */
fun isJankFrame(
    frameMs: Double,
    refreshIntervalMs: Double = JankStats.DEFAULT_REFRESH_INTERVAL_MS,
): Boolean = frameMs > refreshIntervalMs * JANK_FRAME_FACTOR

/** 一段帧耗时序列 → 统计（纯函数）。 */
fun jankStatsOf(
    frameDurationsMs: List<Double>,
    refreshIntervalMs: Double = JankStats.DEFAULT_REFRESH_INTERVAL_MS,
): JankStats {
    if (frameDurationsMs.isEmpty()) return JankStats.EMPTY
    val over = frameDurationsMs.count { isJankFrame(it, refreshIntervalMs) }
    return JankStats(overBudgetFrames = over, totalFrames = frameDurationsMs.size)
}

/**
 * 是否应判定为卡顿（纯函数，判决口径的唯一实现处）。
 *
 * 三个条件全满足才成立：样本量 ≥ [minSamples]、超标占比 > [thresholdRatio]、超标帧数 ≥ 2
 * （单帧长帧可能只是 GC 或一次系统调度，不足以代表持续卡顿）。
 */
fun isJankyWindow(
    frameDurationsMs: List<Double>,
    refreshIntervalMs: Double = JankStats.DEFAULT_REFRESH_INTERVAL_MS,
    thresholdRatio: Float = JANK_RATIO_THRESHOLD,
    minSamples: Int = JANK_MIN_SAMPLES,
): Boolean {
    if (frameDurationsMs.size < minSamples) return false
    val stats = jankStatsOf(frameDurationsMs, refreshIntervalMs)
    return stats.overBudgetFrames >= 2 && stats.ratio > thresholdRatio
}

/**
 * 卡顿检测：自算 [Choreographer] 帧间隔，卡顿时把动效档位从 [MotionTier.FULL] 降到
 * [MotionTier.NORMAL]（只降不升，一次）。
 *
 * 为什么不用 androidx.metrics：仓库零第三方业务依赖，`Choreographer.FrameCallback` 已能拿到
 * 每个 vsync 的时间戳，够做「超标占比」这一种判定。
 *
 * 用法（跟随 Activity 生命周期）：
 * ```
 * private val jankMonitor = JankMonitor(config = MotionConfig.get())
 * override fun onResume() { super.onResume(); jankMonitor.start() }
 * override fun onPause() { jankMonitor.stop(); super.onPause() }
 * ```
 *
 * 边界：
 * - 回调只在主线程；在别的线程调 [start] / [stop] 会 post 到主线程执行，状态位立即生效。
 * - 空闲间隔（> [JANK_IDLE_GAP_MS]，切后台 / 锁屏）不计入样本，避免回前台被误判。
 * - 已经是 [MotionTier.NORMAL] / [MotionTier.REDUCED] 时不再降档（只降不升）。
 * - [stop] 之后不再有任何回调；统计值保留最后一次结果。
 * - 纯 JVM 单测直接喂 [onFrame] 的时间戳，不依赖 Choreographer 与主 Looper。
 */
class JankMonitor(
    private val config: MotionConfig? = null,
    private val windowFrames: Int = JANK_WINDOW_FRAMES,
    private val ratioThreshold: Float = JANK_RATIO_THRESHOLD,
    private val refreshIntervalMs: Double = JankStats.DEFAULT_REFRESH_INTERVAL_MS,
    private val onDowngrade: (JankStats) -> Unit = {},
) {

    private val mainHandler by lazy { Handler(Looper.getMainLooper()) }
    private val running = AtomicBoolean(false)
    private val samples = ArrayDeque<Double>(windowFrames.coerceAtLeast(1))

    @Volatile
    private var latestStats: JankStats = JankStats.EMPTY
    private var lastFrameNanos = 0L

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running.get()) return
            onFrame(frameTimeNanos)
            if (running.get()) {
                runCatching { Choreographer.getInstance().postFrameCallback(this) }
            }
        }
    }

    /** 开始采样；重复调用无副作用。 */
    fun start() {
        if (!running.compareAndSet(false, true)) return
        if (Looper.myLooper() == Looper.getMainLooper()) attach() else mainHandler.post { if (running.get()) attach() }
    }

    /** 停止采样：注销回调，之后不再有任何回调。 */
    fun stop() {
        if (!running.compareAndSet(true, false)) return
        val detach = Runnable { runCatching { Choreographer.getInstance().removeFrameCallback(frameCallback) } }
        if (Looper.myLooper() == Looper.getMainLooper()) detach.run() else mainHandler.post(detach)
    }

    fun isRunning(): Boolean = running.get()

    /** 当前窗口统计（样本不足 [JANK_MIN_SAMPLES] 时为 [JankStats.EMPTY]）。 */
    fun stats(): JankStats = latestStats

    /** 清空窗口样本（手动改档、切前后台后调用一次，避免旧样本继续累积）。 */
    fun reset() {
        synchronized(samples) {
            samples.clear()
            latestStats = JankStats.EMPTY
        }
        lastFrameNanos = 0L
    }

    private fun attach() {
        lastFrameNanos = 0L
        runCatching { Choreographer.getInstance().postFrameCallback(frameCallback) }
    }

    /** 单帧采样：抽出成 internal，单测直接喂帧时间戳（纳秒），不经过 Choreographer。 */
    internal fun onFrame(frameTimeNanos: Long) {
        val previous = lastFrameNanos
        lastFrameNanos = frameTimeNanos
        if (previous == 0L) return
        val frameMs = (frameTimeNanos - previous) / 1_000_000.0
        if (frameMs <= 0.0 || frameMs > JANK_IDLE_GAP_MS) {
            // 时间戳回退 / 切后台：丢弃这段，并从下一帧重新计时
            lastFrameNanos = 0L
            return
        }
        val window: List<Double>
        synchronized(samples) {
            samples.addLast(frameMs)
            while (samples.size > windowFrames) samples.removeFirst()
            if (samples.size < JANK_MIN_SAMPLES) return
            window = samples.toList()
        }
        latestStats = jankStatsOf(window, refreshIntervalMs)
        if (!isJankyWindow(window, refreshIntervalMs, ratioThreshold)) return
        if (config?.autoDowngradeToNormal() == true) {
            val snapshot = latestStats
            reset()
            onDowngrade(snapshot)
        }
    }
}
