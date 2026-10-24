package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceMainScreen.kt 的小卡片；职责：KPI 文本行、双入口卡（统计/预算）。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/** 一行 KPI：左侧标签 + 右侧数值。 */
@Composable
internal fun KpiLine(label: String, value: String, color: Color) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = t.meta)
        Text(value, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/** 首页双入口卡（统计分析 / 预算设置）。 */
@Composable
internal fun EntryCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    // 原型 .s04-entry：min-height 104px、图标块 36×36（radius-md、底 fg 6%、色 fg-2）、
    // 标题 17sp/600、副标题 14sp（s04-finance-home.html:12-25）。
    AppCard(modifier = modifier.heightIn(min = 104.dp), onClick = onClick) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(t.radiusMd))
                .background(t.fgFade(6)),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.height(t.space2))
        Text(title, style = MaterialTheme.typography.titleSmall, color = t.fg, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(t.space1))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = t.muted)
    }
}
