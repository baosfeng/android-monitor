package com.bsfeng.monitor.ui.trip

// 从 ui/trip/TripScreen.kt 拆出：日视图时间轴 —— TripTimelineCard（卡片外壳）、SegmentRow（停留 / 移动 / 未记录单段）。负责「一天按时间倒序摊开，最新段在最上」（顺序规则见 TripTimelineOrder.kt）。

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.domain.trip.DaySegment
import com.bsfeng.monitor.domain.trip.DayTimeline
import com.bsfeng.monitor.domain.trip.Gap
import com.bsfeng.monitor.domain.trip.Move
import com.bsfeng.monitor.domain.trip.Stay
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.live.CardHead
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 日视图时间轴卡。
 *
 * @param onSelect 停留段被点开时回调，并把该段的 [FocusRequester] 交给调用方 ——
 *   抽屉关闭后焦点回到这一段（契约 trip-detail-sheet「移除/关闭后 · 焦点回到触发它的停留段」），
 *   沿用 AdScreen 的 lastRowRequester 模式。移动 / 未记录段不回调（它们没有详情可开）。
 */
@Composable
internal fun TripTimelineCard(
    timeline: DayTimeline,
    dayStart: Long,
    onSelect: (Stay, FocusRequester) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier) {
        // 处数 / 段数已由结论卡次行给出，这里只给操作提示，避免同一组计数在屏内出现两遍
        CardHead("时间轴", "停留段可点开详情")
        Spacer(Modifier.height(t.space2))
        // 段列表容器（契约 s16-timeline-list）：由整页统一跟手滚动，卡内不做独立滚动，避免嵌套吃手势
        Column(
            Modifier
                .fillMaxWidth()
                .testTag("s16-timeline-list"),
        ) {
            // 倒序（最新在上）：只改显示顺序，域层 segments 顺序与导出不动
            val rows = remember(timeline.segments) { timelineRowsNewestFirst(timeline.segments) }
            rows.forEachIndexed { index, segment ->
                if (index > 0) HorizontalDivider(color = t.borderSoft)
                SegmentRow(
                    segment = segment,
                    anchor = timelineAnchorFor(index, rows.size),
                    dayStart = dayStart,
                    onSelect = onSelect,
                )
            }
        }
    }
}

/**
 * 段级锚点（契约 9 段：home-am / move-1 / cafe / move-2 / office / move-3 / home-noon / gap-1 / home-pm）。
 *
 * 原型按示例数据画死「家 00:00–09:10 / 移动 / 咖啡 / …」，真实一天段数不定；锚点跟随**段的身份**：
 * 倒序渲染后视觉第 i 行按时间序第 (段数 - 1 - i) 段去查锚点表，所以 9 段时最上一行（最晚）是
 * s16-seg-home-pm、最下一行（最早）是 s16-seg-home-am，与原型块一一对应。
 * 时间序第 10 段起不打锚点：锚点集合严格等于契约定义，不新增自增号（一经发布不改号、不新增）。
 * 锚点名表在 TripTimelineOrder.kt（唯一真源），由 timelineAnchorFor 换算。
 */
private fun Modifier.segmentTag(anchor: String?): Modifier =
    anchor?.let { testTag(it) } ?: this

/**
 * 单段一行：时间列 + 类型图标 + 标题 / 副标题（停留段另带 ›）。
 *
 * 交互口径（契约 s16-seg-*）：
 * - 停留段可点：按下**行底色加深一档**（dur-state 120ms）+ LIGHT，抬指打开详情抽屉；
 * - 移动段 / 未记录段不可点：无点击区、无高亮、无 toast，行尾也没有 ›（未记录段整行降为次要色）。
 */
@Composable
private fun SegmentRow(
    segment: DaySegment,
    anchor: String?,
    dayStart: Long,
    onSelect: (Stay, FocusRequester) -> Unit,
) {
    val t = LocalDesignTokens.current
    val stay = segment as? Stay
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val pressedBg by animateColorAsState(
        targetValue = if (stay != null && pressed) t.fgFade(7) else Color.Transparent,
        animationSpec = tween(t.durState),
        label = "segmentPressedBg",
    )
    val focusRequester = remember { FocusRequester() }
    val shape = RoundedCornerShape(t.radiusMd)
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
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .segmentTag(anchor)
            .clip(shape)
            .background(pressedBg)
            .then(
                if (stay != null) {
                    Modifier
                        .focusRequester(focusRequester)
                        .keyboardFocusRing(shape)
                        .clickable(
                            interactionSource = interaction,
                            indication = LocalIndication.current,
                        ) {
                            haptic(HapticTone.LIGHT)
                            onSelect(stay, focusRequester)
                        }
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
                modifier = Modifier.testTag("row.segment"),
            )
            Spacer(Modifier.height(t.space1))
            Text(
                text = segmentSubtitle(segment),
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (stay != null) {
            Icon(
                Icons.Outlined.NavigateNext,
                contentDescription = "查看停留详情",
                tint = t.muted,
                modifier = Modifier.size(t.space5),
            )
        }
    }
}
