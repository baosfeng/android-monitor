package com.bsfeng.monitor.transfer.ui

// PIN 互动的两个弹层：接收端展示 4 位 PIN + 倒计时（无锚点）、发送端输入 PIN（C-14 输入 / C-15 验证并发送）。
//
// C-14：输入即过滤非数字并截断 4 位；每位填入 scale 0.9→1（--dur-press）；满 4 位 C-15 才可用；
//       打开即聚焦输入框；关闭清空输入；错误时保留输入并在说明行提示。
// C-15：点击后按钮转圈并禁用；成功由引擎关闭弹层；失败保持弹层与焦点；连续 3 次错误标题追加提示；
//       15s 未落定时恢复按钮并提示超时（避免「点了没反应」）。
//
// 承载走 ui/components 的 [AppDialog] / [ConfirmDialog]（窗口级浮层 + 返回键 / 点外部关闭）。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.UNKNOWN_PEER_ALIAS
import com.bsfeng.monitor.transfer.service.TransferEngine
import com.bsfeng.monitor.ui.components.AppDialog
import com.bsfeng.monitor.ui.components.ConfirmDialog
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import kotlinx.coroutines.delay

/** PIN 长度（协议 §5）。 */
internal const val PIN_LENGTH: Int = 4

/** C-15 按钮转圈时长（口径：200ms 内完成）。 */
private const val PIN_SUBMIT_SPIN_MS: Long = 200L

/** 连续错误多少次后标题追加提示（锚点 C-15）。 */
internal const val PIN_ERROR_HINT_AFTER: Int = 3

/** C-15 请求超时（口径：15s 后恢复按钮并提示）。 */
private const val PIN_TIMEOUT_MS: Long = 15_000L

/** 发送端 PIN 错误文案（引擎经 prompt.message 下发，逐字）。 */
internal const val PIN_ERROR_TEXT: String = "PIN 不正确，请重试"

/** 超时提示（锚点 C-15 边界）。 */
internal const val PIN_TIMEOUT_TEXT: String = "网络超时，请重试"

/** PIN 弹层入口：按方向分发到接收端展示 / 发送端输入。 */
@Composable
internal fun TransferPinPromptSection(
    prompt: TransferEngine.PinPrompt?,
    pinInput: String,
    onPinInputChange: (String) -> Unit,
    onDismissPin: () -> Unit,
    onDeny: (String) -> Unit,
    onSubmitPin: (String) -> Unit,
    onTimeout: () -> Unit,
) {
    if (prompt == null) return
    if (prompt.direction == TransferDirection.RECV) {
        IncomingPinDialog(prompt, onDismissPin, onDeny)
    } else {
        OutgoingPinDialog(
            prompt = prompt,
            pinInput = pinInput,
            onPinInputChange = onPinInputChange,
            onDismissPin = onDismissPin,
            onSubmitPin = onSubmitPin,
            onTimeout = onTimeout,
        )
    }
}

/** 接收端：展示 4 位 PIN + 到期倒计时（拒绝 = 点「拒绝」/ 点遮罩 / 返回键）。 */
@Composable
private fun IncomingPinDialog(
    prompt: TransferEngine.PinPrompt,
    onDismissPin: () -> Unit,
    onDeny: (String) -> Unit,
) {
    val t = LocalDesignTokens.current
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
}

/** 发送端：C-14 输入 + C-15 验证并发送。 */
@Composable
private fun OutgoingPinDialog(
    prompt: TransferEngine.PinPrompt,
    pinInput: String,
    onPinInputChange: (String) -> Unit,
    onDismissPin: () -> Unit,
    onSubmitPin: (String) -> Unit,
    onTimeout: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val peerAlias = prompt.peerAlias.trim().ifBlank { UNKNOWN_PEER_ALIAS }
    val isError = prompt.message.contains(PIN_ERROR_TEXT)
    var errorCount by remember(prompt.sessionId) { mutableIntStateOf(if (isError) 1 else 0) }
    var submitting by remember(prompt.sessionId) { mutableStateOf(false) }
    var awaiting by remember(prompt.sessionId) { mutableStateOf(false) }
    val inputFocus = remember { FocusRequester() }
    // 错误累计：message 变化即一次失败结果（同一次失败不会重复计数）
    LaunchedEffect(prompt.message) {
        if (isError) errorCount += 1
    }
    // C-15：点击后至少转圈 200ms，避免一帧闪过
    LaunchedEffect(submitting) {
        if (submitting) {
            delay(PIN_SUBMIT_SPIN_MS)
            submitting = false
        }
    }
    // C-15 超时兜底：15s 内 prompt 没有新结果就恢复按钮并提示（不把「没反应」留给用户）
    LaunchedEffect(awaiting, prompt.message) {
        if (!awaiting) return@LaunchedEffect
        delay(PIN_TIMEOUT_MS)
        if (awaiting) {
            awaiting = false
            onTimeout()
        }
    }
    // C-14：打开即聚焦输入框；出错后焦点留在输入框（可直接重试）
    LaunchedEffect(prompt.sessionId, isError) {
        runCatching { inputFocus.requestFocus() }
    }
    val enabled = pinInput.length == PIN_LENGTH && !submitting && !awaiting
    val title = if (errorCount >= PIN_ERROR_HINT_AFTER) {
        "配对确认 · 多次错误，请确认对端 PIN"
    } else {
        "配对确认"
    }
    val message = prompt.message.ifBlank { "请输入「$peerAlias」上显示的 4 位 PIN" }
    AppDialog(visible = true, onDismiss = onDismissPin) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = t.fg)
        Spacer(Modifier.height(t.space2))
        Text(
            message,
            style = MaterialTheme.typography.bodySmall,
            color = if (isError) t.danger else t.muted,
        )
        Spacer(Modifier.height(t.space3))
        // 4 个 PIN 位：已填入显示数字（每位 scale 0.9→1），未填入显示占位点
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(t.space2, Alignment.CenterHorizontally),
        ) {
            repeat(PIN_LENGTH) { index ->
                val char = pinInput.getOrNull(index)
                if (char != null) {
                    PinDigit(char, index)
                } else {
                    PinPlaceholder()
                }
            }
        }
        Spacer(Modifier.height(t.space3))
        BasicTextField(
            value = pinInput,
            onValueChange = { text -> onPinInputChange(text.filter { it.isDigit() }.take(PIN_LENGTH)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (pinInput.length == PIN_LENGTH) submitPin(pinInput, onSubmitPin) { submitting = true; awaiting = true } }),
            textStyle = MaterialTheme.typography.headlineMedium.copy(
                color = t.accent,
                textAlign = TextAlign.Center,
            ),
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(t.radiusMd))
                .background(t.fgFade(6))
                .padding(t.space3)
                .focusRequester(inputFocus)
                .testTag("C-14"),
            decorationBox = { inner ->
                Box(contentAlignment = Alignment.Center) {
                    if (pinInput.isEmpty()) {
                        Text("····", style = MaterialTheme.typography.headlineMedium, color = t.muted)
                    }
                    inner()
                }
            },
        )
        Spacer(Modifier.height(t.space4))
        Row(horizontalArrangement = Arrangement.spacedBy(t.space2)) {
            TransferSheetButton(
                label = "稍后再传",
                primary = false,
                danger = false,
                busy = false,
                onClick = {
                    onPinInputChange("")
                    onDismissPin()
                },
                modifier = Modifier.weight(1f),
            )
            TransferSheetButton(
                label = "验证并发送",
                primary = true,
                danger = false,
                busy = submitting || awaiting,
                enabled = enabled,
                onClick = { submitPin(pinInput, onSubmitPin) { submitting = true; awaiting = true } },
                modifier = Modifier
                    .weight(1f)
                    .testTag("C-15"),
            )
        }
    }
}

/** 统一的提交入口：长度不足 4 位不发请求。 */
private fun submitPin(pin: String, onSubmitPin: (String) -> Unit, onStarted: () -> Unit) {
    if (pin.length != PIN_LENGTH) return
    onStarted()
    onSubmitPin(pin)
}

/** 未填入的 PIN 位：空槽（原型 .pinbox .d.empty）。 */
@Composable
private fun PinPlaceholder() {
    val t = LocalDesignTokens.current
    Box(
        modifier = Modifier
            .width(t.space8)
            .height(t.space8)
            .clip(RoundedCornerShape(t.radiusMd))
            .background(t.fgFade(6)),
        contentAlignment = Alignment.Center,
    ) {
        Text("•", style = MaterialTheme.typography.headlineMedium, color = t.border)
    }
}
