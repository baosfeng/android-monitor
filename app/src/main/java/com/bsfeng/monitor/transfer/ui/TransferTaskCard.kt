package com.bsfeng.monitor.transfer.ui

// 任务卡片本体（C-01 取消 / C-04 更多 / C-06 撤销后的高亮）。
//
// 进行中卡片：文件名 + 「发送/接收 · 状态 · 已传/总量 · 速度」+ 进度条 + 右侧「取消」（C-01）。
// 终态卡片：文件名 + 完成副行（契约 §文案口径）+ 状态图标 + 右侧「更多」（C-04，常驻可见）。
// 破坏性入口一律「先面板后动作」：卡上按钮只负责开面板（C-01 → C-02 / C-04 → 动作面板）。

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.rememberMotionEnabled
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.accentFade
import com.bsfeng.monitor.ui.theme.fgFade
import kotlinx.coroutines.delay

/** 高亮时长（契约 C-02 / C-06：移到「最近完成」后高亮 1.2s）。 */
internal const val HIGHLIGHT_MS: Long = 1_200L

@Composable
internal fun TaskCard(
    task: TransferTask,
    highlight: Boolean,
    onHighlightDone: () -> Unit,
    onCancel: () -> Unit,
    onMore: () -> Unit,
    focusPending: String?,
    onFocusConsumed: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    val motion = rememberMotionEnabled()
    val failed = task.status == TransferStatus.FAILED || task.status == TransferStatus.DENIED
    val shake = rememberFailureShake(failed)
    val fraction by animateFloatAsState(
        targetValue = if (task.status == TransferStatus.COMPLETED) 1f else task.fraction,
        animationSpec = tween(t.durState, easing = t.easeStandard),
        label = "taskFraction",
    )
    val accent = when (task.status) {
        TransferStatus.COMPLETED -> t.success
        TransferStatus.FAILED, TransferStatus.DENIED -> t.danger
        TransferStatus.CANCELLED -> t.muted
        else -> t.accent
    }
    val glow by animateColorAsState(
        targetValue = if (highlight) t.accentFade(12) else Color.Transparent,
        animationSpec = if (motion) tween(t.durState, easing = t.easeStandard) else snap(),
        label = "taskHighlight",
    )
    LaunchedEffect(highlight) {
        if (highlight) {
            delay(HIGHLIGHT_MS)
            onHighlightDone()
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = shake.value }
            .clip(shape)
            .background(glow)
            .then(if (highlight) Modifier.border(1.dp, t.accent, shape) else Modifier)
            .then(
                // 「移除后五问」之焦点：撤销恢复 / 删除后落到某一行时，焦点落在卡片容器上
                Modifier
                    .focusable()
                    .returnFocusWhen(taskFocusId(task), focusPending, onFocusConsumed),
            ),
    ) {
        AppCard(elevation = if (task.isActive) 1 else 0) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        task.file.name,
                        style = MaterialTheme.typography.titleSmall,
                        color = t.fg,
                        // 超长文件名最多两行，其余省略：卡片高度不随文件名爆炸
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(t.space1))
                    Text(
                        if (task.isActive) taskDetailText(task) else finishedSubtitle(task),
                        style = MaterialTheme.typography.bodySmall,
                        // 失败态副文案直接走 danger，和「已完成」的灰色一眼分开
                        color = if (failed) t.danger else t.muted,
                    )
                }
                if (task.isActive) {
                    CancelTaskButton(
                        taskKey = taskKey(task),
                        focusPending = focusPending,
                        onFocusConsumed = onFocusConsumed,
                        onClick = onCancel,
                    )
                } else {
                    MoreActionsButton(
                        task = task,
                        focusPending = focusPending,
                        onFocusConsumed = onFocusConsumed,
                        onClick = onMore,
                    )
                }
            }
            // 进度条只属于「进行中」：终态卡片只保留文件名 + 副行 + 状态图标
            if (task.isActive) {
                Spacer(Modifier.height(t.space3))
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(t.space1)
                        .clip(RoundedCornerShape(t.radiusPill))
                        .background(t.fgFade(10)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(t.radiusPill))
                            .background(accent),
                    )
                }
            }
            if (failed && task.message.isNotBlank()) {
                Spacer(Modifier.height(t.space2))
                Text(
                    task.message,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.danger,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/**
 * C-01「取消」：只打开取消面板，不直接发请求、不改任务状态。
 *
 * 点击区 48×48dp（--tap-min 44 + 4dp 内边距）；按下 0.96；触感 LIGHT（真正破坏性的动作在 C-02）。
 */
@Composable
private fun CancelTaskButton(
    taskKey: String,
    focusPending: String?,
    onFocusConsumed: () -> Unit,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    val focusId = "C-01:" + taskKey
    Box(
        modifier = Modifier
            .widthIn(min = t.tapMin + t.space1)
            .heightIn(min = t.tapMin + t.space1)
            .pressFeedback(interaction)
            .clip(RoundedCornerShape(t.radiusSm))
            .clickable(interactionSource = interaction, indication = null) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .testTag("C-01")
            .returnFocusWhen(focusId, focusPending, onFocusConsumed),
        contentAlignment = Alignment.Center,
    ) {
        Text("取消", style = MaterialTheme.typography.labelMedium, color = t.dangerText)
    }
}

/**
 * C-04「更多」：常驻可见、不靠滑动或长按才出现；打开动作面板（C-05 / C-17）。
 *
 * 终态卡片右侧同时承载状态图标与更多按钮：图标是结果，按钮是入口，互不抢占点击区。
 */
@Composable
private fun MoreActionsButton(
    task: TransferTask,
    focusPending: String?,
    onFocusConsumed: () -> Unit,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    Row(verticalAlignment = Alignment.CenterVertically) {
        when (task.status) {
            TransferStatus.COMPLETED -> CompletionCheck()
            TransferStatus.FAILED, TransferStatus.DENIED -> Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = "传输失败",
                tint = t.danger,
                modifier = Modifier.size(t.space5),
            )
            TransferStatus.CANCELLED -> Icon(
                Icons.Outlined.Cancel,
                contentDescription = "已取消",
                tint = t.muted,
                modifier = Modifier.size(t.space5),
            )
            else -> Unit
        }
        Spacer(Modifier.width(t.space1))
        Box(
            modifier = Modifier
                .widthIn(min = t.tapMin + t.space1)
                .heightIn(min = t.tapMin + t.space1)
                .pressFeedback(interaction)
                .clip(RoundedCornerShape(t.radiusSm))
                .clickable(interactionSource = interaction, indication = null) {
                    haptic(HapticTone.LIGHT)
                    onClick()
                }
                .testTag("C-04")
                .returnFocusWhen(actionsTriggerOf(task), focusPending, onFocusConsumed),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                Icons.Filled.MoreHoriz,
                contentDescription = "更多操作",
                tint = t.fg2,
                modifier = Modifier.size(t.space5),
            )
        }
    }
}
