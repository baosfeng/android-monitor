package com.bsfeng.monitor.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.bsfeng.monitor.BuildConfig
import com.bsfeng.monitor.util.DebugConfig

/**
 * 调试 / 演示件的**统一判据**——所有演示件都必须走这里，不要再各自写 `BuildConfig.DEBUG`。
 *
 * 两层闸（详见 [DebugConfig]）：
 * 1. 构建层：release 构建恒为 false（真机 / 演示场景默认生产态）。
 * 2. 运行层：debug 构建里也要先在「关于 → 长按版本」解锁，再打开「调试模式」。
 */

/** 调试入口（设置里的「调试模式」分组）是否显示 */
@Composable
fun rememberDebugUiAvailable(): Boolean {
    val unlocked by DebugConfig.unlockedFlow.collectAsState()
    return BuildConfig.DEBUG && unlocked
}

/** 演示件（组件展厅入口、五态演示条、token dump）是否可见 */
@Composable
fun rememberDemoVisible(): Boolean {
    val unlocked by DebugConfig.unlockedFlow.collectAsState()
    val debugOn by DebugConfig.enabledFlow.collectAsState()
    return BuildConfig.DEBUG && unlocked && debugOn
}
