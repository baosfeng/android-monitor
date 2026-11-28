package com.bsfeng.monitor.quota

import android.content.Context
import androidx.core.content.edit

/**
 * 额度监控的偏好设置 —— 后台刷新频率、预警开关、各账号的前台刷新间隔。
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
        set(value) = prefs.edit { putInt(KEY_REFRESH_HOURS, value.coerceIn(0, 24)) }

    /**
     * 单个**账号实例**的前台刷新间隔（分钟），未设置时退回该类型的默认值。
     *
     * 键用实例键（[instanceId]），第一个实例的 id 恒等于类型 id，所以老用户的
     * `refresh_minutes:ollama` 天然命中，无需迁移；同类型第二个账号按 ``+`#2`` 单独一个键。
     *
     * 默认值仍按**类型**算：限流口径是 provider 决定的（Ollama 10 次/分钟/用户且跨设备共享，
     * opencode-go 官方没公布限流），同类型的两个账号共用同一套限流事实。
     */
    fun refreshIntervalMinutes(instanceId: String, providerTypeId: String = instanceId): Int {
        val value = prefs.getInt(KEY_REFRESH_MINUTES + instanceId, defaultMinutesFor(providerTypeId))
        return value.coerceIn(1, 60)
    }

    fun setRefreshIntervalMinutes(instanceId: String, minutes: Int) {
        prefs.edit { putInt(KEY_REFRESH_MINUTES + instanceId, minutes.coerceIn(1, 60)) }
    }

    /** 额度预警总开关（只影响通知，不影响刷新）。 */
    var alertsEnabled: Boolean
        get() = prefs.getBoolean(KEY_ALERTS, true)
        set(value) = prefs.edit { putBoolean(KEY_ALERTS, value) }

    companion object {
        private const val PREFS_NAME = "quota_settings"
        private const val KEY_REFRESH_HOURS = "refresh_interval_hours"
        private const val KEY_ALERTS = "alerts_enabled"
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
