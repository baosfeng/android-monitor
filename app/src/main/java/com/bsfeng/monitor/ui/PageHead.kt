package com.bsfeng.monitor.ui

// 从 ui/ComponentGalleryScreen.kt 拆出：屏头与分级标题 —— ScreenHead（标题 + 副标题 + 徽标/动作）、GroupLabel（原型 .group-label）、SecHead（原型 .sec-head）。负责「一屏的分层标题」。
// 这些件原本暂存在展厅文件里，现按职责拆出；仍是 ui 包内的共用件（internal）。

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 屏头：可选返回按钮 + 标题 + 副标题 + 右侧徽标。
 *
 * [consumeTopInset] 默认 false（Scaffold 内容区里由 Scaffold 让开状态栏）；true 时页头自己
 * 加 `statusBarsPadding()`，供 Scaffold 之外的整屏页使用。 */
@Composable
internal fun ScreenHead(
    title: String,
    modifier: Modifier = Modifier,
    meta: String? = null,
    onBack: (() -> Unit)? = null,
    badge: (@Composable () -> Unit)? = null,
    consumeTopInset: Boolean = false,
) {
    val t = LocalDesignTokens.current
    // 返回按钮的按压与触感（全站二级页口径：0.96 + 120ms + LIGHT）
    val haptic = rememberHaptic()
    val backInteraction = remember { MutableInteractionSource() }
    Column(
        modifier
            .fillMaxWidth()
            .then(if (consumeTopInset) Modifier.statusBarsPadding() else Modifier)
            .padding(start = t.space4, end = t.space4, top = t.space2, bottom = t.space3),
    ) {
        if (onBack != null) {
            Box(
                Modifier
                    .size(t.space8)
                    .clip(RoundedCornerShape(t.radiusPill))
                    .background(t.surfaceWarm)
                    .pressFeedback(backInteraction)
                    .clickable(
                        interactionSource = backInteraction,
                        indication = LocalIndication.current,
                    ) {
                        haptic(HapticTone.LIGHT)
                        onBack()
                    },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.Filled.KeyboardArrowLeft,
                    contentDescription = "返回",
                    tint = t.fg,
                    modifier = Modifier.size(t.space5),
                )
            }
            Spacer(Modifier.height(t.space2))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.headlineSmall, color = t.fg, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (meta != null) {
                    Spacer(Modifier.height(t.space1))
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = t.muted)
                }
            }
            if (badge != null) {
                Spacer(Modifier.width(t.space2))
                badge()
            }
        }
    }
}

/** 分组小标题（原型 .group-label）。 */
@Composable
internal fun GroupLabel(text: String, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = t.muted,
        modifier = modifier.padding(start = t.space4, end = t.space4, bottom = t.space2),
    )
}

/** 小节头：左标题 + 右说明（原型 .sec-head）。 */
@Composable
internal fun SecHead(label: String, modifier: Modifier = Modifier, right: String? = null) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = t.space4),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = t.muted, modifier = Modifier.weight(1f))
        if (right != null) {
            Text(right, style = MaterialTheme.typography.bodySmall, color = t.muted)
        }
    }
}
