package com.bsfeng.monitor.ui.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 五态容器（契约 §8）：loading 骨架 / empty / error / populated / edge。
 * 每个数据面都能演示五态，W1/W2/W3 直接复用，不要各屏手写空态。
 *
 * 形态对齐原型 `.open-design/parts/00-shell.html`：
 * 骨架 = 细行 shimmer（.sk，450-458 行）；空/错态 = 居中列 + 44px 图标 + 17sp 标题 + 14sp 描述 + CTA（.st-*，428-464 行）。
 */
enum class FiveState { LOADING, EMPTY, ERROR, POPULATED, EDGE }

/** 原型 .sk：height 12px（.sk.tall 20px）、radius-sm、1.4s 线性 shimmer。 */
private val SKELETON_H = 12.dp
private val SKELETON_H_TALL = 20.dp
private val SKELETON_WIDTHS = listOf(1f, 0.8f, 0.6f, 0.4f)
private const val SHIMMER_MS = 1400
private const val SHIMMER_PHASE_STATIC = 0.5f

/** 原型 .st-icon：44px 方块 + 22px 图标、radius-md。 */
private val STATE_ICON = 44.dp

/** 错态 CTA 默认文案（保持 W1–W5 既有 8 个调用点行为不变）。 */
private const val ERROR_ACTION_DEFAULT = "重试"

@Composable
fun StateContainer(
    state: FiveState,
    modifier: Modifier = Modifier,
    emptyText: String = "暂无数据",
    errorText: String = "加载失败",
    edgeText: String = "边界态：超长文案 / 缺失字段 / 10000+ 计数",
    onRetry: (() -> Unit)? = null,
    emptyTitle: String = "空空如也",
    errorTitle: String = "出错",
    emptyActionLabel: String? = null,
    onEmptyAction: (() -> Unit)? = null,
    /**
     * 错态 CTA 文案（原型 s11-whitelist.html:131 为「重新扫描」）。
     * 默认 null = 沿用旧行为「重试」；传值即可按原型覆盖（s11 白名单页用）。
     */
    errorActionLabel: String? = null,
    /** 空态图标覆盖（原型各屏空态图标不同）；默认 null = 沿用 [EmptyState] 的 Widgets。 */
    emptyIcon: ImageVector? = null,
    edgeContent: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    Box(modifier.fillMaxWidth()) {
        when (state) {
            FiveState.LOADING -> StateSkeleton()
            FiveState.EMPTY -> EmptyState(
                text = emptyText,
                title = emptyTitle,
                icon = emptyIcon ?: Icons.Outlined.Widgets,
                actionLabel = emptyActionLabel,
                onAction = onEmptyAction,
            )
            FiveState.ERROR -> ErrorState(
                text = errorText,
                onRetry = onRetry,
                title = errorTitle,
                actionLabel = errorActionLabel ?: ERROR_ACTION_DEFAULT,
            )
            FiveState.EDGE -> EdgeState(edgeText, content = edgeContent)
            FiveState.POPULATED -> content()
        }
    }
}

/** 骨架屏：细行 shimmer（原型 .sk：12 / 20px 高、宽 40/60/80/100%、radius-sm）。 */
@Composable
fun StateSkeleton(rows: Int = 3, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val transition = rememberInfiniteTransition(label = "skeleton")
    val phase by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(SHIMMER_MS, easing = LinearEasing), RepeatMode.Restart),
        label = "skeletonShimmer",
    )
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(t.space2)) {
        repeat(rows) { i ->
            SkeletonRow(
                height = if (i % 3 == 1) SKELETON_H_TALL else SKELETON_H,
                widthFraction = SKELETON_WIDTHS[i % SKELETON_WIDTHS.size],
                phase = phase,
            )
        }
    }
}

/** 细行骨架（原型 .sk）：底 = fg 6% → 13% → 6% 横向渐变，按 [phase] 位移出 shimmer。 */
@Composable
fun SkeletonRow(
    height: Dp,
    widthFraction: Float = 1f,
    phase: Float = SHIMMER_PHASE_STATIC,
    alpha: Float = 1f,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val base = t.fgFade(6).let { if (alpha == 1f) it else it.copy(alpha = it.alpha * alpha) }
    val peak = t.fgFade(13).let { if (alpha == 1f) it else it.copy(alpha = it.alpha * alpha) }
    Box(
        modifier
            .fillMaxWidth(widthFraction.coerceIn(0.05f, 1f))
            .height(height)
            .clip(RoundedCornerShape(t.radiusSm))
            .drawBehind {
                val span = size.width * 2.2f
                val start = size.width * 1.2f - span * phase.coerceIn(0f, 1f)
                drawRect(Brush.horizontalGradient(listOf(base, peak, base), startX = start, endX = start + span))
            }
    )
}

/** 单块骨架（兼容旧调用点：满宽）。 */
@Composable
fun SkeletonBlock(height: Dp, alpha: Float = 1f, modifier: Modifier = Modifier) {
    SkeletonRow(height = height, widthFraction = 1f, alpha = alpha, modifier = modifier)
}

/** 空态：居中列 + 44dp 图标 + 17sp 标题 + 14sp 描述 + 可选 CTA（原型 .st-empty）。 */
@Composable
fun EmptyState(
    text: String,
    modifier: Modifier = Modifier,
    title: String = "空空如也",
    icon: ImageVector = Icons.Outlined.Widgets,
    iconSize: Dp = STATE_ICON,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    StateColumn(
        title = title,
        desc = text,
        icon = icon,
        iconSize = iconSize,
        danger = false,
        actionLabel = actionLabel,
        onAction = onAction,
        modifier = modifier,
    )
}

/** 错态：同空态结构，图标/标题走 danger 色（原型 .st-error）。 */
@Composable
fun ErrorState(
    text: String,
    onRetry: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
    title: String = "出错",
    icon: ImageVector = Icons.Filled.Warning,
    iconSize: Dp = STATE_ICON,
    actionLabel: String? = "重试",
) {
    StateColumn(
        title = title,
        desc = text,
        icon = icon,
        iconSize = iconSize,
        danger = true,
        actionLabel = if (onRetry != null) actionLabel else null,
        onAction = onRetry,
        modifier = modifier,
    )
}

/** 边界态：超长文案 + 10000+ 计数；[content] 可替换默认极值样例。 */
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
                Badge("10,000+", BadgeTone.Accent)
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
        if (actionLabel != null && onAction != null) {
            GhostButton(actionLabel, onAction)
        }
    }
}

/** 幽灵按钮（原型 .btn-ghost）：透明底 + 1px --border 描边 + min-height 48 + 17sp/600。 */
@Composable
private fun GhostButton(label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    Box(
        modifier = modifier
            .heightIn(min = 48.dp)
            .clip(shape)
            .border(1.dp, t.border, shape)
            .clickable { onClick() }
            .padding(horizontal = t.space5),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = t.fg)
    }
}
