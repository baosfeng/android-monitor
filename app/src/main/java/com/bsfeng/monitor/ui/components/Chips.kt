package com.bsfeng.monitor.ui.components

// 从 ui/components/Controls.kt 拆出：chip 与分段控件 —— ChipRow（横滚单行）、Chip（单选标签）、SegmentedControl（分段切换）。负责「选项切换」这一类控件。

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    val shape = RoundedCornerShape(t.radiusPill)
    Box(
        modifier = modifier
            // 触达红线（≥48dp）：短标签（「7 天」这类）只靠左右内边距会窄到 46.7dp，
            // 补最小宽度让点击域达标；视觉上是同一个 pill，只是最短的那个宽一点点。
            .widthIn(min = t.tapMin)
            .height(t.tapMin)
            .pressFeedback(interaction)
            // 键盘焦点环 2dp（契约 s01 通用口径）：与分段控件同一实现，仅键盘 / 遥控器输入时画；
            // 放在 clip 之前，环不被圆角裁掉，也不改变选中态视觉与点击行为。
            .keyboardFocusRing(shape)
            .clip(shape)
            .background(bg)
            .border(1.dp, stroke, shape)
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

/**
 * 组内导航键 → 目标下标（纯函数，便于把「到边界不回绕」直接单测钉死）。
 *
 * @param count 选项个数；≤ 0 返回 null（空组不接受键盘操作）
 * @param current 当前选中下标
 * @return 目标下标；null = 该键不归本组处理（Tab / Esc 等交还上层）
 */
internal fun segmentNavTarget(key: Key, current: Int, count: Int): Int? {
    if (count <= 0) return null
    return when (key) {
        Key.DirectionLeft -> (current - 1).coerceAtLeast(0)
        Key.DirectionRight -> (current + 1).coerceAtMost(count - 1)
        // 用 MoveHome / MoveEnd（键盘 Home / End），不是 Key.Home（安卓系统 Home 键）
        Key.MoveHome -> 0
        Key.MoveEnd -> count - 1
        else -> null
    }
}

/**
 * 分段控件（公共版；W2 应删掉 AddTransactionScreen 里的私有副本改调此版）。
 *
 * 键盘口径（契约 s12-chip-seg「分段控件 · 键盘」行）：
 *  - **Tab 只进组一次**：组挂 [focusGroup]，组内只有当前选中段可聚焦（roving tabindex，见
 *    `focusProperties { canFocus = selected }`），因此 Tab 进组即落选中段、不逐段停留；
 *  - **← / → 即时选中**、**Home / End 跳首尾**，到边界不回绕（[segmentNavTarget] 收敛夹紧）；
 *  - **焦点环 2dp 落当前选中段**：走 [keyboardFocusRing]，仅键盘输入时画；方向键切换后由
 *    `LaunchedEffect(pendingFocusIndex)` 在重组后把焦点送到新段（不在按键栈里零帧请求）；
 *  - **切换 LIGHT 一次**：选中真的变化才震，重复点当前段不震（同一操作不重复震动）。
 *
 * 视觉（pill / elev-1 / 120ms --dur-state 过渡）与鼠标、触屏点击行为不变。
 */
@Composable
fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    // 原型 .segmented：容器 pill + 1px --border-soft 描边 + 底 color-mix(fg 5%)；
    // 选中项 .is-on = --surface 底 + --fg 字 600 + --elev-1
    val shape = RoundedCornerShape(t.radiusPill)
    // 每段一个 requester：方向键移动后把焦点送到新选中段（否则焦点留在旧段、环画错位置）
    val focusRequesters = remember(options.size) { List(options.size) { FocusRequester() } }
    // 键盘切换后的焦点请求不能在按键栈里直接发：此刻新段的 canFocus 还是 false
    // （下面 focusProperties 的 canFocus 要等重组才按新的 selectedIndex 重算），请求会当场失败；
    // 重组后旧段 canFocus=false、请求也落了空 → 焦点被清空，第一次切换后方向键再也到不了分段。
    // 口径照 MainScreen.kt:357-364 / OverviewScreen.kt:327-333 的 s02 chips：先记「下次该聚焦谁」，
    // 等重组把 canFocus 算完，再在 keyed effect 里请求（LaunchedEffect 在组件树应用之后才启动）。
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingFocusIndex) {
        pendingFocusIndex?.let { index ->
            runCatching { focusRequesters.getOrNull(index)?.requestFocus() }
            pendingFocusIndex = null
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(t.tapMin + SEG_PAD * 2)
            .clip(shape)
            .background(t.fgFade(5))
            .border(1.dp, t.borderSoft, shape)
            .padding(SEG_PAD)
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val target = segmentNavTarget(event.key, selectedIndex, options.size)
                    ?: return@onPreviewKeyEvent false
                if (target != selectedIndex) {
                    haptic(HapticTone.LIGHT)
                    onSelect(target)
                }
                // 不在按键栈里零帧 requestFocus：先切状态、记下目标段，请求交给重组后的 effect
                pendingFocusIndex = target
                // 导航键一律吞掉：到边界不回绕，也不让父层滚动跟着跑
                true
            },
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
                    .focusRequester(focusRequesters[index])
                    // roving tabindex：只有选中段可聚焦 → Tab 进组一次，方向键由组统一处理
                    .focusProperties { canFocus = selected }
                    .keyboardFocusRing(shape)
                    .tokenShadow(if (selected) 1 else 0, shape)
                    .clip(shape)
                    .background(bg)
                    .clickable {
                        // 切换属轻触级；重复点当前段不再震
                        if (!selected) haptic(HapticTone.LIGHT)
                        onSelect(index)
                    },
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
