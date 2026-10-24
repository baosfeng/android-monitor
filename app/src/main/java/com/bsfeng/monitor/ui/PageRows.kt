package com.bsfeng.monitor.ui

// 从 ui/ComponentGalleryScreen.kt 拆出：行式排版 —— TokenDivider（1px 分隔线）、TokenRow（图标 + 标题/副标题 + 尾部）、ListRow（紧凑行）、TailNum（等宽尾部数字）、TailChevron（尾部箭头）、ProgressBar（胶囊进度条）。负责「一行一条信息」。
// 这些件原本暂存在展厅文件里，现按职责拆出；仍是 ui 包内的共用件（internal）。

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 1px 分隔线（原型 .row 的 border-top，首行不画）。 */
@Composable
internal fun TokenDivider(first: Boolean) {
    val t = LocalDesignTokens.current
    if (first) return
    Box(Modifier.fillMaxWidth().height(1.dp).background(t.borderSoft))
}

/** 列表行（原型 .row：左图标 + 标题 / 副标题 + 右侧尾部）。 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun TokenRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector? = null,
    titleColor: androidx.compose.ui.graphics.Color? = null,
    subtitleColor: androidx.compose.ui.graphics.Color? = null,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(
                when {
                    onLongClick != null -> Modifier.combinedClickable(
                        onClick = { onClick?.invoke() },
                        onLongClick = onLongClick,
                    )
                    onClick != null -> Modifier.clickable { onClick() }
                    else -> Modifier
                }
            )
            .padding(horizontal = t.space4, vertical = t.space2)
            .heightIn(min = t.rowMinH - t.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                Modifier
                    .size(t.space8)
                    .clip(RoundedCornerShape(t.radiusMd))
                    .background(t.surfaceWarm),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, tint = t.fg2, modifier = Modifier.size(t.space5))
            }
            Spacer(Modifier.width(t.space3))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.bodyLarge,
                color = titleColor ?: t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor ?: t.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(t.space3))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(t.space2)) {
                trailing()
            }
        }
    }
}

/** 紧凑行（原型 .list-row，48dp）。 */
@Composable
internal fun ListRow(title: String, value: String, subtitle: String? = null) {
    val t = LocalDesignTokens.current
    Row(
        Modifier.fillMaxWidth().heightIn(min = t.space12).padding(horizontal = t.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = t.muted, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Text(value, style = MaterialTheme.typography.bodySmall, color = t.muted)
    }
}

/** 尾部数字（原型 .num：等宽、tabular）。 */
@Composable
internal fun TailNum(text: String) {
    val t = LocalDesignTokens.current
    Text(text, style = MaterialTheme.typography.bodySmall, color = t.muted)
}

/** 尾部箭头（原型 .chev）。 */
@Composable
internal fun TailChevron() {
    val t = LocalDesignTokens.current
    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = null, tint = t.muted, modifier = Modifier.size(t.space5))
}

/** 进度条（原型 .bar：pill 轨道 + 填充，入场 700ms --ease-out）。 */
@Composable
internal fun ProgressBar(fraction: Float, modifier: Modifier = Modifier, color: androidx.compose.ui.graphics.Color? = null) {
    val t = LocalDesignTokens.current
    var grown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { grown = true }
    val f by animateFloatAsState(
        targetValue = if (grown) fraction.coerceIn(0f, 1f) else 0f,
        animationSpec = tween(t.durChart, easing = t.easeOut),
        label = "progress",
    )
    Box(
        modifier
            .fillMaxWidth()
            .height(t.space2)
            .clip(RoundedCornerShape(t.radiusPill))
            .background(t.borderSoft),
    ) {
        Box(
            Modifier
                .fillMaxWidth(f)
                .height(t.space2)
                .clip(RoundedCornerShape(t.radiusPill))
                .background(color ?: t.accent),
        )
    }
}
