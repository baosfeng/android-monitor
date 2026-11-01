package com.bsfeng.monitor.transfer.ui

// 任务卡片的滑动外壳（C-07）：左右两个方向一致，越过 20% 露红底、越过 40% 松手删除。
//
// 口径要点：
//  - 进行中卡片左滑松手直接回弹 + REJECT 触感，**不显红底**（删除不是进行中卡片的动作）；
//  - 终态卡片滑过 40% 松手走与 C-05 相同的删除路径（接收 + 已完成仍先二次确认，由页面处理）；
//  - 滑动不是唯一入口：等效入口是 C-04 动作面板。
//
// 位移阈值不用手写像素：Compose 的触摸阈值（touch slop）本身就是「达到最小位移才判定为滑动」，
// 账本里的 8px 是原型口径，这里按 dp 由平台折算。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.opaquePanel

@Composable
internal fun SwipeableTaskCard(
    task: TransferTask,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
    onMore: () -> Unit,
    canDelete: Boolean,
    requiresConfirm: Boolean,
    highlight: Boolean,
    onHighlightDone: () -> Unit,
    focusPending: String?,
    onFocusConsumed: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    val haptic = rememberHaptic()
    val dismissState = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            when {
                value == SwipeToDismissBoxValue.Settled -> true
                // 进行中：回弹 + REJECT（锚点 C-07「不显红底」）
                !canDelete -> {
                    haptic(HapticTone.REJECT)
                    false
                }
                else -> {
                    haptic(HapticTone.REJECT)
                    onDelete()
                    // 需要二次确认时不留在滑出位置：先回弹，确认后才由任务状态移除
                    !requiresConfirm
                }
            }
        },
        // 越过 40% 才判定为删除（锚点 C-07）
        positionalThreshold = { totalDistance -> totalDistance * DELETE_THRESHOLD_PROGRESS },
    )
    // 撤销恢复的列表项可能复用同一 key 的保存态：从已滑出位置复位，否则卡片会停在屏外
    LaunchedEffect(Unit) {
        if (dismissState.currentValue != SwipeToDismissBoxValue.Settled) dismissState.reset()
    }
    // 红底只在「可删除 + 越过 20%」后淡入：进行中卡片永远不出现
    val reveal = if (!canDelete) {
        0f
    } else {
        ((dismissState.progress - REVEAL_START_PROGRESS) / (REVEAL_FULL_PROGRESS - REVEAL_START_PROGRESS))
            .coerceIn(0f, 1f)
    }
    SwipeToDismissBox(
        state = dismissState,
        // 进行中任务也允许拖动：松手回弹，这样「滑不动」和「滑出去才知道不能删」两种手感都不会出现
        enableDismissFromStartToEnd = true,
        enableDismissFromEndToStart = true,
        modifier = Modifier.testTag("C-07"),
        backgroundContent = {
            if (reveal > 0.01f) {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .graphicsLayer { alpha = reveal }
                        .clip(shape)
                        .background(t.danger)
                        .padding(horizontal = t.space4),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Outlined.Delete,
                        contentDescription = null,
                        tint = t.accentOn,
                        modifier = Modifier.size(t.space5),
                    )
                    Spacer(Modifier.width(t.space2))
                    Text(
                        "松手删除这条记录",
                        style = MaterialTheme.typography.labelMedium,
                        color = t.accentOn,
                    )
                }
            }
        },
    ) {
        // 内容必须不透明且铺满：圆角由卡片自身承载，不留会透出底色的缝
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(t.opaquePanel),
        ) {
            TaskCard(
                task = task,
                highlight = highlight,
                onHighlightDone = onHighlightDone,
                onCancel = onCancel,
                onMore = onMore,
                focusPending = focusPending,
                onFocusConsumed = onFocusConsumed,
            )
        }
    }
}
