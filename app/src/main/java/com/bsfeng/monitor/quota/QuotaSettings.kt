package com.bsfeng.monitor.quota

import android.content.Context
import com.bsfeng.monitor.domain.quota.OllamaPlanMode

/**
 * 额度监控的偏好设置 —— 后台刷新频率、预警开关、Ollama 计费模式。
 *
 * 单独一个类是为了让 ViewModel / 调度器 / 设置页读到同一份口径，
 * 不在界面里写字面量默认值。
 */
class QuotaSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 后台刷新间隔（小时）；0 = 关闭。默认每小时。 */
    var refreshIntervalHours: Int
        get() = prefs.getInt(KEY_REFRESH_HOURS, 1)
        set(value) = prefs.edit().putInt(KEY_REFRESH_HOURS, value.coerceIn(0, 24)).apply()

    /**
     * 单个 provider 的前台刷新间隔（分钟），默认走 [defaultMinutesFor]。
     *
     * 每个 provider 的限流口径不同（Ollama 10 次/分钟/用户且跨设备共享，DeepSeek 只算并发，
     * opencode-go 官方没公布限流），所以不做全局一刀切；调大这个值就能让某个 provider 少打接口。
     */
    fun refreshIntervalMinutes(providerId: String): Int {
        val value = prefs.getInt(KEY_REFRESH_MINUTES + providerId, defaultMinutesFor(providerId))
        return value.coerceIn(1, 60)
    }

    fun setRefreshIntervalMinutes(providerId: String, minutes: Int) {
        prefs.edit()
            .putInt(KEY_REFRESH_MINUTES + providerId, minutes.coerceIn(1, 60))
            .apply()
    }

    /** 额度预警总开关（只影响通知，不影响刷新）。 */
    var alertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALERTS, true)
        set(value) = prefs.edit().putBoolean(KEY_ALERTS, value).apply()

    /** Ollama 计费模式：用户手动选，默认按百分比。 */
    var ollamaPlan: OllamaPlanMode
        get() = OllamaPlanMode.of(prefs.getString(KEY_OLLAMA_PLAN, null))
        set(value) = prefs.edit().putString(KEY_OLLAMA_PLAN, value.storageValue).apply()

    /**
     * 额度页顶部视图：`true` = 聚合榜单（一家一行），`false` = 单家卡片。
     *
     * 默认聚合：榜单是这一页最常被问的问题（「哪家先见底」），单家卡片只是下钻后的细节；
     * 偏好落盘是为了让用户切到单家后下次进来仍是单家，不在每次进页面被强行扳回聚合。
     */
    var aggregateMode: Boolean
        get() = prefs.getBoolean(KEY_AGGREGATE_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_AGGREGATE_MODE, value).apply()

    companion object {
        private const val PREFS_NAME = "quota_settings"
        private const val KEY_REFRESH_HOURS = "refresh_interval_hours"
        private const val KEY_ALERTS = "alerts_enabled"
        private const val KEY_OLLAMA_PLAN = "ollama_plan"
        private const val KEY_AGGREGATE_MODE = "aggregate_mode"
        private const val KEY_REFRESH_MINUTES = "refresh_minutes:"

        /** 默认 1 分钟：Ollama 官方建议的轮询频率，也在 10 次/分钟的限流内。 */
        const val DEFAULT_REFRESH_MINUTES = 1

        /** opencode-go 默认 5 分钟：官方没公布限流，保守一点少打接口。 */
        const val OPENCODE_GO_REFRESH_MINUTES = 5

        /**
         * 某个 provider 的默认刷新间隔（分钟）。
         *
         * provider 自己决定默认值：限流口径写在文档里的（Ollama 10 次/分钟）可以 1 分钟，
         * 没公布的（opencode-go）留 5 分钟余量。设置页与数据层都走这一个函数，避免两处默认值分叉。
         */
        fun defaultMinutesFor(providerId: String): Int = when (providerId) {
            "opencode-go" -> OPENCODE_GO_REFRESH_MINUTES
            else -> DEFAULT_REFRESH_MINUTES
        }

        /** 前台刷新间隔可选项（分钟）。 */
        val MINUTE_OPTIONS: List<Int> = listOf(1, 3, 5, 10, 15)

        /** 后台刷新可选间隔（小时），0 表示关闭。 */
        val REFRESH_OPTIONS: List<Int> = listOf(0, 1, 3, 6)

        fun labelOf(hours: Int): String = when (hours) {
            0 -> "关闭"
            1 -> "每小时"
            3 -> "每 3 小时"
            6 -> "每 6 小时"
            else -> "每 $hours 小时"
        }
    }
}
