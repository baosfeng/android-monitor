package com.bsfeng.monitor.transfer.ui

// 从 transfer/ui/TransferScreen.kt 拆出：设备列表行（在线状态 / 配对标记 / 选中态 / 移除配对）。

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Cancel
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Upload
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.domain.transfer.TransferTaskGroups
import com.bsfeng.monitor.domain.transfer.TransferFormat
import com.bsfeng.monitor.transfer.service.ReceivingAutoClose
import com.bsfeng.monitor.transfer.service.ReceivingState
import com.bsfeng.monitor.domain.transfer.UNKNOWN_PEER_ALIAS
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.ConfirmDialog
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.rememberMotionEnabled
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.ui.components.tokenShadow
import com.bsfeng.monitor.ui.live.ScreenHead
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.accentFade
import com.bsfeng.monitor.ui.theme.fgFade
import com.bsfeng.monitor.ui.theme.opaquePanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull

@Composable
internal fun DeviceRow(
    device: PeerDevice,
    selected: Boolean,
    paired: Boolean,
    index: Int,
    onClick: () -> Unit,
    onRemovePairing: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    val alias = device.alias.trim().ifBlank { UNKNOWN_PEER_ALIAS }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .staggerEnterAt(index)
            .tokenShadow(if (selected) 1 else 0, shape)
            .clip(shape)
            .background(t.surface)
            .border(1.dp, if (selected) t.accent else t.borderSoft, shape)
            .clickable(onClick = onClick)
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
                buildString {
                    append(if (device.online) "在线" else "离线")
                    append(" · ${device.hostAddress.ifBlank { "未知地址" }}:${device.port}")
                },
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
        if (paired) {
            Text(
                "移除配对",
                style = MaterialTheme.typography.labelMedium,
                color = t.muted,
                modifier = Modifier
                    .clip(RoundedCornerShape(t.radiusSm))
                    .clickable(onClick = onRemovePairing)
                    .padding(horizontal = t.space2, vertical = t.space1),
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

/**
 * 可滑动删除的任务卡。
 *
 * 只对**已终结**任务生效：左滑 / 右滑都触发删除；进行中任务滑出后 `confirmValueChange` 返回 false，
 * 由 Material3 回弹到原位。背景统一 `--danger` + 垃圾桶图标。
 */
