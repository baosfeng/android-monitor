package com.bsfeng.monitor.ui

// 从 ui/AdScreen.kt 拆出：广告跳过页的两个「区块」—— 摘要条与筛选 chip 组。
// 页面骨架、状态机、记录列表留在 AdScreen.kt；这里只放可独立读的版式块，接收已算好的值。
// 拆分原因：AdScreen.kt 原 744 行，重排后再塞三个新区块已超单文件可读性上限。

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AnimatedNumber
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.BarChart
import com.bsfeng.monitor.ui.components.ChartAxisLabels
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.live.CardHead
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 筛选 chip 组（本页局部实现，不改共享 ChipRow）。
 *
 * 与共享 ChipRow 的差异： 1) FlowRow 自适应折行而不是横滑（四个带计数的选项在窄屏必然超宽）；
 * 2) 带单选组语义（selectableGroup + Role.RadioButton），读屏能念出「已选中」，符合无障碍红线 4。
 */
@Composable
internal fun AdMethodChips(
    items: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    FlowRow(
        modifier = modifier
            .fillMaxWidth()
            .selectableGroup(),
        horizontalArrangement = Arrangement.spacedBy(t.space2),
        verticalArrangement = Arrangement.spacedBy(t.space2),
    ) {
        items.forEachIndexed { index, label ->
            AdMethodChip(
                label = label,
                selected = index == selectedIndex,
                onClick = { onSelect(index) },
            )
        }
    }
}

/** 单个筛选 chip：选中态走中性 fg 混合色，不占强调色预算；触达 44dp。 */
@Composable
internal fun AdMethodChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusPill)
    val bg by animateColorAsState(
        targetValue = if (selected) t.fgFade(7) else Color.Transparent,
        animationSpec = tween(t.durState),
        label = "adChipBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) t.fg else t.fg2,
        animationSpec = tween(t.durState),
        label = "adChipFg",
    )
    val stroke by animateColorAsState(
        targetValue = if (selected) t.fgFade(24) else t.border,
        animationSpec = tween(t.durState),
        label = "adChipStroke",
    )
    Box(
        modifier = Modifier
            .widthIn(min = t.tapMin)
            .height(t.tapMin)
            .pressFeedback(interaction)
            .keyboardFocusRing(shape)
            .clip(shape)
            .background(bg)
            .border(1.dp, stroke, shape)
            .testTag("chip.option")
            // 单选语义：读屏念「已选中 / 未选中」，不靠颜色单独表达状态
            .selectable(
                selected = selected,
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.RadioButton,
            ) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            // 左右内边距对齐原型 .chip 的 --space-3：space4 会让四个带计数的选项在 400dp 下
            // 溢出换行（实测两行），space3 刚好一行放下；更窄屏仍由 FlowRow 折行兜底。
            .padding(horizontal = t.space3),
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

/**
 * 摘要条：单行「近 7 天 N 次」+ 右侧「跳过规则 ›」。
 *
 * 原设计是一张含大数字 + 三个 [StatBox] + 满宽主按钮的大卡，真机上三个格子有两个恒为「—」，
 * 且把记录挤到第二屏。这里压成一行：卡高 = tap-min(44) + space-1 上下各一，约 52dp。
 */
@Composable
internal fun AdSummaryStrip(
    totalCount: Int,
    replayTick: Int,
    onReplay: () -> Unit,
    onOpenRules: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val numberInteraction = remember { MutableInteractionSource() }
    // 卡内边距走 space-1（上下）：卡高 = tap-min 44 + 上下各 4 ≈ 52dp，见原型 .card.s03-sum。
    AppCard(modifier = modifier, padding = t.space1) {
        Row(
            Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 大数字点按重播滚动：点击区就是整块「近 7 天 34 次」，纵向撑满 tap-min。
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = t.tapMin)
                    .keyboardFocusRing(RoundedCornerShape(t.radiusSm))
                    .clickable(
                        interactionSource = numberInteraction,
                        indication = LocalIndication.current,
                    ) { onReplay() },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MetaText("近 7 天")
                Spacer(Modifier.width(t.space1))
                key(replayTick) {
                    AnimatedNumber(
                        value = totalCount,
                        style = MaterialTheme.typography.headlineSmall,
                        color = t.fg,
                    )
                }
                Spacer(Modifier.width(t.space1))
                Text("次", style = MaterialTheme.typography.labelMedium, color = t.fg2)
            }
            // 规则入口仍是 44dp 触达：文本视觉小，点击域与数字块等高、不给相邻控件吞掉。
            Row(
                Modifier
                    .heightIn(min = t.tapMin)
                    .testTag("s03-rules")
                    .keyboardFocusRing(RoundedCornerShape(t.radiusSm))
                    .clip(RoundedCornerShape(t.radiusSm))
                    .clickable { onOpenRules() }
                    .padding(horizontal = t.space1),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("跳过规则", style = MaterialTheme.typography.labelLarge, color = t.fg2)
                Icon(
                    Icons.Outlined.ChevronRight,
                    contentDescription = null, // decorative：同行已有「跳过规则」文本
                    tint = t.fg2,
                    modifier = Modifier.size(t.space4),
                )
            }
        }
    }
}

/** 趋势卡：卡头区间 + 单色柱 + 轴标签 + 峰值说明 + 回顾性尾注；柱子不可点选。 */
@Composable
internal fun AdTrendBlock(
    dailyCounts7: List<Float>,
    labels: List<String>,
    rangeLabel: String,
    extras: String,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier) {
        CardHead("近 7 天跳过趋势", rangeLabel)
        Spacer(Modifier.height(t.space3))
        BarChart(
            values = dailyCounts7,
            modifier = Modifier
                .fillMaxWidth()
                .height(t.chartH),
        )
        ChartAxisLabels(labels)
        Spacer(Modifier.height(t.space2))
        MetaText(peakNote(dailyCounts7))
        if (extras.isNotEmpty()) {
            Spacer(Modifier.height(t.space1))
            MetaText(extras)
        }
    }
}