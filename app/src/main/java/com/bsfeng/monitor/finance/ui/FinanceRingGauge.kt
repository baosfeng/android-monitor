package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceUIKit.kt 的圆环仪表；职责：预算使用率的圆环 + 中心大字 + 小标签。

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.RingProgress
import com.bsfeng.monitor.ui.components.rememberMotionEnabled
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.monitorMonoKpi

/** 记账环直径：原型 s04:71-73 / s06:62-64 / s07:57-59 为 104px（小于全局 --ring-size = 128px）。 */
private val FINANCE_RING_DIAMETER = 104.dp

/**
 * 记账环描边宽度：原型 s04-ring .s04-ring-chart 的环宽 ≈8.7px（s04-记账.html:23-28，
 * gauge 的 thickness 按 104 直径折算），取 9dp。此前走 RingChart 默认 14dp，
 * 比原型粗 60%，孔径被压到看不出「还剩多少」。
 */
internal val FINANCE_RING_STROKE = 9.dp

/** 圆环仪表（预算使用率）：RingProgress + 中心大字 + 小标签。 */
@Composable
internal fun FinanceRingGauge(
    fraction: Float,
    big: String,
    small: String,
    modifier: Modifier = Modifier,
    color: Color? = null,
) {
    val t = LocalDesignTokens.current
    Box(modifier.size(FINANCE_RING_DIAMETER), contentAlignment = Alignment.Center) {
        RingProgress(
            fraction = fraction.coerceIn(0f, 1f),
            color = color,
            animated = rememberMotionEnabled(),
            strokeWidth = FINANCE_RING_STROKE,
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                big,
                // 原型 .s04-ring-txt b = --font-mono --text-lg 21px/700 letter-spacing -.02em
                // （s04-记账.html:27）：21sp 等宽正好是 monitorMonoKpi，不用另造字号。
                style = monitorMonoKpi,
                color = t.fg,
            )
            Text(
                small,
                // 原型 .s04-ring-txt span = --text-sm 14px + --muted（s04-记账.html:28）
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
    }
}
