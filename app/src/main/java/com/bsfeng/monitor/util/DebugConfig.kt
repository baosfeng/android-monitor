package com.bsfeng.monitor.util

import android.content.Context
import com.bsfeng.monitor.MonitorApp

/**
 * 调试配置开关。
 *
 * 开启后输出详细的日志（标签: [MonitorDebug]），便于排查问题。
 * 可在 App 设置界面中实时开关，无需修改代码。
 */
object DebugConfig {
    private const val PREFS_NAME = "monitor_prefs"
    private const val KEY_DEBUG_ENABLED = "debug_enabled"

    /** 是否开启详细调试日志 */
    var enabled: Boolean = false
        private set

    /** 初始化时从持久化存储读取开关状态 */
    fun init() {
        val prefs = MonitorApp.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        enabled = prefs.getBoolean(KEY_DEBUG_ENABLED, false)
        if (enabled) log("DebugConfig initialized: enabled=true")
    }

    /** 持久化开关状态 */
    fun setEnabled(enabled: Boolean) {
        this.enabled = enabled
        val prefs = MonitorApp.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DEBUG_ENABLED, enabled).apply()
        if (enabled) log("DebugConfig: enabled=$enabled")
    }

    /** 输出调试日志 */
    fun log(message: String) {
        if (enabled) {
            android.util.Log.d("MonitorDebug", message)
        }
    }
}
