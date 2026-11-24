package com.bsfeng.monitor.ui.trip

// 从 ui/trip/TripScreen.kt 拆出：日视图时间轴 —— TripTimelineCard（卡片外壳）、SegmentRow（停留 / 移动 / 未记录单段）。负责「一天按时间倒序摊开，最新段在最上」（顺序规则见 TripTimelineOrder.kt）。
//
// 盒模型对齐原型 #s16 .s16-seg：grid 46 / 30 / 1fr / 18、gap 12、min-height 52、padding 12 0、border-top 1px；
// 标题 17px/600 line-height 1.25、副标题 14px line-height 1.3、两行之间 gap 2。

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.NavigateNext
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
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

/** 原型 .s16-seg 的两行行盒：标题 17px × 1.25 = 21.25、副标题 14px × 1.3 = 18.2，中间 gap 2。 */
private val SEG_TITLE_LINE = 21.25.dp
private val SEG_SUB_LINE = 18.2.dp

/** 原型 .s16-seg：上下 padding 各 12 + 内容 41.45 + 顶部 1px 描边 = 66.45（实测原型 66.4）。 */
private val SEG_H = 66.45.dp

/** 原型 .s16-seg 的四列：时间 46 / 图标 30 / 正文 1fr / 箭头 18，列间距 12。 */
private val SEG_TIME_COL = 46.dp
private val SEG_ICON = 30.dp
private val SEG_CHEV = 18.dp

/**
 * 日视图时间轴卡。
 *
 * 原型是 `div.pad > div.card(padding 0 16) > div.s16-tl > 9 × .s16-seg`；锚点 s16-timeline-list 挂的正是这张卡
 * 本身（宽 361、含左右 16 内边距），所以卡的内边距必须放在锚点**内层**，否则会连左右各 16 一起量进去。
 *
 * @param onSelect 停留段被点开时回调，并把该段的 [FocusRequester] 交给调用方 ——
 *   抽屉关闭后焦点回到这一段（锚点 trip-detail-sheet「移除/关闭后 · 焦点回到触发它的停留段」），
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
    // 原型 .card 是 padding 0 16，锚点 s16-timeline-list 就是这张卡：tag 在内边距之前，内边距落到内层
    AppCard(modifier = modifier, padding = 0.dp) {
        Column(
            Modifier
                .fillMaxWidth()
                .testTag("s16-timeline-list")
                .padding(horizontal = t.space4 + 1.dp),
        ) {
            // 处数 / 段数已由结论卡次行给出，这里只给操作提示，避免同一组计数在屏内出现两遍
            CardHead("时间轴", "停留段可点开详情")
            Spacer(Modifier.height(t.space2))
            // 段列表：由整页统一跟手滚动，卡内不做独立滚动，避免嵌套吃手势
            // 倒序（最新在上）：只改显示顺序，域层 segments 顺序与导出不动
            val rows = remember(timeline.segments) { timelineRowsNewestFirst(timeline.segments) }
            rows.forEachIndexed { index, segment ->
                SegmentRow(
                    segment = segment,
                    anchor = timelineAnchorFor(index, rows.size),
                    dayStart = dayStart,
                    hasDivider = index > 0,
                    onSelect = onSelect,
                )
            }
        }
    }
}

/**
 * 段级锚点（9 段：home-am / move-1 / cafe / move-2 / office / move-3 / home-noon / gap-1 / home-pm）。
 *
 * 原型按示例数据画死「家 00:00–09:10 / 移动 / 咖啡 / …」，真实一天段数不定；锚点跟随**段的身份**：
 * 倒序渲染后视觉第 i 行按时间序第 (段数 - 1 - i) 段去查锚点表，所以 9 段时最上一行（最晚）是
 * s16-seg-home-pm、最下一行（最早）是 s16-seg-home-am，与原型块一一对应。
 * 时间序第 10 段起不打锚点：锚点集合严格等于账本定义，不新增自增号（一经发布不改号、不新增）。
 * 锚点名表在 TripTimelineOrder.kt（唯一真源），由 timelineAnchorFor 换算。
 */
private fun Modifier.segmentTag(anchor: String?): Modifier =
    anchor?.let { testTag(it) } ?: this

/**
 * 单段一行：时间列 + 类型图标 + 标题 / 副标题（停留段另带 ›）。
 *
 * 交互口径（锚点 s16-seg-*）：
 * - 停留段可点：按下**行底色加深一档**（dur-state 120ms）+ LIGHT，抬指打开详情抽屉；
 * - 移动段 / 未记录段不可点：无点击区、无高亮、无 toast，行尾也没有 ›（未记录段整行降为次要色）。
 *
 * 行盒按原型钉死（[SEG_H]），文字用 unbounded 溢出绘制 —— CJK 自然行盒（17sp → 25）会把每段撑高
 * 六七个 dp，九段累计到 22（台账里的 s16-seg-* h 差就是这么来的）。
 */
@Composable
private fun SegmentRow(
    segment: DaySegment,
    anchor: String?,
    dayStart: Long,
    hasDivider: Boolean,
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
            .height(SEG_H)
            // 原型 border-top：线画在段行内部（段高含这 1px），首段没有
            .drawBehind {
                if (hasDivider) {
                    val y = 0.5.dp.toPx()
                    drawLine(t.borderSoft, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
            }
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
            .padding(vertical = t.space3),
        verticalAlignment = Alignment.Top,
    ) {
        // 时间列：起止各一行，等宽数字（原型 .s16-seg-time：46 宽、12px、行首 padding-top 2）
        Column(Modifier.width(SEG_TIME_COL).padding(top = 2.dp)) {
            Text(
                text = formatSpanClock(segment.start, dayStart),
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = formatSpanClock(segment.end, dayStart),
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(t.space3))
        Box(
            Modifier
                .size(SEG_ICON)
                .clip(RoundedCornerShape(t.radiusMd))
                .background(tint.copy(alpha = tint.alpha * 0.12f)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(17.dp))
        }
        Spacer(Modifier.width(t.space3))
        Column(
            Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Box(Modifier.height(SEG_TITLE_LINE)) {
                Text(
                    text = segmentTitle(segment),
                    style = MaterialTheme.typography.titleMedium,
                    color = if (segment is Gap) t.muted else t.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .wrapContentHeight(Alignment.Top, unbounded = true)
                        .testTag("row.segment"),
                )
            }
            Box(Modifier.height(SEG_SUB_LINE)) {
                Text(
                    text = segmentSubtitle(segment),
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
                )
            }
        }
        if (stay != null) {
            Icon(
                Icons.Outlined.NavigateNext,
                contentDescription = "查看停留详情",
                tint = t.muted,
                modifier = Modifier.size(SEG_CHEV),
            )
        } else {
            Spacer(Modifier.width(SEG_CHEV))
        }
    }
}
