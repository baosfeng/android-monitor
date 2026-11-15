package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceMainScreen.kt 的小卡片；职责：KPI 文本行。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.monitorMonoValue

/**
 * 一行 KPI：左侧标签 + 右侧数值。
 *
 * 口径照原型 .s04-krow（s04-记账.html:19）：
 * - 标签 .kpi-label = --text-sm 14px + --muted（20-base.html:275）；
 * - 数值 .s04-kv = --font-mono + tabular-nums 17px/600（s04-记账.html:20）→ [monitorMonoValue]。
 */
@Composable
internal fun KpiLine(label: String, value: String, color: Color) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = t.muted)
        Text(value, style = monitorMonoValue, color = color)
    }
}
