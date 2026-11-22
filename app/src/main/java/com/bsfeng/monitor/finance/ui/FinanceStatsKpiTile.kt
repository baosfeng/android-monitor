package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceStatsScreen.kt 的 KpiTile；职责：统计页 KPI 瓦片（surface 底 + 描边 + 等宽数字）。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.monitorMonoKpi

/** 统计页 KPI 瓦片的标签与数值间距（原型 .kpi 的 gap:2px，00-shell:1285）。 */
private val KPI_LABEL_GAP = 2.dp

/**
 * 原型行盒系数：CSS 里 .kpi-label / .s07-kv 都没有显式 line-height，走 normal ≈ 1.47em
 * （Apple 主题 --text-sm 14px → 20.58px、--text-lg 21px → 30.87px）。
 *
 * 为什么按系数折算而不是写死 dp：同一个瓦片 s04 用的是 17px 数值（.s04-amt），
 * 写死 30.8 会把 s04 的行盒一起改错；按「字号 × 1.47」取行盒，两种字号都落在原型值上。
 */
private const val PROTO_LINE_FACTOR = 1.47f

/** 原型 .h2 的布局行盒：21px × leading-tight 1.05 ≈ 22（与 PageHead 的 HEAD_H2_LINE 同值）。 */
private const val STATS_CARD_LINE: Float = 22f

/**
 * 图表卡的标题行：左标题（.h2）+ 右口径（.meta）。
 *
 * 行盒钉成 22dp：CJK 21sp 的自然行盒是 26–31dp，每张卡多 4dp 会沿长页把下面所有锚点的 y
 * 推走（s07 有 6 张卡，累计差 20+）。原型 .row-between 实测高 22。
 */
@Composable
internal fun StatsCardTitleRow(title: String, meta: String, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier.fillMaxWidth().height(STATS_CARD_LINE.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            color = t.fg,
            maxLines = 1,
            modifier = Modifier.wrapContentHeight(unbounded = true, align = Alignment.CenterVertically),
        )
        Text(meta, style = MaterialTheme.typography.bodySmall, color = t.meta, maxLines = 1)
    }
}

/**
 * 统计页 KPI 瓦片：原型 `.kpi` 实测 surface 底 + 1px --border-soft + radius-md(12px)、
 * padding 12px、标签与数值 gap 2px（00-shell:1284-1288）。
 * 卡片基元 AppCard 的圆角固定 radius-lg(18dp) 且不开放覆写，故此处按 `.kpi` 自绘。
 *
 * 行盒钉死：CJK 自然行盒比 CSS normal 高 2–6dp，瓦片是「3 格 × 6 张卡」的重复单元，
 * 每格多 4dp 会沿长页累积成十几 dp 的整体错位（台账「固定盒 + unbounded」解法）。
 */
@Composable
internal fun KpiTile(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    /** 标签字号：原型 .kpi-label 是 --text-sm 14px（bodySmall）。 */
    labelStyle: TextStyle = MaterialTheme.typography.bodySmall,
    /** 数值字号：默认 21sp（.s07-kv，s07 三格）；s04 的 .s04-amt 是 17px，传 [monitorMonoValue]。 */
    valueStyle: TextStyle = monitorMonoKpi,
) {
    val t = LocalDesignTokens.current
    val density = LocalDensity.current
    val labelBox = with(density) { (labelStyle.fontSize.value * PROTO_LINE_FACTOR).sp.toDp() }
    val valueBox = with(density) { (valueStyle.fontSize.value * PROTO_LINE_FACTOR).sp.toDp() }
    val shape = RoundedCornerShape(t.radiusMd)
    Column(
        modifier = modifier
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, shape)
            // 原型 .kpi 是 border-box（实测 .kpi 高 79.4 / 内容宽 89，含 2px 描边）：
            // Compose 的 border 是绘制不占位，补 1dp 让内容宽与总高都回到原型
            .padding(1.dp)
            .padding(t.space3),
    ) {
        Box(Modifier.height(labelBox), contentAlignment = Alignment.CenterStart) {
            Text(
                label,
                style = labelStyle,
                color = t.meta,
                maxLines = 1,
                modifier = Modifier.wrapContentHeight(unbounded = true, align = Alignment.CenterVertically),
            )
        }
        Spacer(Modifier.height(KPI_LABEL_GAP))
        Box(Modifier.height(valueBox), contentAlignment = Alignment.CenterStart) {
            Text(
                value,
                style = valueStyle,
                color = color,
                maxLines = 1,
                modifier = Modifier.wrapContentHeight(unbounded = true, align = Alignment.CenterVertically),
            )
        }
    }
}
