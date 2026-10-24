package com.bsfeng.monitor.ui.components

// 从 ui/components/Controls.kt 拆出：chip 与分段控件 —— ChipRow（横滚单行）、Chip（单选标签）、SegmentedControl（分段切换）。负责「选项切换」这一类控件。

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

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
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
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
            .pressFeedback(interaction)
            .clip(RoundedCornerShape(t.radiusPill))
            .background(bg)
            .border(1.dp, stroke, RoundedCornerShape(t.radiusPill))
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                // 选中切换属轻触级：震动收敛在控件内，调用点不再各补一次
                haptic(HapticTone.LIGHT)
                onClick()
            }
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
