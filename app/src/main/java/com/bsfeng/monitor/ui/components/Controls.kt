package com.bsfeng.monitor.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import com.bsfeng.monitor.ui.theme.monitorMonoBadge

/**
 * 共享控件基元：chip 行 / badge / 分段控件 / 开关 / FAB / theme-opt / legend。
 * 全部走 token，本文件不含任何色值。
 */

/** 原型 .badge：min-height / min-width 20px、padding 0 6px。 */
private val BADGE_H = 20.dp
private val BADGE_PAD_X = 6.dp

/** 原型 .segmented：容器 padding 4px、项间距 4px。 */
private val SEG_PAD = 4.dp

/** 单选 chip 行（类型筛选等）。 */
@Composable
fun ChipRow(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(t.space2),
    ) {
        items.forEachIndexed { index, label ->
            Chip(label = label, selected = index == selectedIndex, onClick = { onSelect(index) })
        }
    }
}

@Composable
fun Chip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    // 原型 .chip.is-on：底 color-mix(fg 7%) / 描边 color-mix(fg 24%) / 字 --fg —— 中性，不占强调色预算
    val bg by animateColorAsState(
        targetValue = if (selected) t.fgFade(7) else Color.Transparent,
        animationSpec = tween(t.durState),
        label = "chipBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) t.fg else t.fg2,
        animationSpec = tween(t.durState),
        label = "chipFg",
    )
    val stroke by animateColorAsState(
        targetValue = if (selected) t.fgFade(24) else t.border,
        animationSpec = tween(t.durState),
        label = "chipStroke",
    )
    Box(
        modifier = modifier
            .height(t.tapMin)
            .clip(RoundedCornerShape(t.radiusPill))
            .background(bg)
            .border(1.dp, stroke, RoundedCornerShape(t.radiusPill))
            .clickable { onClick() }
            .padding(horizontal = t.space4),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium,
            color = fg,
        )
    }
}

enum class BadgeTone { Neutral, Accent, Success, Warn, Danger }

@Composable
fun Badge(text: String, tone: BadgeTone = BadgeTone.Neutral, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val color = when (tone) {
        BadgeTone.Neutral -> t.muted
        BadgeTone.Accent -> t.accent
        BadgeTone.Success -> t.successText
        BadgeTone.Warn -> t.warnText
        BadgeTone.Danger -> t.dangerText
    }
    // 原型 .badge：min-width/height 20、padding 0 6px、底 color-mix(fg 9%)、无描边、等宽 11sp
    Box(
        modifier = modifier
            .heightIn(min = BADGE_H)
            .widthIn(min = BADGE_H)
            .clip(RoundedCornerShape(t.radiusPill))
            .background(t.fgFade(9))
            .padding(horizontal = BADGE_PAD_X),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = monitorMonoBadge, color = color)
    }
}

/** 分段控件（公共版；W2 应删掉 AddTransactionScreen 里的私有副本改调此版）。 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    // 原型 .segmented：容器 pill + 1px --border-soft 描边 + 底 color-mix(fg 5%)；
    // 选中项 .is-on = --surface 底 + --fg 字 600 + --elev-1
    val shape = RoundedCornerShape(t.radiusPill)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(t.tapMin + SEG_PAD * 2)
            .clip(shape)
            .background(t.fgFade(5))
            .border(1.dp, t.borderSoft, shape)
            .padding(SEG_PAD),
        horizontalArrangement = Arrangement.spacedBy(SEG_PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val bg by animateColorAsState(
                targetValue = if (selected) t.surface else Color.Transparent,
                animationSpec = tween(t.durState),
                label = "segBg",
            )
            val fg by animateColorAsState(
                targetValue = if (selected) t.fg else t.muted,
                animationSpec = tween(t.durState),
                label = "segFg",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(t.tapMin)
                    .tokenShadow(if (selected) 1 else 0, shape)
                    .clip(shape)
                    .background(bg)
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = fg,
                )
            }
        }
    }
}

/** 开关（自绘，规格来自 --switch-* / --knob-size）。 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val trackColor by animateColorAsState(
        targetValue = if (checked) t.accent else t.borderSoft,
        animationSpec = tween(t.durState),
        label = "swTrack",
    )
    val knobOffset by animateDpAsState(
        targetValue = if (checked) t.switchTrackW - t.knobSize - 4.dp else 4.dp,
        animationSpec = tween(t.durState),
        label = "swKnob",
    )
    Box(
        modifier = modifier
            .size(width = t.switchTrackW, height = t.switchTrackH)
            .clip(RoundedCornerShape(t.switchTrackH / 2))
            .background(trackColor)
            .clickable { onCheckedChange(!checked) },
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .offset(x = knobOffset)
                .size(t.knobSize)
                .clip(CircleShape)
                .background(t.accentOn)
        )
    }
}

/** FAB：禁止外包 inset 高度容器（见踩坑记录）。 */
@Composable
fun AppFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector = Icons.Filled.Add,
    contentDescription: String = "新增",
) {
    val t = LocalDesignTokens.current
    Box(
        modifier = modifier
            .size(t.fabSize)
            .clip(CircleShape)
            .background(t.accent)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = t.accentOn, modifier = Modifier.size(t.iconSize / 2))
    }
}

/** 主题选项卡：预览 3 个色块 + 名称 + 选中描边（s13 用）。 */
@Composable
fun ThemeOpt(
    id: String,
    title: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val preview = DesignTokens(id)
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(t.radiusLg))
            .background(t.surface)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) t.accent else t.borderSoft,
                shape = RoundedCornerShape(t.radiusLg),
            )
            .clickable { onClick() }
            .padding(t.space3),
    ) {
        Column {
            Row {
                Box(Modifier.size(t.space6).clip(CircleShape).background(preview.surface).border(1.dp, preview.border, CircleShape))
                Spacer(Modifier.width(t.space1))
                Box(Modifier.size(t.space6).clip(CircleShape).background(preview.accent))
                Spacer(Modifier.width(t.space1))
                Box(Modifier.size(t.space6).clip(CircleShape).background(preview.fg))
            }
            Spacer(Modifier.height(t.space2))
            Text(title, style = MaterialTheme.typography.titleSmall, color = t.fg)
            Text(id, style = MaterialTheme.typography.labelSmall, color = t.meta)
        }
    }
}

/** 图例数据：标签 + 右侧数值 + 色块。 */
data class LegendItem(val label: String, val value: String, val color: Color)

/** 图例列表（.legend：10px 圆角方块 + 标签 + 右侧等宽数值）。 */
@Composable
fun Legend(items: List<LegendItem>, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(t.space2)) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(item.color)
                )
                Spacer(Modifier.width(t.space2))
                Text(
                    item.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.fg2,
                    modifier = Modifier.weight(1f),
                )
                Text(item.value, style = MaterialTheme.typography.labelMedium, color = t.muted)
            }
        }
    }
}

/** 触达尺寸保证的点击行封装。 */
@Composable
fun TapRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp? = null,
    content: @Composable () -> Unit,
) {
    val t = LocalDesignTokens.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(minHeight ?: t.rowMinH)
            .clickable { onClick() },
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}
