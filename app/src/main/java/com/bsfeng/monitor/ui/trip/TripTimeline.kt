package com.bsfeng.monitor.ui.trip

// 从 ui/trip/TripScreen.kt 拆出：日视图时间轴 —— TripTimelineCard（卡片外壳）、SegmentRow（停留 / 移动 / 未记录单段）。负责「一天按时间顺序摊开」。

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NavigateNext
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.domain.trip.DaySegment
import com.bsfeng.monitor.domain.trip.DayTimeline
import com.bsfeng.monitor.domain.trip.Gap
import com.bsfeng.monitor.domain.trip.Move
import com.bsfeng.monitor.domain.trip.Stay
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.live.CardHead
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

@Composable
internal fun TripTimelineCard(
    timeline: DayTimeline,
    dayStart: Long,
    onSelect: (Stay) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier) {
        // 处数 / 段数已由结论卡次行给出，这里只给操作提示，避免同一组计数在屏内出现两遍
        CardHead("时间轴", "停留段可点开详情")
        Spacer(Modifier.height(t.space2))
        timeline.segments.forEachIndexed { index, segment ->
            if (index > 0) HorizontalDivider(color = t.borderSoft)
            when (segment) {
                is Stay -> SegmentRow(segment, dayStart, onSelect = { onSelect(segment) })
                else -> SegmentRow(segment, dayStart, onSelect = null)
            }
        }
    }
}

@Composable
private fun SegmentRow(
    segment: DaySegment,
    dayStart: Long,
    onSelect: (() -> Unit)?,
) {
    val t = LocalDesignTokens.current
    val tint = when (segment) {
        is Stay -> t.chart2
        is Move -> t.chart1
        is Gap -> t.fg2
    }
    val icon: ImageVector = when (segment) {
        is Stay -> Icons.Outlined.LocationOn
        is Move -> Icons.Outlined.SwapHoriz
        is Gap -> Icons.Outlined.History
    }
    val trackText = (segment as? Stay)?.let { pageTrackText(it) }.orEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (onSelect != null) {
                    Modifier.clickable { onSelect() }
                } else {
                    Modifier
                },
            )
            .heightIn(min = t.rowMinH)
            .padding(vertical = t.space2),
        verticalAlignment = Alignment.Top,
    ) {
        // 时间列：起止各一行，等宽数字（原型 .s16-seg-time）
        Column(Modifier.width(t.space12)) {
            Text(
                text = formatSpanClock(segment.start, dayStart),
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
                maxLines = 1,
            )
            Text(
                text = formatSpanClock(segment.end, dayStart),
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(t.space2))
        Box(
            Modifier
                .size(t.space8)
                .clip(RoundedCornerShape(t.radiusMd))
                .background(tint.copy(alpha = tint.alpha * 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(t.space5))
        }
        Spacer(Modifier.width(t.space3))
        Column(Modifier.weight(1f)) {
            Text(
                text = segmentTitle(segment),
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                color = if (segment is Gap) t.muted else t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(t.space1))
            Text(
                text = segmentSubtitle(segment),
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (trackText.isNotEmpty()) {
                Spacer(Modifier.height(t.space1))
                Text(
                    text = trackText,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.fg2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (onSelect != null) {
            Icon(
                Icons.Outlined.NavigateNext,
                contentDescription = "查看停留详情",
                tint = t.muted,
                modifier = Modifier.size(t.space5),
            )
        }
    }
}

// ───────────────────────────── 口径说明 ─────────────────────────────
