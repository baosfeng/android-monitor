package com.bsfeng.monitor.transfer.ui

// 接收侧卡片（C-10）：端口 / 可被发现 / 免配对窗口 / 自动关闭 / 暂停接收。
//
// C-10 的口径（契约）：
//  - 一个控件承载开 / 关：关闭态是主按钮「开启接收」，开启态是卡头右侧的「关闭」；
//  - 点击后按钮即时转圈（在途禁用防连点），状态由 engine 的 running 流落定；
//  - 关闭时若有传输中任务，由页面先弹二次确认（说明会中断），本卡片只管发动作。

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.transfer.service.ReceivingAutoClose
import com.bsfeng.monitor.transfer.service.ReceivingState
import com.bsfeng.monitor.domain.transfer.TransferFormat
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.rememberMotionEnabled
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/** C-10 的锚点 id（开 / 关两个形态共用，焦点回归也用它）。 */
internal const val RECEIVING_TOGGLE_ID: String = "C-10"

@Composable
internal fun ReceivingCard(
    running: Boolean,
    port: Int,
    onlineCount: Int,
    backgroundReceive: Boolean,
    windowRemainingMs: Long,
    autoCloseRemainingMs: Long,
    thresholdMb: Int,
    busy: Boolean,
    focusPending: String?,
    onFocusConsumed: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val modeLabel = if (backgroundReceive) "后台常驻" else "仅应用内"
    val motionEnabled = rememberMotionEnabled()
    // 「自动关闭」文案：最后 60 秒切警示色（颜色过渡取 dur-state，reduced-motion 时直接落终态）
    val autoCloseText = if (running && autoCloseRemainingMs > 0L) {
        ReceivingAutoClose.remainingText(autoCloseRemainingMs)
    } else {
        null
    }
    val autoCloseColor by animateColorAsState(
        targetValue = if (autoCloseText != null && ReceivingAutoClose.isWarning(autoCloseRemainingMs)) {
            t.warnText
        } else {
            t.muted
        },
        animationSpec = if (motionEnabled) tween(t.durState) else snap(),
        label = "autoCloseColor",
    )
    AppCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (running) "接收已开启" else "未开启接收",
                    style = MaterialTheme.typography.titleSmall,
                    color = t.fg,
                )
                Spacer(Modifier.height(t.space1))
                Text(
                    if (running) {
                        ReceivingState.of(true, port, onlineCount).statusText + " · " + modeLabel
                    } else {
                        // 关闭态只说明「开启后会发生什么」；不把「后台常驻」贴在「未开启接收」旁边
                        // （那是开启后的模式，不是当前状态，并排会读成自相矛盾）。
                        "同一 Wi-Fi 下开启后，对端就能发现本机 · " +
                            if (backgroundReceive) "开启后常驻后台" else "仅应用内"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
            if (running) {
                ReceivingToggle(
                    label = "关闭",
                    filled = false,
                    busy = busy,
                    focusPending = focusPending,
                    onFocusConsumed = onFocusConsumed,
                    onClick = onStop,
                )
            }
        }
        if (!running) {
            Spacer(Modifier.height(t.space3))
            ReceivingToggle(
                label = "开启接收",
                filled = true,
                busy = busy,
                focusPending = focusPending,
                onFocusConsumed = onFocusConsumed,
                onClick = onStart,
            )
        }
        if (autoCloseText != null) {
            Spacer(Modifier.height(t.space2))
            Text(
                autoCloseText,
                style = MaterialTheme.typography.bodySmall,
                color = autoCloseColor,
            )
        }
        if (running && windowRemainingMs > 0L) {
            Spacer(Modifier.height(t.space2))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Badge(
                    text = "免配对 " + TransferFormat.windowRemaining(windowRemainingMs),
                    tone = BadgeTone.Warn,
                )
                Spacer(Modifier.width(t.space2))
                Text(
                    "未配对设备在窗口内免 PIN",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
        }
        if (running && windowRemainingMs <= 0L && thresholdMb > 0) {
            Spacer(Modifier.height(t.space2))
            Text(
                "小于 " + TransferFormat.threshold(thresholdMb) + " 的文件免配对",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
    }
}

/**
 * C-10 控件本体：关闭态是主按钮（filling），开启态是文字按钮（右侧「关闭」）。
 *
 * 两态共用同一锚点与同一套反馈：按下 0.96（--dur-press）+ LIGHT 触感；
 * 在途（[busy]）即时转圈并禁用，防止「点了两下开两次服务」。
 */
@Composable
private fun ReceivingToggle(
    label: String,
    filled: Boolean,
    busy: Boolean,
    focusPending: String?,
    onFocusConsumed: () -> Unit,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    val enabled = !busy
    val minHeight = if (filled) t.tapMin else t.tapMin
    Row(
        modifier = Modifier
            .heightIn(min = minHeight)
            .then(if (filled) Modifier.fillMaxWidth() else Modifier)
            .pressFeedback(interaction, enabled = enabled)
            .clip(shape)
            .background(if (filled) t.accent else t.fgFade(6))
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = null,
            ) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .testTag("C-10")
            .returnFocusWhen(RECEIVING_TOGGLE_ID, focusPending, onFocusConsumed)
            .padding(horizontal = t.space4, vertical = t.space2),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (busy) {
            CircularProgressIndicator(
                modifier = Modifier.size(t.space4),
                strokeWidth = 2.dp,
                color = if (filled) t.accentOn else t.fg2,
            )
            Spacer(Modifier.width(t.space2))
        }
        Text(
            label,
            style = MaterialTheme.typography.bodyMedium,
            color = if (filled) t.accentOn else t.accent,
        )
    }
}

/** 空分组提示（比 EmptyState 轻，避免列表里出现两个大空态）。 */
@Composable
internal fun HintText(text: String) {
    val t = LocalDesignTokens.current
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = t.muted,
        modifier = Modifier.padding(vertical = t.space2),
    )
}

internal fun statusLabel(task: TransferTask): String = when (task.status) {
    TransferStatus.IDLE -> "准备中"
    TransferStatus.OFFERED -> "已提交清单"
    TransferStatus.AWAITING_CONFIRM -> "等待 PIN 确认"
    TransferStatus.TRANSFERRING -> "传输中"
    TransferStatus.COMPLETED -> "已完成"
    TransferStatus.FAILED -> task.errorCode?.let { "失败（$it）" } ?: "失败"
    TransferStatus.CANCELLED -> "已取消"
    TransferStatus.DENIED -> "对方拒绝"
}

/** 抖动幅度（协议 §7：±4px）。 */
internal const val SHAKE_PX = 4f

/** 滑到该比例时红底达到完全不透明（契约 C-07：越过 20% 露底，越过 40% 松手删除）。 */
internal const val REVEAL_FULL_PROGRESS = 0.4f

/** 露出红底的下限（契约 C-07：越过 20% 才露）。 */
internal const val REVEAL_START_PROGRESS = 0.2f

/** 松手删除的阈值（契约 C-07：越过 40%）。 */
internal const val DELETE_THRESHOLD_PROGRESS = 0.4f

/** 「最近完成」最多展示条数（更早的记录由删除整理，不做无限增长）。 */
internal const val MAX_FINISHED_TASKS = 20
