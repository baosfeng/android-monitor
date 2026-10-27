package com.bsfeng.monitor.ui.components

// 从 ui/components/Controls.kt 拆出：开关与选择器 —— AppSwitch（拨动开关）、AppFab（悬浮按钮）、ThemeOpt（主题选项行）。负责「改变一个值」的交互件。

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.gpuSlideX
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 开关外层触达尺寸（纯函数，便于单测）：视觉轨道尺寸不动，点击域取 max(视觉, tapMin)。
 *
 * 结果再经 material3 `minimumInteractiveComponentSize()` 托底到 48dp；契约下限是 44×44。
 */
internal fun switchTouchSize(trackW: Dp, trackH: Dp, tapMin: Dp): Pair<Dp, Dp> =
    trackW.coerceAtLeast(tapMin) to trackH.coerceAtLeast(tapMin)

/** 开关（自绘，规格来自 --switch-* / --knob-size）。 */
@Composable
fun AppSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val trackColor by animateColorAsState(
        // 关闭态轨道用前景 18% 覆盖：border-soft 在浅色主题里只有 6% 透明度，
        // 白 knob 落在白卡片上几乎看不见（用户会以为这里没有开关）。
        targetValue = if (checked) t.accent else t.fgFade(18),
        animationSpec = tween(t.durState),
        label = "swTrack",
    )
    // 滑块位移走绘制期（gpuSlideX 读 layer 内 lambda）：offset(x = 动画值) 会让每帧
    // measure/layout，开关切换与列表滚动时都白占主线程。
    val knobOffset = animateDpAsState(
        targetValue = if (checked) t.switchTrackW - t.knobSize - 4.dp else 4.dp,
        animationSpec = tween(t.durState),
        label = "swKnob",
    )
    // 触达口径（契约 §二「可点元素点击区 ≥ 44×44dp」）：视觉轨道保持 52×32 不变，
    // 点击域由外层撑开——minimumInteractiveComponentSize 兜住 Material 的 48dp 下限，
    // 外层 size 取 max(视觉, tapMin)；视觉轨道另画在内层，尺寸与轨道动画完全不动。
    val (touchW, touchH) = switchTouchSize(t.switchTrackW, t.switchTrackH, t.tapMin)
    Box(
        modifier = modifier
            .minimumInteractiveComponentSize()
            .size(width = touchW, height = touchH)
            .clickable {
                // 开关切换是轻触级；调用点此前各自补 performConfirmHaptic，现收敛到控件内
                haptic(HapticTone.LIGHT)
                onCheckedChange(!checked)
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .size(width = t.switchTrackW, height = t.switchTrackH)
                .clip(RoundedCornerShape(t.switchTrackH / 2))
                .background(trackColor),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .gpuSlideX { knobOffset.value }
                    .size(t.knobSize)
                    .clip(CircleShape)
                    .background(t.accentOn)
            )
        }
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
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(t.fabSize)
            .pressFeedback(interaction)
            .clip(CircleShape)
            .background(t.accent)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                // 主操作给中触（区别于列表点击的轻触），按下就出现缩放
                haptic(HapticTone.MEDIUM)
                onClick()
            },
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
