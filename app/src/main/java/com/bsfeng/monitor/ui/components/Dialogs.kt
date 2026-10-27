package com.bsfeng.monitor.ui.components

// 从 ui/components/Surfaces.kt 拆出：对话框 —— AppDialog、ConfirmDialog、DialogAction 按钮与 DIALOG_MAX_W 宽度常量。负责「覆盖在页面之上的确认/表单弹窗」。

import androidx.compose.foundation.LocalIndication
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.dialogEnter
import com.bsfeng.monitor.ui.foundation.dialogExit
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.opaquePanel
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn

/** 原型 .dialog：max-width 320px。 */
private val DIALOG_MAX_W = 320.dp

/**
 * 居中弹窗（原型 .scrim > .dialog：底 --surface-warm、--elev-3、radius-lg、pop spring 入场）。
 *
 * 承载走 [WindowOverlay]（窗口级全屏 + 沉浸式遮罩 + 返回键/点外部关闭统一口径）；
 * 与 M3 AlertDialog 的差别：底色 / 圆角 / 阴影全部来自原型 token，几何不受 Material 默认值接管。
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
    val shape = RoundedCornerShape(t.radiusLg)
    // 每次打开都从「未进入」开始，AnimatedVisibility 才会真正播放入场动画
    var entered by remember(visible) { mutableStateOf(false) }
    LaunchedEffect(visible) { entered = visible }
    WindowOverlay(visible = visible, onDismiss = onDismiss, modifier = modifier) {
        AnimatedVisibility(
            visible = entered,
            modifier = Modifier.align(Alignment.Center),
            enter = dialogEnter(),
            exit = dialogExit(),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = t.space4)
                    .widthIn(max = maxWidth)
                    .fillMaxWidth()
                    .tokenShadow(3, shape)
                    .clip(shape)
                    // 面板底合成到不透明：glassmorphism 的 --surface-warm 带 72% alpha，
                    // 弹窗里是要读的字，不能透出下层内容（有意偏离该主题语言）
                    .background(t.opaquePanel)
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
    // 弹窗按钮按压（契约 s12-dialog：0.96 + 120ms + LIGHT）
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(t.tapMin)
            .clip(shape)
            .background(if (primary) t.accent else Color.Transparent)
            .border(1.dp, if (primary) Color.Transparent else t.border, shape)
            .pressFeedback(interaction)
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                haptic(HapticTone.LIGHT)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, color = if (primary) t.accentOn else t.fg)
    }
}
