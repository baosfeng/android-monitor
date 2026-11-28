package com.bsfeng.monitor.ui.quota

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 桌面组件「4×2 样张」（原型 s18-widget）：与账号列表同一套语言（值 + 窗口 + 条）。
 *
 * 数据取「每个 provider 的第一个账号」的首列，最多 4 格 —— 与 D 的组件同一个取数口径，
 * 主屏看得到的四格就是组件上会显示的四格。
 */
internal data class QuotaWidgetCell(
    val valueText: String,
    val label: String,
    val fraction: Float?,
)

internal fun quotaWidgetCells(accounts: List<QuotaAccountUi>, limit: Int = 4): List<QuotaWidgetCell> =
    accounts
        .distinctBy { it.providerTypeId }
        .mapNotNull { account ->
            val column = account.columns.firstOrNull() ?: return@mapNotNull null
            val reset = column.resetText
            QuotaWidgetCell(
                valueText = column.valueText,
                label = account.name + " " + column.title + if (reset == null) "" else " · " + reset,
                fraction = column.fraction,
            )
        }
        .take(limit)

@Composable
internal fun QuotaWidgetSample(
    accounts: List<QuotaAccountUi>,
    updatedText: String,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val cells = quotaWidgetCells(accounts)
    // 原型 --w-tint / --w-line / --w-ink：chart-8 与暖面 / fg 的混色
    val tint = lerp(t.surfaceWarm, t.chart8, 0.08f)
    val line = t.chart8.copy(alpha = 0.24f)
    val ink = lerp(t.chart8, t.fg, 0.38f)
    Column(modifier.fillMaxWidth().padding(horizontal = t.space4)) {
        Row(
            Modifier.fillMaxWidth().padding(top = t.space3, bottom = t.space1).testTag("s18-widget-head"),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "桌面组件 · 4×2 样张",
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                color = t.fg,
                modifier = Modifier.weight(1f),
            )
            Text("与列表同一套语言", style = quotaTextSmall, color = t.muted)
        }
        Column(
            Modifier
                .fillMaxWidth()
                .testTag("s18-widget")
                .clip(RoundedCornerShape(t.radiusLg))
                .background(tint)
                .border(1.dp, line, RoundedCornerShape(t.radiusLg))
                .padding(t.space3),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(16.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(ink),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("%", style = quotaTextSmall, color = t.surfaceWarm)
                }
                Spacer(Modifier.width(5.dp))
                Text(
                    "额度 · " + updatedText,
                    style = quotaLabel13.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                    color = ink,
                )
            }
            Spacer(Modifier.height(t.space2))
            cells.chunked(2).forEach { rowCells ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(t.space3)) {
                    rowCells.forEach { cell -> WidgetCell(cell, ink) }
                    if (rowCells.size == 1) Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun RowScope.WidgetCell(cell: QuotaWidgetCell, ink: androidx.compose.ui.graphics.Color) {
    val t = LocalDesignTokens.current
    Column(Modifier.weight(1f)) {
        Text(cell.valueText, style = quotaMonoWidget, color = ink, maxLines = 1)
        Text(
            cell.label,
            style = quotaTextSmall,
            color = t.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        // 与 D 的小组件同一套语言：纯余额格（fraction = null）不画横条，只留占位保高度
        QuotaMeterTrack(fraction = cell.fraction, color = ink, height = 4.dp)
    }
}
