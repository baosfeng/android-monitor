package com.bsfeng.monitor.ui

// 从 ui/ComponentGalleryScreen.kt 拆出（W1 五屏共用的原型通用件）：pressScale 按压系数、AppButtonVariant 与 AppButton（主/次/块级按钮）、AppIconButton（44dp 触达）。负责「点一下就发生」的按钮面。
// 这些件原本暂存在展厅文件里，现按职责拆出；仍是 ui 包内的共用件（internal）。

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

@Composable
internal fun pressScale(pressed: Boolean): Float {
    val t = LocalDesignTokens.current
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = t.springOf(MotionToken.Press),
        label = "pressScale",
    )
    return scale
}

internal enum class AppButtonVariant { Primary, Ghost }

/** AppButton 默认触感（契约 §三）：普通按钮 = LIGHT；主操作 SUCCESS / 破坏性 REJECT 由调用方显式指定。 */
internal val DEFAULT_APP_BUTTON_TONE: HapticTone = HapticTone.LIGHT

/**
 * 主/次按钮（原型 .btn / .btn-ghost / .btn-block / :disabled）。
 *
 * 触感（契约 §三）：默认 [HapticTone.LIGHT]（普通按钮）；**主操作确认**由调用方显式传
 * `tone = HapticTone.SUCCESS`，**破坏性操作**（删除 / 取消传输）传 `tone = HapticTone.REJECT`。
 * 同一操作不重复震动：调用方不要再自己补震。
 */
@Composable
internal fun AppButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    variant: AppButtonVariant = AppButtonVariant.Primary,
    enabled: Boolean = true,
    block: Boolean = false,
    tone: HapticTone = DEFAULT_APP_BUTTON_TONE,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale = pressScale(pressed && enabled)
    val bg = if (variant == AppButtonVariant.Primary) t.accent else t.surface
    val fg = if (variant == AppButtonVariant.Primary) t.accentOn else t.fg
    Box(
        modifier = modifier
            .then(if (block) Modifier.fillMaxWidth() else Modifier)
            .height(t.tapMin)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(RoundedCornerShape(t.radiusMd))
            .background(bg)
            .border(1.dp, if (variant == AppButtonVariant.Primary) t.accent else t.border, RoundedCornerShape(t.radiusMd))
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = {
                    haptic(tone)
                    onClick()
                },
            )
            .padding(horizontal = t.space4),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) fg else t.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * 图标按钮（原型 .icon-btn，44dp 触达）。
 *
 * 触感（契约 §三）与 [AppButton] 同口径：默认 [HapticTone.LIGHT]（普通按钮）；
 * 主操作确认传 [HapticTone.SUCCESS]，破坏性操作传 [HapticTone.REJECT]。
 */
@Composable
internal fun AppIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: HapticTone = DEFAULT_APP_BUTTON_TONE,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    Box(
        modifier = modifier
            .size(t.tapMin)
            .clip(RoundedCornerShape(t.radiusMd))
            .background(t.surfaceWarm)
            .clickable {
                haptic(tone)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = contentDescription, tint = t.fg, modifier = Modifier.size(t.space5))
    }
}
