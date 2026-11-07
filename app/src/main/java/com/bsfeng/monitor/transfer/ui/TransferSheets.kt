package com.bsfeng.monitor.transfer.ui

// 传送页的浮层：取消面板（C-02 / C-03 / C-09）、动作面板（C-04 / C-05 / C-17）、二次确认框（C-12 / C-16 / C-10 边界 / C-05）。
//
// 承载统一走 ui/components 的 [BottomSheet] / [ConfirmDialog]（窗口级浮层 + 沉浸式遮罩 + 返回键口径），
// 本文件只负责「这一页的文案与动作接线」，不自绘遮罩、不改组件库。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.transfer.service.ResendAvailability
import com.bsfeng.monitor.domain.transfer.UNKNOWN_PEER_ALIAS
import com.bsfeng.monitor.ui.components.BottomSheet
import com.bsfeng.monitor.ui.components.ConfirmDialog
import com.bsfeng.monitor.ui.components.rowPressFeedback
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * C-02 / C-03：取消面板（C-09「全部取消」复用同一面板，只换标题与按钮文案）。
 *
 * 破坏性动作用 --danger、单独成块、按钮之间留 8dp；取消按钮点击即转圈防连点。
 * 面板关闭（点遮罩 / 返回键 / 继续传输）一律等同「继续传输」：本次取消意图作废。
 */
@Composable
internal fun CancelSheet(
    target: CancelTarget,
    onConfirm: () -> Unit,
    onContinue: () -> Unit,
) {
    val t = LocalDesignTokens.current
    var submitting by remember(target) { mutableStateOf(false) }
    BottomSheet(visible = true, onDismiss = onContinue) {
        Text(cancelTitle(target), style = MaterialTheme.typography.titleMedium, color = t.fg)
        Spacer(Modifier.height(t.space2))
        Text(cancelBody(target), style = MaterialTheme.typography.bodySmall, color = t.muted)
        Spacer(Modifier.height(t.space4))
        TransferSheetButton(
            label = cancelConfirmLabel(target),
            primary = true,
            danger = true,
            busy = submitting,
            modifier = Modifier.testTag("C-02"),
            onClick = {
                if (submitting) return@TransferSheetButton
                submitting = true
                onConfirm()
            },
        )
        Spacer(Modifier.height(t.space2))
        TransferSheetButton(
            label = "继续传输",
            primary = false,
            danger = false,
            busy = false,
            modifier = Modifier.testTag("C-03"),
            onClick = onContinue,
        )
    }
}

/**
 * C-04：动作面板（终态任务）。
 *
 * 菜单顺序按口径：重新发送 → 删除记录 → 取消。
 * 「重新发送」按 [ResendAvailability] 渲染三态：不渲染（接收方向 / 未终态）、
 * 置灰并说明原因（源文件已不在本机 / 对方不在线）、可点。
 */
@Composable
internal fun ActionsSheet(
    task: TransferTask,
    batchCount: Int,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    resend: ResendAvailability = ResendAvailability.Ready,
    onResend: () -> Unit = {},
) {
    val t = LocalDesignTokens.current
    val peerAlias = task.peer.alias.trim().ifBlank { UNKNOWN_PEER_ALIAS }
    BottomSheet(visible = true, onDismiss = onDismiss) {
        Text(
            if (batchCount > 1) "${task.file.name} 等 $batchCount 个文件" else task.file.name,
            style = MaterialTheme.typography.titleMedium,
            color = t.fg,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(t.space2))
        Text(
            (if (task.direction == TransferDirection.SEND) "发送给 $peerAlias" else "接收自 $peerAlias") +
                " · " + formatClock(task.updatedAtMs),
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
        Spacer(Modifier.height(t.space4))
        // 三态：Hidden 不渲染；Blocked 置灰 + 说明原因（不给假入口，也不把入口藏掉）；
        // Ready 真重发。原因文案逐字取锚点 s22 C-17 边界。
        if (resend != ResendAvailability.Hidden) {
            val enabled = resend is ResendAvailability.Ready
            MenuRow(
                label = "重新发送",
                danger = false,
                enabled = enabled,
                hint = (resend as? ResendAvailability.Blocked)?.reason,
                modifier = Modifier.testTag("C-17"),
                onClick = {
                    if (!enabled) return@MenuRow
                    onResend()
                },
            )
        }
        MenuRow(
            label = "删除记录",
            danger = true,
            modifier = Modifier.testTag("C-05"),
            onClick = onDelete,
        )
        MenuRow(label = "取消", danger = false, onClick = onDismiss)
    }
}

/** 二次确认框（不可撤销的操作才走到这里）。 */
@Composable
internal fun TransferConfirmHost(
    confirm: TransferConfirm?,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    val haptic = rememberHaptic()
    when (confirm) {
        is TransferConfirm.Unpair -> ConfirmDialog(
            visible = true,
            title = unpairConfirmTitle(confirm.alias),
            message = UNPAIR_CONFIRM_MESSAGE,
            confirmLabel = "解除配对",
            dismissLabel = "保留配对",
            onConfirm = {
                haptic(HapticTone.LIGHT)
                onConfirm()
            },
            onDismiss = onDismiss,
        )
        is TransferConfirm.StopReceiving -> ConfirmDialog(
            visible = true,
            title = STOP_RECEIVING_TITLE,
            message = stopReceivingBody(confirm.activeCount),
            confirmLabel = "关闭接收",
            dismissLabel = "继续接收",
            onConfirm = {
                haptic(HapticTone.REJECT)
                onConfirm()
            },
            onDismiss = onDismiss,
        )
        is TransferConfirm.DeleteReceived -> ConfirmDialog(
            visible = true,
            title = deleteConfirmTitle(confirm.task.file.name),
            message = DELETE_CONFIRM_MESSAGE,
            confirmLabel = "删除记录和文件",
            dismissLabel = "保留",
            onConfirm = {
                haptic(HapticTone.REJECT)
                onConfirm()
            },
            onDismiss = onDismiss,
        )
        is TransferConfirm.ClearFinished -> ConfirmDialog(
            visible = true,
            title = clearConfirmTitle(confirm.recordCount),
            message = clearConfirmBody(confirm.recordCount, confirm.fileCount),
            confirmLabel = "清空 ${confirm.recordCount} 条",
            dismissLabel = "保留记录",
            onConfirm = {
                haptic(HapticTone.REJECT)
                onConfirm()
            },
            onDismiss = onDismiss,
        )
        null -> Unit
    }
}

/**
 * 面板 / 弹窗主次按钮：主按钮 --danger 或 --accent，次按钮描边；按下 0.96（--dur-press）。
 *
 * 锚点由调用方用 `Modifier.testTag("C-xx")` 显式给出（页面里能直接 grep 到锚点，不藏在参数里）。
 *
 * @param enabled false = 禁用（点击区保留但无反馈）。
 */
@Composable
internal fun TransferSheetButton(
    label: String,
    primary: Boolean,
    danger: Boolean,
    busy: Boolean,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    val active = enabled && !busy
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(t.tapMin)
            .pressFeedback(interaction, enabled = active)
            .clip(shape)
            .background(
                when {
                    !active -> t.fgFade(10)
                    primary && danger -> t.danger
                    primary -> t.accent
                    else -> t.surface
                },
            )
            .then(if (primary) Modifier else Modifier.border(1.dp, t.border, shape))
            .clickable(
                enabled = active,
                interactionSource = interaction,
                indication = null,
            ) {
                // 破坏性操作走 REJECT（口径 §三）；普通按钮 LIGHT
                haptic(if (danger) HapticTone.REJECT else HapticTone.LIGHT)
                onClick()
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(t.space4),
                strokeWidth = 2.dp,
                color = if (primary) t.accentOn else t.fg,
            )
            Spacer(Modifier.width(t.space2))
        }
        Text(
            label,
            style = MaterialTheme.typography.titleMedium,
            color = when {
                !active -> t.meta
                primary -> t.accentOn
                else -> t.fg
            },
            modifier = Modifier.testTag("button.sheet"),
        )
    }
}

/**
 * 动作面板的一行菜单：min-height 52、--surface 底、危险项用 --danger；锚点由调用方显式给出。
 *
 * @param enabled false = 禁用（点击区保留但无反馈、不震）；[hint] 是禁用原因，
 *   渲染在标签下方 —— 交互口径要求「置灰并说明」而不是只置灰。
 */
@Composable
private fun MenuRow(
    label: String,
    danger: Boolean,
    modifier: Modifier = Modifier,
    hint: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = t.space2)
            .heightIn(min = t.rowMinH)
            .clip(shape)
            .background(t.surface)
            // clickable 禁用时 interaction 不会发按压事件，rowPressFeedback 自然不动
            .rowPressFeedback(interaction)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
            ) {
                haptic(if (danger) HapticTone.REJECT else HapticTone.LIGHT)
                onClick()
            }
            .then(modifier)
            .padding(horizontal = t.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                color = when {
                    !enabled -> t.muted
                    danger -> t.dangerText
                    else -> t.fg
                },
                modifier = Modifier.testTag("row.menu"),
            )
            if (!enabled && hint != null) {
                Spacer(Modifier.height(t.space1))
                // 锚点 s22 C-17 边界：置灰时必须说明为什么（源文件已不在本机 / 对方不在线）
                Text(hint, style = MaterialTheme.typography.bodySmall, color = t.meta)
            }
        }
    }
}
