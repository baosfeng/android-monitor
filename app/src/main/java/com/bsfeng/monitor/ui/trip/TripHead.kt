package com.bsfeng.monitor.ui.trip

// 从 ui/trip/TripScreen.kt 拆出：页头 —— TripHead（日期行 + 大标题 + 返回 + 前一天/后一天）。
// 结构对齐原型 #s16 header.screen-head：左列「.meta 日期 / .h1 标题」两行，右侧两个 44dp icon-btn。

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronLeft
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.formatFullDate
import com.bsfeng.monitor.ui.live.weekdayLabel
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/** 原型 .h1 行盒：28px × leading-tight 1.05 ≈ 29.4，取 30（同 OverviewHead 的取法）。 */
private val HEAD_TITLE_LINE = 30.dp

/**
 * 每日行程页头（锚点 s16-head / s16-prev / s16-next）。
 *
 * 原型 `header.screen-head` 是**一个** flex 行：左边 `<div><p class="meta">日期</p><h1 class="h1 mt-2">每日行程</h1></div>`，
 * 右边 `.s16-nav` 两个 .icon-btn；padding 8 / 16 / 12、align-items:flex-start，盒高 78.6。
 * 拆成「ScreenHead 标题在上 + 日期条在下」会把两个箭头顶到底部、页头高 27.7（台账 s16 十二条的根因）。
 *
 * 返回键是原型未画的增量（原型靠外壳手势返回，实现是整屏 overlay）。它 32dp、矮于左列 58.6，
 * 挂在行首不撑高，因此不改变 s16-head 的盒高与两个箭头的 y。
 *
 * 换日时日期文案交叉淡入（锚点 s16-prev「内容 150ms 内整体替换，不播整页转场」）。
 */
@Composable
internal fun TripHead(
    dayStart: Long,
    now: Long,
    onBack: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier
            .fillMaxWidth()
            .testTag("s16-head")
            .statusBarsPadding()
            .padding(start = t.space4, end = t.space4, top = t.space2, bottom = t.space3),
        verticalAlignment = Alignment.Top,
    ) {
        BackButton(onBack)
        Spacer(Modifier.width(t.space2))
        Column(Modifier.weight(1f)) {
            Crossfade(
                targetState = dayStart,
                animationSpec = tween(t.durOverlayOut),
                label = "tripDayText",
            ) { day ->
                MetaText(
                    text = formatFullDate(day) + " · " + weekdayLabel(day),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            // 原型 .h1 的 mt-2 = 8；行盒钉 30 让 CJK 自然行盒（41）不把页头撑高
            Spacer(Modifier.height(t.space2))
            Box(Modifier.height(HEAD_TITLE_LINE)) {
                Text(
                    "每日行程",
                    style = MaterialTheme.typography.headlineMedium,
                    color = t.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true),
                )
            }
        }
        Spacer(Modifier.width(t.space3))
        DayArrow(
            icon = Icons.Outlined.ChevronLeft,
            description = "前一天",
            modifier = Modifier.testTag("s16-prev"),
            enabled = TripDays.canPrevious(dayStart, now),
            onClick = onPrevious,
        )
        Spacer(Modifier.width(t.space2))
        DayArrow(
            icon = Icons.Outlined.ChevronRight,
            description = "后一天",
            modifier = Modifier.testTag("s16-next"),
            enabled = TripDays.canNext(dayStart, now),
            onClick = onNext,
        )
    }
}

/** 返回键（32dp，全站二级页口径：0.96 按下 + LIGHT）。 */
@Composable
private fun BackButton(onBack: () -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(t.space8)
            .clip(RoundedCornerShape(t.radiusPill))
            .background(t.surfaceWarm)
            .pressFeedback(interaction)
            .testTag("button.back")
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                haptic(HapticTone.LIGHT)
                onBack()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Outlined.ChevronLeft,
            contentDescription = "返回",
            tint = t.fg,
            modifier = Modifier.size(t.space5),
        )
    }
}

/**
 * 日期箭头：点击区 44dp，按下 0.96 + LIGHT（锚点 s16-prev / s16-next）。
 *
 * 边界：已是最早一天 / 今天 → 底色 fgFade(4)、图标 muted、不可点、不弹提示（原因由灰态表达）。
 */
@Composable
private fun DayArrow(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(t.tapMin)
            .pressFeedback(interaction, enabled)
            .clip(RoundedCornerShape(t.radiusPill))
            .background(if (enabled) t.surfaceWarm else t.fgFade(4))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                enabled = enabled,
            ) {
                haptic(HapticTone.LIGHT)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = description,
            tint = if (enabled) t.fg else t.muted,
            modifier = Modifier.size(t.space5).testTag("button.icon"),
        )
    }
}
