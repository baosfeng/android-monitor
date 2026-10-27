package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/ImportScreen.kt 的 file item；职责：已选文件卡（文件名 / 就绪状态 / 移除按钮）与导入进度条。

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 已选文件卡（锚点 s08-file / s08-file-clear）。
 *
 * 契约：卡按下底变 fg 5%（--dur-state）、抬指无进一步动作；文件名单行省略；副行「已就绪 · 可开始导入」，
 * 读取失败转红「读取失败」；移除按钮按下底变深一档 + LIGHT，导入中置灰不可点且不进 Tab 序列
 * （读屏「移除文件，不可用，导入进行中」）。
 */
@Composable
internal fun ImportFileCard(
    fileName: String,
    ready: Boolean,
    sizeText: String,
    recordCount: Int?,
    importing: Boolean,
    onRemove: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(t.radiusLg)
    val bg by animateColorAsState(
        targetValue = if (pressed) lerp(t.surface, t.fg, 0.05f) else t.surface,
        animationSpec = tween(t.durState),
        label = "importFileBg",
    )
    Column(Modifier.padding(horizontal = t.space4)) {
        Spacer(Modifier.height(t.space3))
        Row(
            modifier = modifier
                .fillMaxWidth()
                .clip(shape)
                .background(bg)
                .border(1.dp, t.borderSoft, shape)
                .clickable(interactionSource = interaction, indication = null) { /* 抬指无动作 */ }
                .padding(horizontal = t.space3, vertical = t.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Description,
                contentDescription = null,
                tint = t.muted,
                modifier = Modifier.size(t.iconSize),
            )
            Spacer(Modifier.width(t.space3))
            Column(Modifier.weight(1f)) {
                Text(
                    fileName,
                    style = MaterialTheme.typography.titleSmall,
                    color = t.fg,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (ready) "已就绪 · 可开始导入" else "读取失败",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (ready) t.incomeText else t.dangerText,
                    )
                    val meta = fileMetaText(sizeText, recordCount)
                    if (meta.isNotEmpty()) {
                        Text(
                            " · " + meta,
                            style = MaterialTheme.typography.labelSmall.copy(
                                // 契约 s08-file：记录数超 10,000 用等宽字体 + 千分位
                                fontFamily = if ((recordCount ?: 0) > 10_000) FontFamily.Monospace else FontFamily.Default,
                            ),
                            color = t.muted,
                            maxLines = 1,
                        )
                    }
                }
            }
            RemoveFileButton(importing = importing, onRemove = onRemove)
        }
    }
}

/** 副行的「大小 · 记录数」；两者都未知时返回空串（不伪造数字）。 */
private fun fileMetaText(sizeText: String, recordCount: Int?): String = when {
    sizeText.isNotEmpty() && recordCount != null -> sizeText + " · " + formatCount(recordCount) + " 条记录"
    sizeText.isNotEmpty() -> sizeText
    recordCount != null -> formatCount(recordCount) + " 条记录"
    else -> ""
}

/**
 * 移除文件（锚点 s08-file-clear）。
 *
 * 44dp 圆形点击区；按下底变深一档（fg 5% → 12%，--dur-state）+ LIGHT；焦点环 2dp；
 * 导入进行中 clickable(enabled = false) → 不进 Tab 序列，读屏补「不可用，导入进行中」。
 */
@Composable
private fun RemoveFileButton(importing: Boolean, onRemove: () -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val enabled = !importing
    val bg by animateColorAsState(
        targetValue = if (pressed && enabled) t.fgFade(12) else t.fgFade(5),
        animationSpec = tween(t.durState),
        label = "importClearBg",
    )
    Box(
        modifier = Modifier
            .size(t.tapMin)
            .clip(CircleShape)
            .background(bg)
            .keyboardFocusRing(CircleShape)
            .testTag("s08-file-clear")
            .semantics { contentDescription = if (importing) "移除文件，不可用，导入进行中" else "移除文件" }
            .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                haptic(HapticTone.LIGHT)
                onRemove()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Close,
            contentDescription = null,
            tint = if (enabled) t.muted else t.meta,
            modifier = Modifier.size(t.iconSize),
        )
    }
}

/**
 * 导入进度条（锚点 s08-progress）。
 *
 * 契约：无点击响应、不可拖动、长按无响应；文案三段推进；进度停在 92% 直到结果返回。
 */
@Composable
internal fun ImportProgressCard(
    progress: Float,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Column(
        modifier
            .padding(horizontal = t.space4)
            .testTag("s08-progress"),
    ) {
        Spacer(Modifier.height(t.space3))
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(t.space2)
                .clip(RoundedCornerShape(t.radiusPill))
                .background(t.borderSoft),
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(t.radiusPill))
                    // B1：进度条用 chart-2（不占每屏强调色预算）
                    .background(t.chart2),
            )
        }
        Spacer(Modifier.height(t.space3))
        Text(
            progressLabel(progress),
            style = MaterialTheme.typography.labelSmall,
            color = t.meta,
        )
    }
}
