package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/AddTransactionScreen.kt 的按钮；职责：主按钮（含按压缩放与禁用态）与幽灵按钮。

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/** 吸底主按钮：按下缩放 + 禁用走中性配方（保证对比度）。 */
@Composable
internal fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = t.springOf(MotionToken.Press),
        label = "primaryScale",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            // 原型 .btn{border-radius:var(--radius-md)} = 12dp（不是 radius-lg 的 18dp）
            .clip(RoundedCornerShape(t.radiusMd))
            // 禁用态走中性配方（底 fg 12% + 字 muted）：accent 降饱和得到的 #87BAEE 配白字
            // 只有 2.04:1，低于 WCAG AA；中性配方同时把两态的差别从「深浅」变成「有没有色」。
            .background(if (enabled) t.accent else t.fgFade(12))
            .clickable(interactionSource = interaction, indication = null) { if (enabled) onClick() }
            .padding(vertical = t.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = if (enabled) t.accentOn else t.muted,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 幽灵按钮（删除这笔交易等破坏性次级动作）。 */
@Composable
internal fun GhostButton(
    text: String,
    textColor: Color,
    background: Color,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.radiusMd))
            .background(background)
            .clickable { onClick() }
            .padding(vertical = t.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = textColor)
    }
}
