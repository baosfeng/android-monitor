package com.bsfeng.monitor.transfer.ui

// 附近设备行（C-11 选中 / C-12 移除配对）。
//
// 口径要点：
//  - 选中：边框 --border-soft → --accent（含 1dp 描边）+ 右侧对勾 + LIGHT 触感；再点取消选中；
//  - 离线：整行降到 45% 不透明、不可选中，副行写「离线 · 上次 HH:mm」；
//  - 已配对：行内「已配对」徽标 + 行尾「移除配对」（C-12），点击只开确认框，确认后才删 pairingKey。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.UNKNOWN_PEER_ALIAS
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.rowPressFeedback
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.components.tokenShadow
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.accentFade
import com.bsfeng.monitor.ui.theme.fgFade

/** 离线行不透明度（原型 .dev.offline：opacity .45）。 */
private const val OFFLINE_ALPHA: Float = 0.45f

@Composable
internal fun DeviceRow(
    device: PeerDevice,
    selected: Boolean,
    paired: Boolean,
    index: Int,
    focusPending: String?,
    onFocusConsumed: () -> Unit,
    onClick: () -> Unit,
    onRemovePairing: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    val alias = device.alias.trim().ifBlank { UNKNOWN_PEER_ALIAS }
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .staggerEnterAt(index)
            .graphicsLayer { alpha = if (device.online) 1f else OFFLINE_ALPHA }
            .tokenShadow(if (selected) 1 else 0, shape)
            .clip(shape)
            .background(t.surface)
            .border(1.dp, if (selected) t.accent else t.borderSoft, shape)
            .rowPressFeedback(interaction)
            .clickable(
                enabled = device.online,
                interactionSource = interaction,
                indication = null,
            ) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .testTag("C-11")
            .returnFocusWhen("C-11:" + device.deviceId, focusPending, onFocusConsumed)
            .padding(t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(t.iconSize)
                .clip(CircleShape)
                .background(if (device.online) t.accentFade(14) else t.fgFade(8)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                // 协议常量 DeviceTypes.MAC = "macos"；对端若发 "mac" / "macbook" 也按桌面设备显示
                imageVector = if (device.deviceType.startsWith("mac", ignoreCase = true)) {
                    Icons.Outlined.Computer
                } else {
                    Icons.Outlined.Smartphone
                },
                contentDescription = alias,
                tint = if (device.online) t.accent else t.muted,
                modifier = Modifier.size(t.space6),
            )
        }
        Spacer(Modifier.width(t.space3))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(alias, style = MaterialTheme.typography.titleSmall, color = t.fg)
                if (paired) {
                    Spacer(Modifier.width(t.space2))
                    Badge("已配对", tone = BadgeTone.Accent)
                }
            }
            Spacer(Modifier.height(t.space1))
            Text(
                deviceSubtitle(device),
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
        if (paired) {
            UnpairButton(
                deviceId = device.deviceId,
                focusPending = focusPending,
                onFocusConsumed = onFocusConsumed,
                onClick = onRemovePairing,
            )
        }
        if (selected) {
            Spacer(Modifier.width(t.space1))
            Icon(
                Icons.Filled.CheckCircle,
                contentDescription = "已选中",
                tint = t.accent,
                modifier = Modifier.size(t.space5),
            )
        }
    }
}

/** 设备副行：在线「在线 · ip:port」；离线「离线 · 上次 HH:mm」（锚点 C-11 边界文案）。 */
internal fun deviceSubtitle(device: PeerDevice, clock: (Long) -> String = ::formatClock): String =
    if (device.online) {
        "在线 · " + device.hostAddress.ifBlank { "未知地址" } + ":" + device.port
    } else {
        "离线 · 上次 " + clock(device.lastSeenMs)
    }

/**
 * C-12「移除配对」：点击只开确认框（页面负责二次确认与真正删 pairingKey）。
 *
 * 点击区 ≥ 44dp；按下 0.96（--dur-press）；确认框取消后焦点回本按钮。
 */
@Composable
private fun UnpairButton(
    deviceId: String,
    focusPending: String?,
    onFocusConsumed: () -> Unit,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    Box(
        modifier = Modifier
            .widthIn(min = t.tapMin + t.space1)
            .heightIn(min = t.tapMin + t.space1)
            .pressFeedback(interaction)
            .clip(RoundedCornerShape(t.radiusSm))
            .clickable(interactionSource = interaction, indication = null) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .testTag("C-12")
            .returnFocusWhen(unpairTriggerOf(deviceId), focusPending, onFocusConsumed),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "移除配对",
            style = MaterialTheme.typography.labelMedium,
            color = t.muted,
        )
    }
}
