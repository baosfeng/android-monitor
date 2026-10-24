package com.bsfeng.monitor.transfer.ui

// 从 transfer/ui/TransferScreen.kt 拆出：PIN 互动的两个弹层
// （接收端展示 4 位 PIN + 倒计时、发送端输入 PIN）。

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
internal fun TransferPinPromptSection(
    prompt: com.bsfeng.monitor.transfer.service.TransferEngine.PinPrompt?,
    pinInput: String,
    onPinInputChange: (String) -> Unit,
    onDismissPin: () -> Unit,
    onDeny: (String) -> Unit,
    onSubmitPin: (String) -> Unit,
) {
    val t = LocalDesignTokens.current
if (prompt != null && prompt.direction == TransferDirection.RECV) {
    val peerAlias = prompt.peerAlias.trim().ifBlank { UNKNOWN_PEER_ALIAS }
    ConfirmDialog(
        visible = true,
        title = "「$peerAlias」请求发送",
        message = "${prompt.fileName} · 在对方设备上输入下方 PIN 即可开始",
        confirmLabel = "知道了",
        dismissLabel = "拒绝",
        onConfirm = onDismissPin,
        onDismiss = { onDeny(prompt.sessionId) },
        extraContent = {
            val remaining = rememberSecondsUntil(prompt.expiresAtMs)
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(t.space2, Alignment.CenterHorizontally),
                ) {
                    prompt.pin?.forEachIndexed { index, char -> PinDigit(char, index) }
                }
                if (remaining != null) {
                    Spacer(Modifier.height(t.space2))
                    Text(
                        "${remaining}s 后自动拒绝",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (remaining <= 10) t.danger else t.muted,
                    )
                }
            }
        },
    )
} else if (prompt != null) {
    val peerAlias = prompt.peerAlias.trim().ifBlank { UNKNOWN_PEER_ALIAS }
    val message = prompt.message.ifBlank { "请输入「$peerAlias」上显示的 4 位 PIN" }
    ConfirmDialog(
        visible = true,
        title = "配对确认",
        message = message,
        confirmLabel = "确定",
        dismissLabel = "取消",
        onConfirm = { onSubmitPin(pinInput) },
        onDismiss = onDismissPin,
        extraContent = {
            BasicTextField(
                value = pinInput,
                onValueChange = { text -> onPinInputChange(text.filter { it.isDigit() }.take(4)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                textStyle = MaterialTheme.typography.headlineMedium.copy(
                    color = t.accent,
                    textAlign = TextAlign.Center,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(t.radiusMd))
                    .background(t.fgFade(6))
                    .padding(t.space3),
                decorationBox = { inner ->
                    Box(contentAlignment = Alignment.Center) {
                        if (pinInput.isEmpty()) {
                            Text(
                                "····",
                                style = MaterialTheme.typography.headlineMedium,
                                color = t.muted,
                            )
                        }
                        inner()
                    }
                },
            )
        },
    )
}
}
