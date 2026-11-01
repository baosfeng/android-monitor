package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/ImportScreen.kt 的 pick item；职责：选择数据文件的入口卡与读取错误提示。

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Upload
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.accentSoftFill

/**
 * 选择数据文件入口（锚点 s08-drop）。
 *
 * 口径：整卡全宽可点，按下缩到 0.96（共享基元）+ 底变 fg 6%（--dur-state）+ LIGHT 触感，
 * 随即拉起系统文件选择器；键盘 Enter / Space 由 clickable 自带，focus-visible 2dp 环画在卡片内侧、
 * 不改卡片高度；导入进行中整卡不可聚焦。
 */
@Composable
internal fun ImportPickCard(
    readError: String,
    onPick: () -> Unit,
    enabled: Boolean = true,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(t.radiusLg)
    val bg by animateColorAsState(
        targetValue = if (pressed && enabled) lerp(t.surface, t.fg, 0.06f) else t.surface,
        animationSpec = tween(t.durState),
        label = "importPickBg",
    )
    Column(Modifier.padding(horizontal = t.space4)) {
        Spacer(Modifier.height(t.space3))
        Box(
            modifier = modifier
                .fillMaxWidth()
                .pressFeedback(interaction, enabled)
                .clip(shape)
                .background(bg)
                .border(1.dp, t.borderSoft, shape)
                .keyboardFocusRing(shape)
                .testTag("card.pick")
                .clickable(interactionSource = interaction, indication = null, enabled = enabled) {
                    haptic(HapticTone.LIGHT)
                    onPick()
                }
                .padding(t.space6),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(
                    modifier = Modifier
                        .size(t.tapMin)
                        .clip(CircleShape)
                        .background(t.accentSoftFill()),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        Icons.Filled.Upload,
                        contentDescription = null,
                        tint = t.accent,
                        modifier = Modifier.size(t.iconSize),
                    )
                }
                Spacer(Modifier.height(t.space3))
                Text(
                    "选择数据文件",
                    style = MaterialTheme.typography.titleSmall,
                    color = t.fg,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(t.space1))
                Text(
                    "熊猫记账导出的 .db 文件",
                    style = MaterialTheme.typography.labelSmall,
                    color = t.muted,
                )
            }
        }
        if (readError.isNotEmpty()) {
            Spacer(Modifier.height(t.space2))
            Text(
                readError,
                style = MaterialTheme.typography.labelSmall,
                color = t.dangerText,
            )
        }
    }
}
