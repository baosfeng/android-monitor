package com.bsfeng.monitor.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AppDialog
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 弹窗动作按钮（原型 `.btn` / `.btn-ghost` / `.btn[disabled]`）：
 * min-height --tap-min、圆角 --radius-md、文字 17sp/600（主题 titleMedium，与
 * [com.bsfeng.monitor.ui.components.ConfirmDialog] 的动作按钮同一口径）。
 *
 * 与 ui/components/Surfaces.kt 的 `DialogAction` 同款；那个是 private，本次写 scope
 * 又只含记账两个文件（不动 W1 维护的共享基元），故按同一 token 口径在此重绘，
 * 并补上 `DialogAction` 没有的禁用态：原型 `.btn[disabled]{opacity:.45}`，
 * 表达方式沿用本模块既有事实标准（AddTransactionScreen.kt 的 PrimaryButton：禁用 = --accent 45%）。
 */
@Composable
private fun DialogAction(
    label: String,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    Box(
        modifier = modifier
            .height(t.tapMin)
            .clip(shape)
            .background(if (primary) t.accent.copy(alpha = if (enabled) 1f else 0.45f) else Color.Transparent)
            .border(1.dp, if (primary) Color.Transparent else t.border, shape)
            .clickable(enabled = enabled) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                !enabled -> t.muted
                primary -> t.accentOn
                else -> t.fg
            },
        )
    }
}

/**
 * 删除类确认弹窗（契约 s05-del-dialog / s05-del-cancel / s05-del-confirm）。
 *
 * 与预算编辑弹窗（BudgetScreen.kt 的 BudgetEditorDialog）同一套视觉（[AppDialog] + 1fr 1fr 动作按钮），差异只有两个动作的语义：
 * 确认是不可逆动作，按口径 §三 给 REJECT 触感；取消与点遮罩 / 返回键等价。
 * 锚点贴在本弹窗的动作按钮上 —— 共享 [com.bsfeng.monitor.ui.components.ConfirmDialog] 不透出按钮 modifier。
 */
@Composable
internal fun FinanceConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    dismissLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    AppDialog(
        visible = true,
        onDismiss = onDismiss,
        modifier = Modifier.testTag("s05-del-dialog"),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = t.fg)
        Spacer(Modifier.height(t.space2))
        Text(message, style = MaterialTheme.typography.bodySmall, color = t.muted)
        Spacer(Modifier.height(t.space4))
        Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
            DialogAction(
                label = dismissLabel,
                primary = false,
                onClick = onDismiss,
                modifier = Modifier.weight(1f).testTag("s05-del-cancel"),
            )
            DialogAction(
                label = confirmLabel,
                primary = true,
                onClick = {
                    // 破坏性动作：REJECT（口径 §三「删除 / 取消传输」）
                    haptic(HapticTone.REJECT)
                    onConfirm()
                },
                modifier = Modifier.weight(1f).testTag("s05-del-confirm"),
            )
        }
    }
}
