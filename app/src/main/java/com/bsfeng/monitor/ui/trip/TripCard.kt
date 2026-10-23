package com.bsfeng.monitor.ui.trip

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import com.bsfeng.monitor.domain.trip.DayTimeline
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * s01 概览的「今日行程」卡（原型 parts/s01-overview.html `data-od-id="s01-trip"`）。
 *
 * 结构：卡头（标题 + 基准角标）→ 24h 时间带 → 刻度 → 图例 → 结论一句话 → 覆盖度 / 入口。
 * 整卡可点（[onClick]）、不需要卡内再放按钮。**没有数据时只显示空态文案，不画假时间带。**
 */
@Composable
fun TodayTripCard(
    timeline: DayTimeline?,
    dayStart: Long,
    dayEnd: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val hasTrack = timeline != null && (timeline.stays.isNotEmpty() || timeline.moves.isNotEmpty())

    AppCard(modifier = modifier, onClick = onClick) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "今日行程",
                style = MaterialTheme.typography.titleSmall,
                color = t.fg,
                modifier = Modifier.weight(1f),
            )
            // 无数据时不宣称任何基准，避免「按设备活动推算」在空态上变成假信息
            Badge(if (timeline == null) "暂无数据" else basisBadge(timeline.basis))
        }
        Spacer(Modifier.height(t.space3))
        if (!hasTrack) {
            // 无时间轴（无任何段）时的首行也走 TripFormat，避免概览卡自带一版结论句：
            // 完全没有记录 → 空态；有移动/未记录但 0 处停留 → 「今天没有可识别的地点」
            Text(
                text = if (timeline == null) "今天还没有行程" else tripHeadline(timeline),
                style = MaterialTheme.typography.bodyMedium,
                color = t.fg,
            )
            Spacer(Modifier.height(t.space1))
            MetaText("停留与移动由设备活动推算；采集中断的时间标成「未记录」，不做推断。")
        } else {
            TripBand(segments = timeline.segments, dayStart = dayStart, dayEnd = dayEnd)
            Spacer(Modifier.height(t.space1))
            TripBandAxis()
            Spacer(Modifier.height(t.space2))
            TripBandLegend(timeline)
            Spacer(Modifier.height(t.space3))
            Text(
                text = tripHeadline(timeline),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = t.fg,
            )
            Spacer(Modifier.height(t.space1))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                MetaText(tripCoverageNote(timeline))
                MetaText("查看时间轴 ›")
            }
        }
    }
}
