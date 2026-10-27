package com.bsfeng.monitor.transfer.ui

// C-06 撤销条：删除记录后 5s 可撤销 + 收缩进度（契约「能撤销给撤销」的落点）。
//
// 为什么留在 transfer/ui 而不是 ui/components：全项目只有这一处撤销条，
// 组件库里没有现成基元；只有一个使用点时抽成通用件会留下一个没人验证的共享件，
// 等第二处真正需要再抽（那时才知道该抽成什么样）。

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.tokenShadow
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 进度条高度（原型 .uprog：3px）。 */
private val UNDO_PROGRESS_H: androidx.compose.ui.unit.Dp = 3.dp

/**
 * 撤销条：一行「已删除「文件名」」+ 右侧「撤销」+ 底部 5s 收缩进度。
 *
 * 计时口径取页面传入的 [windowMs]（= TransferEngine.UNDO_WINDOW_MS）；动画是**信息**（剩余时间），
 * 因此不随「精简动效」省略 —— 否则用户看不到窗口还剩多久。
 *
 * @param undo null = 不显示。
 */
@Composable
internal fun TransferUndoBar(
    undo: TransferUndo?,
    windowMs: Long,
    onUndo: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (undo == null) return
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    val progress = remember(undo.deletion.deletionId) { Animatable(1f) }
    // 只负责「把剩余时间画出来」；到期提交删除由页面统一做（一处计时，避免两套窗口）
    LaunchedEffect(undo.deletion.deletionId) {
        progress.snapTo(1f)
        progress.animateTo(0f, tween(windowMs.toInt(), easing = LinearEasing))
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .tokenShadow(2, shape)
            .clip(shape)
            .background(t.fg)
            .padding(top = t.space2),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = t.space4, end = t.space2, bottom = t.space2),
        ) {
            Text(
                "已删除「${undo.deletion.task.file.name}」",
                style = MaterialTheme.typography.bodySmall,
                color = t.accentOn,
                modifier = Modifier.weight(1f),
            )
            UndoButton(onUndo)
        }
        Box(
            Modifier
                .fillMaxWidth()
                .height(UNDO_PROGRESS_H)
                .background(t.accentOn.copy(alpha = 0.2f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(progress.value.coerceIn(0f, 1f))
                    .height(UNDO_PROGRESS_H)
                    .background(t.accentOn),
            )
        }
    }
}

/** 「撤销」：按下变浅一档（原型 .ubtn:active → 透明白 24%），点击区 ≥ 44dp。 */
@Composable
private fun UndoButton(onUndo: () -> Unit) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(t.radiusSm)
    Box(
        modifier = Modifier
            .heightIn(min = t.tapMin)
            .clip(shape)
            .background(t.accentOn.copy(alpha = if (pressed) 0.24f else 0.14f))
            .clickable(interactionSource = interaction, indication = null) { onUndo() }
            .padding(horizontal = t.space4)
            .testTag("C-06"),
        contentAlignment = Alignment.Center,
    ) {
        Text("撤销", style = MaterialTheme.typography.labelLarge, color = t.accentOn)
    }
}
