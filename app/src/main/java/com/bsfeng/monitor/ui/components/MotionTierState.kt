package com.bsfeng.monitor.ui.components

// 从 ui/components/Motion.kt 拆出：当前动效档位的读取 —— rememberMotionTier（三档）与 rememberMotionEnabled（Boolean 兼容层）。负责「此刻允许播什么强度的动效」。

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.bsfeng.monitor.ui.foundation.MotionConfig
import com.bsfeng.monitor.ui.foundation.MotionTier

/**
 * 当前生效的动效档位（[MotionTier]），点击一次订阅、档位变化立即重组。
 *
 * 系统「动画时长缩放 = 0」时始终返回 [MotionTier.REDUCED]（系统优先级最高，忽略用户选择）；
 * 首次读系统设置有进程级缓存（ContentResolver 读是 binder IPC），缓存装在
 * [MotionConfig.systemAnimationsAllowed] 里，与基础层同源。
 */
@Composable
fun rememberMotionTier(): MotionTier {
    val context = LocalContext.current
    val config = remember(context) { MotionConfig.get(context) }
    val tier by config.tier.collectAsState()
    return tier
}

/**
 * 当前是否允许播动画（Boolean 兼容层，语义 = 档位 != [MotionTier.REDUCED]）。
 *
 * 它写在 [staggerEnterAt] / [rememberStaggerProgress] 等基元的 `animated:` 默认参数上，
 * 列表每行挂载都会求值一次 —— 内部只读 [MotionConfig] 的内存态，零 IPC、零磁盘。
 * 需要区分三档时用 [rememberMotionTier]。
 */
@Composable
fun rememberMotionEnabled(): Boolean = rememberMotionTier() != MotionTier.REDUCED
