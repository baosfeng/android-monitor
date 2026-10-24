package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceStatsScreen.kt 的 KpiTile；职责：统计页 KPI 瓦片（surface 底 + 描边 + 等宽数字）。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.monitorMonoKpi

/** 统计页 KPI 瓦片的标签与数值间距（原型 .kpi 的 gap:2px，00-shell:1285）。 */
private val KPI_LABEL_GAP: Dp = 2.dp

/**
 * 统计页 KPI 瓦片：原型 `.kpi` 实测 surface 底 + 1px --border-soft + radius-md(12px)、
 * padding 12px、标签与数值 gap 2px（00-shell:1284-1288）。
 * 卡片基元 AppCard 的圆角固定 radius-lg(18dp) 且不开放覆写，故此处按 `.kpi` 自绘。
 */
@Composable
internal fun KpiTile(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    Column(
        modifier = modifier
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, shape)
            .padding(t.space3),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = t.meta)
        Spacer(Modifier.height(KPI_LABEL_GAP))
        Text(
            value,
            // 原型 .s07-kv（s07-stats.html:9）：--text-lg 等宽 700，逐位对齐
            style = monitorMonoKpi,
            color = color,
            maxLines = 1,
        )
    }
}
