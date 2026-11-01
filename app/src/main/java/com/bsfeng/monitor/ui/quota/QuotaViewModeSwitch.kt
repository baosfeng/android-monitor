package com.bsfeng.monitor.ui.quota

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/** 一个分段项：显示文案 + 锚点（s18-mode-aggregate / s18-plan-legacy 等逐字照抄）。 */
internal data class QuotaSegment(val label: String, val tagModifier: Modifier)

/** 顶部视图分段的两个选项（顺序 = 显示顺序，索引 0 为聚合）。 */
private const val VIEW_AGGREGATE = "聚合"
private const val VIEW_SINGLE = "单家"

/** 分段内边距：外层给 44dp 点击区留出余量（原型 .segmented）。 */
private val SEGMENT_PAD = 3.dp

/**
 * 「聚合 / 单家」分段切换（原型 s20a 顶部 .segmented）。
 *
 * 放在 provider chip 上方而不是与 chip 同一行：两者都要整行宽度才不挤，
 * 而聚合模式下 chip 整行消失，切换器留在原地不会让内容上下跳。
 *
 * 为什么不直接用共享 [com.bsfeng.monitor.ui.components.SegmentedControl]：
 * s18 交互口径要求**每段各有自己的锚点**（s18-mode-aggregate / s18-mode-single），
 * 共享件只暴露整条容器的 modifier，段级 testTag 落不下去；这里保留同一套观感
 * （胶囊 + fg 7% 底 + 24% 描边 / 120ms）与键盘行为（整组一次 Tab、← → 移动、Home/End 跳首尾）。
 */
@Composable
internal fun ViewModeSwitch(
    aggregateMode: Boolean,
    onSelect: (Boolean) -> Unit,
) {
    val t = LocalDesignTokens.current
    QuotaSegmented(
        options = listOf(
            QuotaSegment(VIEW_AGGREGATE, Modifier.testTag("s18-mode-aggregate")),
            QuotaSegment(VIEW_SINGLE, Modifier.testTag("s18-mode-single")),
        ),
        selectedIndex = if (aggregateMode) 0 else 1,
        // 已是当前视图时点击不重播动画、不发请求、不重复震动
        onSelect = { index ->
            val next = index == 0
            if (next != aggregateMode) onSelect(next)
        },
        tone = HapticTone.MEDIUM,
        modifier = Modifier.padding(horizontal = t.space4).testTag("s18-mode"),
    )
}

/**
 * 带锚点的两段（或多段）分段控件：视图模式 / Ollama 计费模式共用。
 *
 * 选中态：底 fg 7% + 描边 24%（--dur-state 120ms）；未选中透明底 —— 容器已有 fg 5% 底，
 * 段再叠一层会糊成一片。
 */
@Composable
internal fun QuotaSegmented(
    options: List<QuotaSegment>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    tone: HapticTone,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusPill)
    val haptic = rememberHaptic()
    // roving tabindex：组内只让选中段可聚焦 → Tab 进组一次；方向键后把焦点送到新选中段
    val focusRequesters = remember(options.size) { List(options.size) { FocusRequester() } }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(t.tapMin + SEGMENT_PAD * 2)
            .clip(shape)
            .background(t.fgFade(5))
            .border(1.dp, t.borderSoft, shape)
            .padding(SEGMENT_PAD)
            .selectableGroup()
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val target = when (event.key) {
                    Key.DirectionLeft -> (selectedIndex - 1).coerceAtLeast(0)
                    Key.DirectionRight -> (selectedIndex + 1).coerceAtMost(options.lastIndex)
                    // Key.Home 是安卓系统 Home 键，会误吞返回桌面 → 用 MoveHome / MoveEnd
                    Key.MoveHome -> 0
                    Key.MoveEnd -> options.lastIndex
                    else -> return@onPreviewKeyEvent false
                }
                if (target != selectedIndex) {
                    haptic(tone)
                    onSelect(target)
                    focusRequesters[target].requestFocus()
                }
                // 导航键一律吞掉：不让父层滚动跟着跑
                true
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, option ->
            val selected = index == selectedIndex
            val interaction = remember { MutableInteractionSource() }
            val bg by animateColorAsState(
                targetValue = if (selected) t.fgFade(7) else Color.Transparent,
                animationSpec = tween(t.durState),
                label = "quotaSegmentBg",
            )
            val stroke by animateColorAsState(
                targetValue = if (selected) t.fgFade(24) else Color.Transparent,
                animationSpec = tween(t.durState),
                label = "quotaSegmentStroke",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .focusRequester(focusRequesters[index])
                    .focusProperties { canFocus = selected }
                    .clip(shape)
                    .background(bg)
                    .border(1.dp, stroke, shape)
                    .pressFeedback(interaction)
                    .keyboardFocusRing(shape)
                    .then(option.tagModifier)
                    .selectable(
                        selected = selected,
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                        role = Role.Tab,
                    ) {
                        if (!selected) {
                            haptic(tone)
                            onSelect(index)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    option.label,
                    // 文字固定不换行：两项等宽时「按金额（USD）」也压得下
                    style = MaterialTheme.typography.labelLarge,
                    color = if (selected) t.fg else t.fg2,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
