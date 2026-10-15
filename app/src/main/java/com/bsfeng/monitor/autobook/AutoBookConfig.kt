package com.bsfeng.monitor.autobook

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 自动记账开关。
 *
 * 原型阶段默认开启（用户要的是「装上就能看到效果」），落盘在 `monitor_prefs`，
 * 与 [com.bsfeng.monitor.util.DebugConfig] 共用同一份 SharedPreferences。
 */
object AutoBookConfig {

    private const val PREFS_NAME = "monitor_prefs"
    private const val KEY_ENABLED = "autobook_enabled"

    @Volatile
    private var initialized = false

    private val _enabledFlow = MutableStateFlow(true)

    /** 可观察的开关状态，供后续设置页 / 自动记账页绑定 */
    val enabledFlow: StateFlow<Boolean> = _enabledFlow.asStateFlow()

    /** 当前开关值；未初始化时返回默认值（避免无障碍服务早于 Application 初始化时误判） */
    val enabled: Boolean
        get() = if (initialized) _enabledFlow.value else DEFAULT_ENABLED

    init {
        // 读盘动作放在 [init] 方法里，避免 object 静态初始化时触碰 Context
    }

    /** 从 SharedPreferences 读取开关（由 Application 启动时调用一次） */
    fun init(context: Context) {
        if (initialized) return
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        _enabledFlow.value = prefs.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
        initialized = true
    }

    /** 写入开关并落盘 */
    fun setEnabled(context: Context, enabled: Boolean) {
        _enabledFlow.value = enabled
        initialized = true
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_ENABLED, enabled)
            .apply()
    }

    /** 原型阶段默认开启 */
    const val DEFAULT_ENABLED = true
}
