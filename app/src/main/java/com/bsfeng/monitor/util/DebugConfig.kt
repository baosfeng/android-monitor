package com.bsfeng.monitor.util

import android.content.Context
import com.bsfeng.monitor.BuildConfig
import com.bsfeng.monitor.MonitorApp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 调试配置开关（两层闸，默认都是关的）。
 *
 * 第一层 = 构建：release 构建里 [BuildConfig.DEBUG] 为 false，所有调试 / 演示入口恒不可见。
 * 第二层 = 运行：debug 构建里默认也「没有调试入口」——先在设置「关于」长按版本行解锁，
 *          再打开「调试模式」，演示件（组件展厅、五态演示条、token dump）才出现。
 *
 * 这样真机/演示场景默认就是生产态，排查问题时才逐层打开。
 */
object DebugConfig {
    private const val PREFS_NAME = "monitor_prefs"
    private const val KEY_DEBUG_ENABLED = "debug_enabled"
    private const val KEY_UNLOCKED = "debug_unlocked"

    private val _enabled = MutableStateFlow(false)
    private val _unlocked = MutableStateFlow(false)

    /** 调试入口是否已解锁（长按「关于 → 版本」解锁；仅 debug 构建可能为 true） */
    val unlockedFlow: StateFlow<Boolean> = _unlocked.asStateFlow()
    val unlocked: Boolean get() = _unlocked.value

    /** 调试入口是否可用：debug 构建 + 已解锁。release 恒 false */
    val uiAvailable: Boolean get() = BuildConfig.DEBUG && _unlocked.value

    /** 演示件是否可见：debug 构建 + 已解锁 + 调试模式已开 */
    val demoVisible: Boolean get() = uiAvailable && _enabled.value

    /** 解锁调试入口（幂等，落盘） */
    fun unlock() {
        if (_unlocked.value) return
        _unlocked.value = true
        MonitorApp.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_UNLOCKED, true).apply()
    }

    /** 可观察的开关状态：Compose 侧读它，切换后界面自动重组 */
    val enabledFlow: StateFlow<Boolean> = _enabled.asStateFlow()

    /** 是否开启详细调试日志（只读；改动一律走 setEnabled 以同时落盘并发通知） */
    val enabled: Boolean get() = _enabled.value

    /** 初始化时从持久化存储读取开关状态 */
    fun init() {
        val prefs = MonitorApp.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _enabled.value = prefs.getBoolean(KEY_DEBUG_ENABLED, false)
        _unlocked.value = prefs.getBoolean(KEY_UNLOCKED, false)
        log("DebugConfig initialized: unlocked=" + unlocked + " enabled=" + enabled)
    }

    /** 持久化开关状态 */
    fun setEnabled(value: Boolean) {
        _enabled.value = value
        val prefs = MonitorApp.instance.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_DEBUG_ENABLED, value).apply()
        if (value) log("DebugConfig: enabled=$value")
    }

    /** 输出调试日志 */
    fun log(message: String) {
        if (enabled) {
            android.util.Log.d("MonitorDebug", message)
        }
    }
}
