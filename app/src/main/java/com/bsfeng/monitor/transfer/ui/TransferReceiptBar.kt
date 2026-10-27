package com.bsfeng.monitor.transfer.ui

// C-08 批次回执条（进行中与最近完成共用）：一行汇总 + 点击展开逐文件明细。
//
// 只在「同一 sessionId 下的文件数 ≥ 2」时出现（一次多选）；单文件批次整条隐藏，
// 避免每个单文件任务头上都压一条「1 个文件」的噪音。
// 展开/收起只改本屏状态，不动任务数据，也不计入任何计数（契约 C-08）。

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.outlined.SwapVert
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.transfer.TransferFormat
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 批次回执条。
 *
 * @param tasks 同一 sessionId 下的全部文件（调用方保证 ≥ 2 条）。
 * @param expanded 展开态由页面持有（列表复用时不丢）。
 * @param onToggle 点击切换展开/收起（不改数据）。
 */
@Composable
internal fun BatchReceiptBar(
    tasks: List<TransferTask>,
    expanded: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    val delivered = receiptDelivered(tasks)
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    // 箭头翻转 120ms（--dur-state）
    val chevron by animateFloatAsState(
        targetValue = if (expanded) 180f else 0f,
        animationSpec = tween(t.durState, easing = t.easeStandard),
        label = "receiptChevron",
    )
    Column(modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("C-08")
                .clip(shape)
                .background(if (delivered) t.success.copy(alpha = t.success.alpha * 0.12f) else t.surface)
                .border(1.dp, t.borderSoft, shape)
                .clickable(interactionSource = interaction, indication = null) {
                    haptic(HapticTone.LIGHT)
                    onToggle()
                }
                .heightIn(min = t.tapMin)
                .padding(horizontal = t.space3, vertical = t.space2),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.space2),
        ) {
            Icon(
                imageVector = if (delivered) Icons.Filled.CheckCircle else Icons.Outlined.SwapVert,
                contentDescription = null,
                tint = if (delivered) t.successText else t.accent,
                modifier = Modifier.size(t.space4),
            )
            Text(
                receiptText(tasks),
                style = MaterialTheme.typography.bodySmall,
                color = if (delivered) t.successText else t.fg2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Icon(
                Icons.Filled.ExpandMore,
                contentDescription = if (expanded) "收起明细" else "展开明细",
                tint = t.fg2,
                modifier = Modifier
                    .size(t.space5)
                    .rotate(chevron),
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier
                    .padding(top = t.space1)
                    .fillMaxWidth()
                    .clip(shape)
                    .background(t.surfaceWarm)
                    .border(1.dp, t.borderSoft, shape)
                    .padding(horizontal = t.space3, vertical = t.space2),
            ) {
                tasks.forEach { task ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = t.space1),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            task.file.name,
                            style = MaterialTheme.typography.bodySmall,
                            color = t.fg2,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(t.space2))
                        Text(
                            receiptFileLine(task),
                            style = MaterialTheme.typography.labelSmall,
                            color = t.meta,
                        )
                        if (task.status.isTerminal && task.status != com.bsfeng.monitor.domain.transfer.TransferStatus.COMPLETED) {
                            Spacer(Modifier.width(t.space2))
                            Text(
                                TransferFormat.progressPair(task.receivedBytes, task.file.size),
                                style = MaterialTheme.typography.labelSmall,
                                color = t.meta,
                            )
                        }
                    }
                }
            }
        }
    }
}
