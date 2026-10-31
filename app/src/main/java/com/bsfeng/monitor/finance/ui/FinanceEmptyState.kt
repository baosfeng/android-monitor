package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceUIKit.kt 的空态卡与 CTA 按钮；职责：五屏空态（图标 + 标题 + 说明 + 行动按钮）。

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.accentSoftFill

/**
 * 记账五屏的空态卡：图标 + 标题 + 说明 + 行动按钮。
 *
 * 文案逐屏取原型 `st-title` / `st-desc`（s04:163-165 / s05:108-110 / s06:117-119 /
 * s07:134-136 / s08:103-105），每屏文案与 CTA 都不同；自绘而不改
 * [com.bsfeng.monitor.ui.components.StateContainer]（该组件由 W1 维护）。
 */
@Composable
internal fun FinanceEmptyState(
    title: String,
    desc: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.radiusLg))
            .background(t.surfaceWarm)
            .padding(t.space6),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            modifier = Modifier
                .size(t.space12)
                .clip(RoundedCornerShape(t.radiusMd))
                .background(t.accentSoftFill()),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, tint = t.accent, modifier = Modifier.size(t.iconSize))
        }
        Spacer(Modifier.height(t.space3))
        Text(title, style = MaterialTheme.typography.titleSmall, color = t.fg, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(t.space1))
        Text(desc, style = MaterialTheme.typography.bodySmall, color = t.muted, textAlign = TextAlign.Center)
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(t.space3))
            FinanceCtaButton(text = actionText, onClick = onAction)
        }
    }
}

/** 原型 `.btn-ghost` 的空态行动按钮（surface-warm 底 + accent 文字）。 */
@Composable
internal fun FinanceCtaButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(t.radiusLg))
            .background(t.surfaceWarm)
            .testTag("button.cta")
            .clickable { onClick() }
            .padding(horizontal = t.space6, vertical = t.space2),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = t.accent, fontWeight = FontWeight.SemiBold)
    }
}
