package com.bsfeng.monitor.ui

// 设置页专用行：TokenRow 的「副标题可锚定」版本。
// 只用在一处 —— 契约锚点落在副标题本身（s09-quota-sub），而 TokenRow 的 subtitle 是
// 内部 Text，外部拿不到节点。布局与 TokenRow 同规格（图标 32dp / 行高 >= 52dp / 标题单行 /
// 副标题单行），按压与触感复用 PressFeedback + Haptics 基元，不新造第二套动效。

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 设置行（TokenRow 的锚点子集）。
 *
 * 与 TokenRow 的差别只有两处：副标题可传 subtitleModifier（承载契约锚点）、
 * 点击由本组件内建（0.96 按压 + LIGHT），调用点不再各补触感。
 *
 * @param onClick null = 不可点（纯展示行，不画按压态、不发触感）
 * @param subtitleModifier 副标题节点的修饰符 —— 需要锚点时传 testTag(锚点 id)
 */
@Composable
internal fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    subtitleModifier: Modifier = Modifier,
    subtitleColor: Color? = null,
    icon: ImageVector? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressModifier = if (onClick != null) Modifier.pressFeedback(interaction) else Modifier
    val clickModifier = if (onClick != null) {
        Modifier.clickable(
            interactionSource = interaction,
            indication = LocalIndication.current,
        ) {
            haptic(HapticTone.LIGHT)
            onClick()
        }
    } else {
        Modifier
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(pressModifier)
            .then(clickModifier)
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
                color = t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (subtitle != null) {
                Text(
                    subtitle,
                    modifier = subtitleModifier,
                    style = MaterialTheme.typography.bodySmall,
                    color = subtitleColor ?: t.muted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(t.space3))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(t.space2),
            ) {
                trailing()
            }
        }
    }
}
