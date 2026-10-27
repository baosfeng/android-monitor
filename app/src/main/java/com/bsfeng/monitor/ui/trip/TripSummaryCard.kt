package com.bsfeng.monitor.ui.trip

// 从 ui/trip/TripScreen.kt 拆出：今日结论卡 —— TripSummaryCard（停留多久 / 移动多远 / 去过几处）。负责「今天这一天的一句话结论」。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.domain.trip.Basis
import com.bsfeng.monitor.domain.trip.DayTimeline
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

@Composable
internal fun TripSummaryCard(timeline: DayTimeline, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MetaText("今日结论", Modifier.weight(1f))
            // 基准角标（契约 s16-source）：文案随 basis 变；不响应点按、不可点、不进 Tab 序
            Badge(basisBadge(timeline.basis), modifier = Modifier.testTag("s16-source"))
        }
        Spacer(Modifier.height(t.space2))
        Text(
            text = tripHeadline(timeline),
            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            color = t.fg,
        )
        Spacer(Modifier.height(t.space1))
        MetaText(tripSubSummary(timeline))
        Spacer(Modifier.height(t.space3))
        Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
            tripMetrics(timeline).forEach { (label, value) ->
                Column(
                    Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(t.radiusMd))
                        .background(t.fgFade(4))
                        .padding(horizontal = t.space3, vertical = t.space2),
                ) {
                    // 标签与数值都固定单行：数值走 formatCompactSpan（15h15m），24h 全长也放得下
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelSmall,
                        color = t.muted,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(t.space1))
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                        color = t.fg,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
        if (timeline.basis == Basis.DEVICE) {
            Spacer(Modifier.height(t.space3))
            MetaText("按设备活动推算：没有定位点，地点名来自网络名与使用簇，坐标与真实距离不显示。")
        }
    }
}
