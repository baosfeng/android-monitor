package com.bsfeng.monitor.skip

import android.content.Context
import android.content.SharedPreferences

/**
 * 开屏跳过的可配置项（持久化在 `skip_config`）。
 *
 * ## 为什么要可配置（P2-2）
 * 监控窗口原本硬编码为 10 秒（[SkipRuleEngine]）+ 内置全局规则的 `matchTime` 8 秒。
 * 弱网/冷启动时开屏广告可能 10 秒后才渲染出跳过按钮，规则窗口已过期 → 跳不过；
 * 反过来窗口开太久又会增加"正常页面上的跳过按钮被误点"的概率。因此交给用户选择。
 *
 * **监控窗口同时是内置规则的时间闸门**：`SkipRuleManager.load()` 会把该值传给
 * [BuiltInRules.getRules]，保证"扫描窗口"与"规则参与匹配的时间"一致（否则窗口再长也没用）。
 *
 * ## 弱网顺延（P2-2 增强）
 * 除用户选择的窗口外，引擎在**窗口临近结束且页面仍在渲染**时会把截止时间顺延一次，
 * 参数集中在 [WINDOW_EXTEND_STEP_MS] / [WINDOW_MAX_EXTENSIONS] / [WINDOW_STILL_RENDERING_MS] /
 * [WINDOW_EXTEND_MAX_SCAN_GAP_MS]，由 [WindowExtendPolicy] 打包注入引擎（无 UI 开关，默认开启）。
 */
object SkipConfig {

    private const val PREFS_NAME = "skip_config"
    private const val KEY_WATCH_WINDOW_MS = "watch_window_ms"

    /** 可选的监控窗口（毫秒） */
    val WINDOW_OPTIONS_MS: List<Long> = listOf(5_000L, 10_000L, 15_000L)

    /** 默认监控窗口（毫秒） */
    const val DEFAULT_WATCH_WINDOW_MS = 10_000L

    // ==================== 弱网顺延参数（P2-2 增强，无 UI 开关） ====================

    /**
     * 弱网顺延的**步长**（毫秒）：窗口到点时若页面仍在渲染，就把截止时间往后推这么多。
     *
     * 2 秒的来源：真机日志里"跳过按钮在窗口结束后才出现"的晚到量多在 1~3 秒，
     * 而一次"广告容器渲染 → 跳过按钮出现"的延迟量级也是秒级，2 秒刚好覆盖一次渲染延迟。
     */
    const val WINDOW_EXTEND_STEP_MS = 2_000L

    /**
     * 单个监控窗口**最多顺延的次数**（配合 [WINDOW_EXTEND_STEP_MS] 即最多 +4 秒）。
     *
     * 上限的理由（防误触优先）：顺延越久，正常首页/详情页上出现的"跳过/关闭/×"文本被误点的
     * 概率越高——窗口本身就是防误触闸门。取 2 次后：
     * - 默认 10 秒档最长 14 秒（+4 秒 = 1.4×），仍明显短于"用户已经看到首页却还在点"的时长；
     * - 最小 5 秒档最长 9 秒（+4 秒 = 1.8×，仍 < 2×）。
     * 该上限是**精确**的：截止时间按「原截止时间 + 步长」累加，不会随"到点检测延迟"膨胀。
     * 若需要更长覆盖，应让用户把监控窗口调到 15 秒档，而不是无限顺延。
     */
    const val WINDOW_MAX_EXTENSIONS = 2

    /**
     * 判定「页面仍在渲染」的阈值（毫秒）：距最近一次 CONTENT_CHANGED **小于**该值即视为仍在变化。
     *
     * 依据：CONTENT_CHANGED 由无障碍服务以 100ms 节流上送，兜底轮询为 300ms；
     * 页面静止 1 秒以上还继续顺延就属于"广告根本还没请求回来"，继续等只会增加误触窗口。
     */
    const val WINDOW_STILL_RENDERING_MS = 1_000L

    /**
     * 「到点判定」允许的最大扫描间隔（毫秒）：上一次扫描距今超过该值就不再顺延。
     *
     * 正常扫描节奏 = 兜底轮询 300ms + 单次扫描耗时（实测 < 150ms），CONTENT_CHANGED 只会更密；
     * 1.5 秒是给慢页面/主线程抖动留的余量。超过它说明引擎/主线程长时间没跑（进程被冻结、
     * 熄屏、系统时间跳变），此时窗口实际上早已过期，再顺延等于"复活过期窗口"并放大误触风险。
     */
    const val WINDOW_EXTEND_MAX_SCAN_GAP_MS = 1_500L

    /** 顺延能带来的最大额外时长（毫秒）= [WINDOW_EXTEND_STEP_MS] × [WINDOW_MAX_EXTENSIONS] = 4000 */
    const val WINDOW_MAX_EXTENSION_MS = WINDOW_EXTEND_STEP_MS * WINDOW_MAX_EXTENSIONS

    private var prefs: SharedPreferences? = null

    /** 当前监控窗口（毫秒，自 App 切换起算） */
    @Volatile
    var watchWindowMs: Long = DEFAULT_WATCH_WINDOW_MS
        private set

    /** 从持久化存储读取配置（App 启动时调用一次） */
    fun init(context: Context) {
        val p = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs = p
        watchWindowMs = normalize(p.getLong(KEY_WATCH_WINDOW_MS, DEFAULT_WATCH_WINDOW_MS))
    }

    /** 保存监控窗口；非法取值回落默认 */
    fun setWatchWindowMs(ms: Long) {
        val value = normalize(ms)
        watchWindowMs = value
        prefs?.edit()?.putLong(KEY_WATCH_WINDOW_MS, value)?.apply()
    }

    /** 归一化：只接受 [WINDOW_OPTIONS_MS] 里的取值，其余一律回落默认（防止手改 prefs 造成 0/超长窗口） */
    internal fun normalize(ms: Long): Long = if (ms in WINDOW_OPTIONS_MS) ms else DEFAULT_WATCH_WINDOW_MS
}

/**
 * 弱网顺延（窗口延长）策略。
 *
 * [SkipRuleEngine] 以可注入字段持有本策略（默认值即 [SkipConfig] 中的常量），单测可替换步长/上限
 * 来验证边界；参数语义集中在此，避免散落在引擎里的"魔法数字"。
 *
 * @param stepMs 单次顺延时长（毫秒）
 * @param maxExtensions 单个窗口最多顺延次数
 * @param stillRenderingMs 判定「页面仍在渲染」的阈值（距最近一次 CONTENT_CHANGED 的毫秒数）
 * @param maxScanGapMs 到点判定允许的最大扫描间隔（毫秒）：引擎必须一直在连续扫描，否则视为窗口已过期
 */
data class WindowExtendPolicy(
    val stepMs: Long = SkipConfig.WINDOW_EXTEND_STEP_MS,
    val maxExtensions: Int = SkipConfig.WINDOW_MAX_EXTENSIONS,
    val stillRenderingMs: Long = SkipConfig.WINDOW_STILL_RENDERING_MS,
    val maxScanGapMs: Long = SkipConfig.WINDOW_EXTEND_MAX_SCAN_GAP_MS
) {

    /** 本策略下最多能额外延长的时长（毫秒）：步长 × 次数 */
    val maxExtensionMs: Long
        get() = if (stepMs > 0 && maxExtensions > 0) stepMs * maxExtensions else 0L

    /** 是否启用顺延；步长或次数非正数视为关闭（可用于灰度/对照） */
    val enabled: Boolean
        get() = stepMs > 0 && maxExtensions > 0

    /** 给定基础窗口下的最长监控总时长（毫秒）：截止时间是精确的，引擎在截止后的下一次扫描内停止 */
    fun maxTotalWindowMs(baseWindowMs: Long): Long = baseWindowMs + maxExtensionMs
}
