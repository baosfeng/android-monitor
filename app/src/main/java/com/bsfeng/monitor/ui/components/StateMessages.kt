package com.bsfeng.monitor.ui.components

// 从 ui/components/States.kt 拆出：空态 / 错态 / 边界态 —— EmptyState、ErrorState、EdgeState 与共用列布局 StateColumn、幽灵按钮 GhostButton（原型 .st-*）。负责「没数据 / 出错 / 极端值」时的说明与 CTA。

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.Widgets
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/** 原型 .st-icon：44px 方块 + 22px 图标、radius-md。 */
private val STATE_ICON = 44.dp

/** 错态 CTA 默认文案（保持 W1–W5 既有 8 个调用点行为不变）。 */
internal const val ERROR_ACTION_DEFAULT = "重试"

/**
 * 空态：居中列 + 44dp 图标 + 17sp 标题 + 14sp 描述 + 可选 CTA（原型 .st-empty）。
 *
 * @param modifier 块级锚点入口：调用方按契约传 `Modifier.testTag("sNN-empty")`，本组件不自带 tag。
 * @param actionLabel CTA 文案；与 [onAction] 任一为 null 时整颗按钮不渲染（未接线不伪造跳转）。
 * @param onAction CTA 回调；非 null 时按钮按契约给按下 0.96 + LIGHT 触感。
 * @param actionModifier CTA 级锚点入口（如 `sNN-empty-cta`），只在按钮渲染时生效。
 */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    title: String = "空空如也",
    icon: ImageVector = Icons.Outlined.Widgets,
    iconSize: Dp = STATE_ICON,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    actionModifier: Modifier = Modifier,
) {
    StateColumn(
        title = title,
        desc = text,
        icon = icon,
        iconSize = iconSize,
        danger = false,
        actionLabel = actionLabel,
        onAction = onAction,
        actionModifier = actionModifier,
        modifier = modifier,
    )
}

/**
 * 错态：同空态结构，图标/标题走 danger 色（原型 .st-error）。
 *
 * @param modifier 块级锚点入口（`sNN-error`）；不自带 tag。
 * @param onRetry 重试回调；为 null 时按钮不渲染（未接线不伪造重试）。
 * @param actionModifier 重试按钮的锚点入口（`sNN-error-retry`），只在按钮渲染时生效。
 */
@Composable
fun ErrorState(
    text: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null,
    title: String = "出错",
    icon: ImageVector = Icons.Filled.Warning,
    iconSize: Dp = STATE_ICON,
    actionLabel: String? = "重试",
    actionModifier: Modifier = Modifier,
) {
    StateColumn(
        title = title,
        desc = text,
        icon = icon,
        iconSize = iconSize,
        danger = true,
        actionLabel = if (onRetry != null) actionLabel else null,
        onAction = onRetry,
        actionModifier = actionModifier,
        modifier = modifier,
    )
}

/**
 * 边界态：超长文案 + 10000+ 计数；[content] 可替换默认极值样例。
 *
 * 纯展示、无 CTA（契约里边界态不接点击）；[modifier] 是块级锚点入口（`sNN-edge`）。
 */
@Composable
fun EdgeState(
    text: String,
    modifier: Modifier = Modifier,
    content: (@Composable () -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.radiusLg))
            .background(t.surfaceWarm)
            .padding(t.space4),
    ) {
        if (content != null) {
            content()
        } else {
            Text(
                "人情往来 · 红白喜事与节日红包（这是一条刻意超长的标签，用来验证换行与不裁切）",
                style = MaterialTheme.typography.bodySmall,
                color = t.fg2,
            )
            Spacer(Modifier.height(t.space2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("¥9,876,543.21", style = MaterialTheme.typography.titleMedium, color = t.fg)
                Spacer(Modifier.width(t.space3))
                Badge("10,000+", tone = BadgeTone.Accent)
            }
        }
        Spacer(Modifier.height(t.space1))
        Text(text, style = MaterialTheme.typography.labelSmall, color = t.meta)
    }
}

/** 空/错态共用的居中列（原型 .st-empty / .st-error 同构）。 */
@Composable
private fun StateColumn(
    title: String,
    desc: String,
    icon: ImageVector,
    iconSize: Dp,
    danger: Boolean,
    actionLabel: String?,
    onAction: (() -> Unit)?,
    actionModifier: Modifier = Modifier,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val iconBg = if (danger) t.danger.copy(alpha = t.danger.alpha * 0.14f) else t.fgFade(6)
    val iconTint = if (danger) t.dangerText else t.fg2
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4, vertical = t.space8),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(t.space3),
    ) {
        Box(
            Modifier
                .size(iconSize)
                .clip(RoundedCornerShape(t.radiusMd))
                .background(iconBg),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(iconSize / 2))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, color = if (danger) t.dangerText else t.fg)
        Text(
            desc,
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 240.dp),
        )
        // 未接线（label 或回调为 null）时整颗按钮不渲染：不伪造跳转、不占位。
        if (actionLabel != null && onAction != null) {
            GhostButton(actionLabel, onAction, actionModifier)
        }
    }
}

/**
 * 幽灵按钮（原型 .btn-ghost）：透明底 + 1px --border 描边 + min-height 48 + 17sp/600。
 *
 * 反馈按契约走现成基元：[pressFeedback] 按下 0.96（时长取 --dur-press、精简档自动省略）、
 * 触发时 [HapticTone.LIGHT]；两者都不手写常量。
 */
@Composable
private fun GhostButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .pressFeedback(interaction)
            // 键盘焦点环 2dp（契约头部通用口径）：放在剪裁之前，环不被圆角裁掉；
            // 只在输入方式是键盘 / 遥控器时绘制，触屏点击视觉零变化。
            .keyboardFocusRing(shape)
            .clip(shape)
            .border(1.dp, t.border, shape)
            .testTag("button.ghost")
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                // 普通按钮点击属轻触级：震动收敛在控件内，调用点不再各补一次
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .padding(horizontal = t.space5),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = t.fg)
    }
}
