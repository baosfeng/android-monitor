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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.RingProgress
import com.bsfeng.monitor.ui.components.rememberMotionEnabled
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 记账环直径：原型 s04:71-73 / s06:62-64 / s07:57-59 为 104px（小于全局 --ring-size = 128px）。 */
private val FINANCE_RING_DIAMETER = 104.dp

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
        )
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                big,
                style = MaterialTheme.typography.titleMedium,
                color = t.fg,
                fontWeight = FontWeight.Bold,
            )
            Text(small, style = MaterialTheme.typography.labelSmall, color = t.muted)
        }
    }
}
