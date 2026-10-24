package com.bsfeng.monitor.ui.components

// 从 ui/components/Controls.kt 拆出：Badge 徽标 —— BadgeTone 色调枚举与 Badge 组件（原型 .badge）。负责「状态/数量的小标签」。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import com.bsfeng.monitor.ui.theme.monitorMonoBadge

/** 原型 .badge：min-height / min-width 20px、padding 0 6px。 */
private val BADGE_H = 20.dp

private val BADGE_PAD_X = 6.dp

enum class BadgeTone { Neutral, Accent, Success, Warn, Danger }

@Composable
fun Badge(text: String, modifier: Modifier = Modifier, tone: BadgeTone = BadgeTone.Neutral) {
    val t = LocalDesignTokens.current
    val color = when (tone) {
        BadgeTone.Neutral -> t.muted
        BadgeTone.Accent -> t.accent
        BadgeTone.Success -> t.successText
        BadgeTone.Warn -> t.warnText
        BadgeTone.Danger -> t.dangerText
    }
    // 原型 .badge：min-width/height 20、padding 0 6px、底 color-mix(fg 9%)、无描边、等宽 11sp
    Box(
        modifier = modifier
            .heightIn(min = BADGE_H)
            .widthIn(min = BADGE_H)
            .clip(RoundedCornerShape(t.radiusPill))
            .background(t.fgFade(9))
            .padding(horizontal = BADGE_PAD_X),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = monitorMonoBadge, color = color)
    }
}
