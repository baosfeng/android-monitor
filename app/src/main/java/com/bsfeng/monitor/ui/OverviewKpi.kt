package com.bsfeng.monitor.ui

// 从 ui/OverviewScreen.kt 拆出：概览 KPI 数值的三个渲染件 —— KpiValue（整数滚动）、KpiMoney（金额滚动）、KpiActive（活跃时长 h/m）。负责「一块 KPI 卡里的那个大数字」，三段面板共用。

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import com.bsfeng.monitor.ui.components.AnimatedNumber
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * KPI 数值：原型 .kpi-value 为 28px/700 + font-mono + tabular-nums（parts/00-shell.html:322）。
 * W1 的等宽数字样式尚未落到 ui/theme/Type.kt，这里先显式指定 FontFamily.Monospace，
 * 集成时统一替换为 Type.kt 的等宽样式。
 */
@Composable
internal fun KpiValue(value: Int) {
    val t = LocalDesignTokens.current
    AnimatedNumber(
        value = value,
        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
        color = t.fg,
    )
}

@Composable
internal fun KpiMoney(value: Double) {
    val t = LocalDesignTokens.current
    AnimatedNumber(
        value = value.toFloat(),
        decimals = 0,
        prefix = "¥",
        style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
        color = t.fg,
    )
}

@Composable
internal fun KpiActive(minutes: Long) {
    val t = LocalDesignTokens.current
    Row(verticalAlignment = Alignment.Bottom) {
        AnimatedNumber(
            value = (minutes / 60).toInt(),
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
            color = t.fg,
        )
        Text("h", style = MaterialTheme.typography.labelMedium, color = t.muted)
        Spacer(Modifier.width(t.space1))
        AnimatedNumber(
            value = (minutes % 60).toInt(),
            style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
            color = t.fg,
        )
        Text("m", style = MaterialTheme.typography.labelMedium, color = t.muted)
    }
}
