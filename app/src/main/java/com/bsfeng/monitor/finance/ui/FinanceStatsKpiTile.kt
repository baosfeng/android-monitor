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
import androidx.compose.ui.text.TextStyle
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
    /** 标签字号：默认 12sp（.kpi-label 在 s04 档位卡是 14px，那两处调用点传 bodySmall）。 */
    labelStyle: TextStyle = MaterialTheme.typography.labelSmall,
    /** 数值字号：默认 21sp（.s07-kv，s07 三格）；s04 的 .s04-amt 是 17px，传 [monitorMonoValue]。 */
    valueStyle: TextStyle = monitorMonoKpi,
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
        Text(label, style = labelStyle, color = t.meta)
        Spacer(Modifier.height(KPI_LABEL_GAP))
        Text(
            value,
            style = valueStyle,
            color = color,
            maxLines = 1,
        )
    }
}
