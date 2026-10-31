package com.bsfeng.monitor.ui.live

// 从 ui/live/LiveCards.kt 拆出：页头与页头按钮 —— MetaText（等宽元信息）、ScreenHead（大标题 + 徽标）、HeadIconButton（44dp 触达图标按钮）。负责「一屏最上面那条」。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

@Composable
fun MetaText(text: String, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Text(text, modifier = modifier, style = MaterialTheme.typography.labelMedium, color = t.meta)
}

/**
 * 页面大标题：副标题 meta 在上、标题在下，右侧可选动作按钮。
 *
 * [consumeTopInset] 默认 false：页头在 Scaffold 内容区里，状态栏避让已由 Scaffold 的 inset 完成。
 * true 时页头自己加 `statusBarsPadding()`，供 Scaffold 之外的整屏页（覆盖层）使用。
 */
@Composable
fun ScreenHead(
    title: String,
    meta: String,
    modifier: Modifier = Modifier,
    actionIcon: ImageVector? = null,
    actionDesc: String = "",
    onAction: (() -> Unit)? = null,
    consumeTopInset: Boolean = false,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (consumeTopInset) Modifier.statusBarsPadding() else Modifier)
            .padding(start = t.space4, end = t.space4, top = t.space4, bottom = t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            MetaText(meta)
            Spacer(Modifier.height(t.space2))
            Text(title, style = MaterialTheme.typography.headlineMedium, color = t.fg)
        }
        if (actionIcon != null && onAction != null) {
            Spacer(Modifier.width(t.space3))
            HeadIconButton(icon = actionIcon, contentDescription = actionDesc, onClick = onAction)
        }
    }
}

@Composable
fun HeadIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Box(
        modifier = modifier
            .size(t.iconSize)
            .clip(CircleShape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, CircleShape)
            .testTag("button.icon")
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = t.fg2,
            modifier = Modifier.size(t.iconSize / 2),
        )
    }
}

// ───────────────────────── 行 / 卡 ─────────────────────────
