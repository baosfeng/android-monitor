package com.bsfeng.monitor.quota

import android.content.Context
import androidx.core.content.edit

/**
 * 额度监控的偏好设置 —— 后台刷新频率（分钟档）、预警开关、息屏策略、各账号前台间隔。
 *
 * 单独一个类是为了让 ViewModel / 调度器 / 设置页读到同一份口径，
 * 不在界面里写字面量默认值。
 */
class QuotaSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 后台刷新间隔（**分钟**）；0 = 关闭。默认 15 分钟。
     *
     * 迁移：旧键 refresh_interval_hours 存的是 0/1/3/6 小时，首次读新键时 ×60 并夹到
     * [MIN_BACKGROUND_MINUTES, MAX_BACKGROUND_MINUTES]；0 仍表示关闭。旧键不删——降级回旧版本
     * 时它还在，不会因为升级后重装把用户的关闭态读成默认 15 分钟。
     */
    var backgroundRefreshMinutes: Int
        get() {
            val fresh = prefs.getInt(KEY_REFRESH_MINUTES_BG, UNSET)
            if (fresh != UNSET) return fresh.coerceIn(0, MAX_BACKGROUND_MINUTES)
            return migrateLegacyHours()
        }
        set(value) = prefs.edit { putInt(KEY_REFRESH_MINUTES_BG, value.coerceIn(0, MAX_BACKGROUND_MINUTES)) }

    /**
     * ViewModel 与设置页快捷卡仍用旧名读写（本轮不动 VM / 设置页）：**值已是分钟**。
     * 新代码请用 [backgroundRefreshMinutes]。
     */
    var refreshIntervalHours: Int
        get() = backgroundRefreshMinutes
        set(value) {
            backgroundRefreshMinutes = value
        }

    /** 息屏时不跑后台刷新（省电；解锁后由前台定时器补一次）。消费点在 [QuotaRefreshReceiver]。 */
    var skipRefreshWhenScreenOff: Boolean
        get() = prefs.getBoolean(KEY_SKIP_SLEEP, true)
        set(value) = prefs.edit { putBoolean(KEY_SKIP_SLEEP, value) }

    /**
     * 单个**账号实例**的前台刷新间隔（分钟），未设置时退回该类型的默认值。
     *
     * 键用实例键（[instanceId]），第一个实例的 id 恒等于类型 id，所以老用户的
     * refresh_minutes:ollama 天然命中，无需迁移；同类型第二个账号按 #2 单独一个键。
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

    /** 旧小时键 → 分钟档：0 关闭；1/3/6 小时一律夹到最大档 60 分钟（新档只到 1 小时）。 */
    private fun migrateLegacyHours(): Int {
        val hours = prefs.getInt(KEY_REFRESH_HOURS_LEGACY, UNSET)
        if (hours == UNSET) return DEFAULT_BACKGROUND_MINUTES
        if (hours <= 0) return 0
        return (hours * 60).coerceIn(MIN_BACKGROUND_MINUTES, MAX_BACKGROUND_MINUTES)
    }

    companion object {
        private const val PREFS_NAME = "quota_settings"
        private const val KEY_REFRESH_HOURS_LEGACY = "refresh_interval_hours"
        private const val KEY_REFRESH_MINUTES_BG = "refresh_interval_minutes"
        private const val KEY_SKIP_SLEEP = "skip_refresh_when_screen_off"
        private const val KEY_ALERTS = "alerts_enabled"
        private const val KEY_REFRESH_MINUTES = "refresh_minutes:"

        /** 未写过任何键（新装 / 旧键也没有）。 */
        private const val UNSET = -1

        /** 默认 1 分钟：Ollama 官方建议的轮询频率，也在 10 次/分钟的限流内。 */
        const val DEFAULT_REFRESH_MINUTES = 1

        /** opencode-go 默认 5 分钟：官方没公布限流，保守一点少打接口。 */
        const val OPENCODE_GO_REFRESH_MINUTES = 5

        /** 后台刷新默认 15 分钟：与原型节奏说明「后台每 15 分钟」一致，也给 doze 留出余量。 */
        const val DEFAULT_BACKGROUND_MINUTES = 15

        /** 后台刷新最小档 5 分钟：原型第一档，Ollama 10 次/分钟限流内。 */
        const val MIN_BACKGROUND_MINUTES = 5

        /** 后台刷新最大档 60 分钟。 */
        const val MAX_BACKGROUND_MINUTES = 60

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

        /** 后台刷新可选间隔（分钟），0 = 关闭；顺序与原型 s19 chip 一致。 */
        val REFRESH_OPTIONS: List<Int> = listOf(5, 15, 30, 60, 0)

        /** 设置页快捷卡文案：每 15 分钟 / 每 1 小时 / 关闭。 */
        fun labelOf(minutes: Int): String = when {
            minutes <= 0 -> "关闭"
            minutes == 60 -> "每 1 小时"
            minutes < 60 -> "每 $minutes 分钟"
            else -> "每 ${minutes / 60} 小时"
        }

        /** 原型 s19 的 chip 文案逐字（不带「每」）：5 分钟 / 15 分钟 / 30 分钟 / 1 小时 / 关闭。 */
        fun chipLabelOf(minutes: Int): String = when (minutes) {
            0 -> "关闭"
            60 -> "1 小时"
            else -> "$minutes 分钟"
        }
    }
}

/**
 * 息屏时是否跳过这一轮后台刷新。
 *
 * fail-open：拿不到屏幕状态（[screenInteractive] 传 true）时照常刷新——省电不能变成停更。
 */
internal fun shouldSkipBackgroundRefresh(skipWhenScreenOff: Boolean, screenInteractive: Boolean): Boolean =
    skipWhenScreenOff && !screenInteractive
