package com.bsfeng.monitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.sparkHeight
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Shape
import kotlinx.coroutines.delay

/**
 * 共享面（surface）基元：卡片 / 玻璃卡 / KPI 卡 / 分组头 / scrim / 底部 sheet / toast。
 * 全部走 token，本文件不含任何色值。
 */

/** 页面底衬：浅色玻璃必须压在可见底衬上才成立（契约 §4）。 */
@Composable
fun Modifier.appBackdrop(): Modifier {
    val t = LocalDesignTokens.current
    return this.drawBehind {
        drawRect(
            Brush.verticalGradient(
                listOf(t.backdropA, t.backdropB),
                startY = 0f,
                endY = size.height,
            )
        )
    }
}

/**
 * 层级阴影（原型 --elev-1/2/3）：Compose 侧用 shadow 近似 CSS box-shadow，
 * 色相取 --fg 的透明度（1 → 0 1px 2px fg 7%；2 → 0 10px 28px -10px fg 22%；3 → 0 22px 56px -18px fg 34%）。
 */
@Composable
fun Modifier.tokenShadow(level: Int, shape: Shape): Modifier {
    if (level <= 0) return this
    val t = LocalDesignTokens.current
    val elevation = when (level) {
        1 -> 1.dp
        2 -> 10.dp
        else -> 22.dp
    }
    val alpha = when (level) {
        1 -> 0.07f
        2 -> 0.22f
        else -> 0.34f
    }
    val c = t.fg.copy(alpha = t.fg.alpha * alpha)
    return this.shadow(elevation = elevation, shape = shape, clip = false, ambientColor = c, spotColor = c)
}

/** 普通卡：surface 底 + 1px 描边（原型 .card）；[elevation] = 1 时叠加 --elev-1。 */
@Composable
fun AppCard(
    modifier: Modifier = Modifier,
    padding: Dp? = null,
    onClick: (() -> Unit)? = null,
    elevation: Int = 0,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    val p = padding ?: t.space4
    Column(
        modifier = modifier
            .fillMaxWidth()
            .tokenShadow(elevation, shape)
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(p),
        content = content,
    )
}

/** 玻璃卡：surface 72% 透明 + 8% fg 描边 + 顶部高光（真模糊需 API31 backdrop，这里以底衬+半透明等价表达）。 */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    padding: Dp? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    val p = padding ?: t.space4
    Column(
        modifier = modifier
            .fillMaxWidth()
            .tokenShadow(1, shape)
            .clip(shape)
            .background(t.glassBg)
            .border(1.dp, t.glassBorder, shape)
            // 原型 .glass：box-shadow inset 0 1px 0 var(--glass-highlight) —— 顶部 1px 高光内描边
            .drawBehind {
                drawRect(
                    color = t.glassHighlight,
                    topLeft = Offset.Zero,
                    size = Size(size.width, 1.dp.toPx()),
                )
            }
            .padding(p),
        content = content,
    )
}

/** 分组头（分组列表上方的小标题 + 右侧动作）。 */
@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(start = t.space4, end = t.space4, top = t.space4, bottom = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelMedium,
            color = t.muted,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

/**
 * KPI 卡：数值 + 单位 + 环比 + 可选 sparkline。
 * @param spark 迷你走势数据；null 表示不画。
 */
@Composable
fun KpiCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    delta: String? = null,
    deltaUp: Boolean? = null,
    spark: List<Float>? = null,
) {
    val t = LocalDesignTokens.current
    AppCard(modifier = modifier) {
        Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = t.meta)
        Spacer(Modifier.height(t.space2))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                color = t.fg,
            )
            if (unit != null) {
                Spacer(Modifier.width(t.space1))
                Text(unit, style = MaterialTheme.typography.labelMedium, color = t.muted)
            }
            if (delta != null) {
                Spacer(Modifier.weight(1f))
                val c = when (deltaUp) {
                    true -> t.successText
                    false -> t.dangerText
                    null -> t.muted
                }
                Text(delta, style = MaterialTheme.typography.labelMedium, color = c)
            }
        }
        if (spark != null) {
            Spacer(Modifier.height(t.space2))
            Sparkline(values = spark, modifier = Modifier.fillMaxWidth().height(t.sparkHeight))
        }
    }
}

/** 遮罩：点击关闭。 */
@Composable
fun Scrim(visible: Boolean, onDismiss: () -> Unit) {
    val t = LocalDesignTokens.current
    if (!visible) return
    Box(
        Modifier
            .fillMaxWidth()
            .background(t.scrim)
            .clickable { onDismiss() }
    )
}

/** 底部 sheet：进入 220ms / 退出 150ms（契约 §7）；底 = --surface-warm + --elev-3 + grip（原型 .sheet）。 */
@Composable
fun BottomSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    showGrip: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalDesignTokens.current
    if (!visible) return
    androidx.compose.animation.AnimatedVisibility(
        visible = visible,
        // ⚠️ modifier 必须作用在 AnimatedVisibility（本组件的根节点）上：调用方普遍写
        // Modifier.align(Alignment.BottomCenter) 来把 sheet 贴底，而 align 是 Box 的 parentData ——
        // 只有落在 Box 的**直接子节点**上才会被读取。此前它被传给内部的 Column，Box 读不到，
        // 于是所有抽屉都退回默认的 TopStart（真机表现：抽屉贴在屏幕顶部）。
        modifier = modifier,
        enter = androidx.compose.animation.slideInVertically(
            animationSpec = androidx.compose.animation.core.tween(t.durOverlayIn)
        ) + androidx.compose.animation.fadeIn(
            animationSpec = androidx.compose.animation.core.tween(t.durOverlayIn)
        ),
        exit = androidx.compose.animation.slideOutVertically(
            animationSpec = androidx.compose.animation.core.tween(t.durOverlayOut)
        ) + androidx.compose.animation.fadeOut(
            animationSpec = androidx.compose.animation.core.tween(t.durOverlayOut)
        ),
    ) {
        val sheetShape = RoundedCornerShape(topStart = t.radiusLg, topEnd = t.radiusLg)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .tokenShadow(3, sheetShape)
                .clip(sheetShape)
                .background(t.surfaceWarm)
                .padding(start = t.space4, end = t.space4, top = t.space4, bottom = t.space6),
        ) {
            if (showGrip) {
                Box(
                    Modifier
                        .align(Alignment.CenterHorizontally)
                        .width(SHEET_GRIP_W)
                        .height(SHEET_GRIP_H)
                        .clip(RoundedCornerShape(t.radiusPill))
                        .background(t.border)
                )
                Spacer(Modifier.height(t.space3))
            }
            content()
        }
    }
}

/** Toast：1.6s 自动消失（契约 §5）。 */
@Composable
fun AppToast(
    message: String?,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    durationMs: Int = 1600,
) {
    val t = LocalDesignTokens.current
    if (message == null) return
    LaunchedEffect(message) {
        delay(durationMs.toLong())
        onDismiss()
    }
    Box(modifier = modifier, contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.labelLarge,
            color = t.accentOn,
            modifier = Modifier
                .clip(RoundedCornerShape(t.radiusPill))
                .background(t.fg)
                .padding(horizontal = t.space4, vertical = t.space2),
        )
    }
}

/** 简单 toast 状态（配合 AppToast 使用）。 */
class ToastHostState {
    var message by mutableStateOf<String?>(null)
        private set

    fun show(text: String) {
        message = text
    }

    fun clear() {
        message = null
    }
}

@Composable
fun rememberToastHostState(): ToastHostState = remember { ToastHostState() }

/** 原型 .sheet-grip：36×4 pill。 */
private val SHEET_GRIP_W = 36.dp
private val SHEET_GRIP_H = 4.dp

/** 原型 .dialog：max-width 320px。 */
private val DIALOG_MAX_W = 320.dp

/**
 * 自绘弹窗基元（原型 .scrim > .dialog：底 --surface-warm、--elev-3、radius-lg、pop spring 入场）。
 * 与 M3 AlertDialog 的差别：底色/圆角/阴影全部来自原型 token，几何不受 Material 默认值接管。
 * 浮层填满父容器（父容器需 fillMaxSize），点遮罩关闭。
 *
 * 用法：`AppDialog(visible = show, onDismiss = { show = false }) { ...内容... }`
 */
@Composable
fun AppDialog(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    maxWidth: Dp = DIALOG_MAX_W,
    content: @Composable ColumnScope.() -> Unit,
) {
    val t = LocalDesignTokens.current
    if (!visible) return
    val shape = RoundedCornerShape(t.radiusLg)
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(t.scrim)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onDismiss() },
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(t.durOverlayIn)) + scaleIn(initialScale = 0.94f, animationSpec = tween(t.durOverlayIn)),
            exit = fadeOut(tween(t.durOverlayOut)) + scaleOut(targetScale = 0.94f, animationSpec = tween(t.durOverlayOut)),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = t.space4)
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .tokenShadow(3, shape)
                    .clip(shape)
                    .background(t.surfaceWarm)
                    // 卡片自身吞掉点击，避免点内容触发遮罩关闭
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { }
                    .padding(t.space5),
                content = content,
            )
        }
    }
}

/**
 * 确认弹窗（原型 .dialog + 两枚 .btn/.btn-ghost：标题 17/600、说明 14、动作 grid 1fr 1fr）。
 * s05 删除交易 / s06 保存预算 可直接用它替换 M3 AlertDialog；自定义正文用 [extraContent]。
 */
@Composable
fun ConfirmDialog(
    visible: Boolean,
    title: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    message: String? = null,
    confirmLabel: String = "确定",
    dismissLabel: String = "取消",
    extraContent: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    AppDialog(visible = visible, onDismiss = onDismiss, modifier = modifier) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = t.fg)
        if (message != null) {
            Spacer(Modifier.height(t.space2))
            Text(message, style = MaterialTheme.typography.bodySmall, color = t.muted)
        }
        if (extraContent != null) {
            Spacer(Modifier.height(t.space3))
            extraContent()
        }
        Spacer(Modifier.height(t.space4))
        Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
            DialogAction(dismissLabel, primary = false, onClick = onDismiss, modifier = Modifier.weight(1f))
            DialogAction(confirmLabel, primary = true, onClick = onConfirm, modifier = Modifier.weight(1f))
        }
    }
}

/** 弹窗动作按钮（原型 .btn / .btn-ghost）：min-height 48、radius-md、17sp/600。 */
@Composable
private fun DialogAction(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    Box(
        modifier = modifier
            .height(t.tapMin)
            .clip(shape)
            .background(if (primary) t.accent else Color.Transparent)
            .border(1.dp, if (primary) Color.Transparent else t.border, shape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (primary) t.accentOn else t.fg)
    }
}

/** 占位空行。 */
@Composable
fun HSpacer(height: Dp) = Spacer(Modifier.height(height))

/** 主题快照便利访问（供非 @Composable 的 draw 逻辑使用）。 */
@Composable
fun currentTokens(): DesignTokens = LocalDesignTokens.current
