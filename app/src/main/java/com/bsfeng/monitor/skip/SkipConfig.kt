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
 */
object SkipConfig {

    private const val PREFS_NAME = "skip_config"
    private const val KEY_WATCH_WINDOW_MS = "watch_window_ms"

    /** 可选的监控窗口（毫秒） */
    val WINDOW_OPTIONS_MS: List<Long> = listOf(5_000L, 10_000L, 15_000L)

    /** 默认监控窗口（毫秒） */
    const val DEFAULT_WATCH_WINDOW_MS = 10_000L

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
