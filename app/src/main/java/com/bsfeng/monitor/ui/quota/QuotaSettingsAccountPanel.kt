package com.bsfeng.monitor.ui.quota

// s19 账号面板：凭据输入 + 显示/隐藏 + 从剪贴板粘贴 + 保存并查询 + 断开连接。
// 拆出独立文件：输入/粘贴/二次确认是一组，账号行头与分组是另一组（单文件 ≤300 行）。

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ContentPaste
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/** 面板里那一条凭据的字段名（原型 field-lab 逐字）。 */
internal fun credentialFieldLabel(info: QuotaProviderInfo): String = when (info.balanceKind.name) {
    "PLATFORM_TOKEN" -> "平台会话令牌（不是 sk- API Key）"
    "SESSION_COOKIE" -> "会话令牌"
    else -> "API Key"
}

/**
 * 账号展开面板。
 *
 * 交互口径（原型 s19-panel-*）：
 * - 输入框密码遮罩；眼睛按钮只改本行的显示态，不落盘、不发请求；
 * - 「从剪贴板粘贴」只填输入框、**不自动保存**（用户还要能改一眼再保存）；
 * - 保存成功后由屏层收行；失败保留输入并就地提示；
 * - 断开连接是破坏性操作：二次确认后才清凭据 + 快照。
 */
@Composable
internal fun CredentialPanel(
    info: QuotaProviderInfo,
    suffix: String,
    saving: Boolean,
    saveError: String?,
    configured: Boolean,
    onSave: (String) -> Unit,
    onClear: () -> Unit,
    onToast: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val haptic = rememberHaptic()
    var draft by remember(info.instanceId) { mutableStateOf("") }
    var revealed by remember(info.instanceId) { mutableStateOf(false) }
    var hint by remember(info.instanceId) { mutableStateOf(PASTE_HINT_IDLE) }
    var confirmClear by remember(info.instanceId) { mutableStateOf(false) }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(t.surfaceWarm)
            // 原型 .acc 的 border-top 画在面板自己的盒子里，不另插分割线
            .topHairline()
            // 锚点先于左右内边距：量到的是整块面板（359dp），与原型 .acc 一致
            .accountPanelTagOf(suffix)
            .padding(start = t.space3, end = t.space3, bottom = t.space3),
    ) {
        // 原型 .acc 的 field-lab 上边距 8；Compose 11sp 的 CJK 行盒比 CSS 高 ~1.5，
        // 收到 6 才能让输入框 y 落进容差（8 → 实测 y 差 3.8）
        Spacer(Modifier.height(6.dp))
        Text(
            credentialFieldLabel(info),
            style = MaterialTheme.typography.labelSmall
                .copy(fontSize = 11.sp, lineHeight = (11 * t.leadingBody).sp)
                .quotaTight(),
            color = t.fg2,
            fontWeight = FontWeight.SemiBold,
        )
        Spacer(Modifier.height(t.space1))
        Row(verticalAlignment = Alignment.CenterVertically) {
            CredentialField(
                value = draft,
                onValueChange = { draft = it },
                obscure = !revealed,
                label = info.displayName + " " + credentialFieldLabel(info),
                modifier = Modifier.weight(1f).accountInputTagOf(suffix),
            )
            Spacer(Modifier.width(t.space2))
            RevealButton(
                revealed = revealed,
                onToggle = {
                    revealed = !revealed
                    onToast(if (revealed) "已显示令牌 · 仅本机可见" else "已隐藏令牌")
                },
                modifier = Modifier.accountRevealTagOf(suffix),
            )
        }
        Spacer(Modifier.height(t.space2))
        PasteButton(
            contentDescription = "从剪贴板粘贴 " + info.displayName + " " + credentialFieldLabel(info),
            onClick = {
                haptic(HapticTone.LIGHT)
                val text = readClipboard(context)
                if (text.isBlank()) {
                    hint = PASTE_HINT_EMPTY
                    onToast("没读到内容 · 先复制 Key 再点这里")
                } else {
                    draft = text
                    hint = PASTE_HINT_OK
                    onToast("已粘贴剪贴板内容 · 确认后再保存")
                }
            },
            modifier = Modifier.accountPasteTagOf(suffix),
        )
        Spacer(Modifier.height(t.space1))
        Text(
            hint,
            // 目标高 14.8（原型 .paste-hint）；Compose 单行会多 ~1.2 的行盒余量 → 13.6
            style = MaterialTheme.typography.labelSmall
                .copy(fontSize = 11.sp, lineHeight = 13.6.sp)
                .quotaTight(),
            color = if (hint == PASTE_HINT_OK) t.successText else t.muted,
            // 原型 .paste-hint 是整块宽（335）；不 fillMaxWidth 会被文字宽量成 188.7
            modifier = Modifier.fillMaxWidth().accountPasteHintTagOf(suffix),
        )
        Spacer(Modifier.height(t.space2))
        PanelButton(
            label = "保存并查询",
            enabled = !saving && draft.isNotBlank(),
            primary = true,
            onClick = {
                haptic(HapticTone.SUCCESS)
                onSave(draft.trim())
            },
            modifier = Modifier.accountSaveTagOf(suffix),
        )
        if (configured) {
            Spacer(Modifier.height(t.space2))
            PanelButton(
                label = "断开连接",
                enabled = !saving,
                primary = false,
                onClick = {
                    haptic(HapticTone.LIGHT)
                    confirmClear = true
                },
                modifier = Modifier.accountClearTagOf(suffix),
            )
        }
        if (saveError != null) {
            Spacer(Modifier.height(t.space2))
            // 就地提示：失败时输入保留，改一改再点保存即可
            Text(
                "保存后查询失败：" + saveError + " · 输入已保留，可修改后重试",
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = t.dangerText,
            )
        }
        Spacer(Modifier.height(t.space2))
        Text(
            // 原型 .acc-note 实测两行 26.4（line-height 13.2）；显式断行避免 CJK 度量差异导致单行
            "Key 用 Android Keystore 加密存本机；\n清空后不再显示上次余额。",
            style = MaterialTheme.typography.labelSmall
                .copy(fontSize = 11.sp, lineHeight = (11 * 1.2f).sp)
                .quotaTight(),
            color = t.muted,
        )
    }
    ClearCredentialsConfirmDialog(
        visible = confirmClear,
        onDismiss = { confirmClear = false },
        onConfirm = {
            confirmClear = false
            onClear()
        },
    )
}

/** 密码输入框：44dp 高、白底、1dp 描边；明态由眼睛按钮控制。 */
@Composable
private fun CredentialField(
    value: String,
    onValueChange: (String) -> Unit,
    obscure: Boolean,
    label: String,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    Box(
        modifier = modifier
            .height(t.tapMin)
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.border, shape)
            .padding(horizontal = t.space3),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            // 原型 placeholder：sk-…（不预填任何真实令牌）
            Text("sk-…", style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp), color = t.meta)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            modifier = Modifier
                .fillMaxWidth()
                .semantics { contentDescription = label },
            textStyle = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, color = t.fg),
            cursorBrush = SolidColor(t.accent),
            visualTransformation = if (obscure) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(
                keyboardType = if (obscure) KeyboardType.Password else KeyboardType.Text,
                imeAction = ImeAction.Done,
            ),
        )
    }
}

/** 眼睛按钮：44×44、pressed 态换底；读名随状态在「显示令牌 / 隐藏令牌」之间切换。 */
@Composable
private fun RevealButton(revealed: Boolean, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val shape = RoundedCornerShape(t.radiusMd)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .width(t.tapMin)
            .height(t.tapMin)
            .clip(shape)
            .background(if (revealed) t.fgFade(8) else t.surface)
            .border(1.dp, t.border, shape)
            .pressFeedback(interaction)
            .semantics { role = Role.Button }
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                haptic(HapticTone.LIGHT)
                onToggle()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (revealed) Icons.Outlined.VisibilityOff else Icons.Outlined.Visibility,
            contentDescription = if (revealed) "隐藏令牌" else "显示令牌",
            tint = if (revealed) t.fg else t.fg2,
            modifier = Modifier.width(19.dp),
        )
    }
}

/** 「从剪贴板粘贴」：整宽 44dp、白底 1dp 描边、12sp 600；按下缩到 0.99。 */
@Composable
private fun PasteButton(
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusMd)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(t.tapMin)
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.border, shape)
            .pressFeedback(interaction)
            .semantics { this.contentDescription = contentDescription }
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                onClick()
            }
            .padding(horizontal = t.space2),
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.ContentPaste,
            contentDescription = null,
            tint = t.fg,
            modifier = Modifier.width(17.dp),
        )
        Spacer(Modifier.width(t.space2))
        Text(
            "从剪贴板粘贴",
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 12.sp),
            color = t.fg,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** 面板主按钮（保存）/ 危险文字按钮（断开连接）：44dp 高、整宽。 */
@Composable
private fun PanelButton(
    label: String,
    enabled: Boolean,
    primary: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusMd)
    val bg = if (primary) (if (enabled) t.accent else t.fgFade(10)) else androidx.compose.ui.graphics.Color.Transparent
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(t.tapMin)
            .clip(shape)
            .background(bg)
            .then(
                if (primary) {
                    Modifier
                } else {
                    Modifier.border(1.dp, t.danger.copy(alpha = 0.34f), shape)
                },
            )
            .pressFeedback(interaction, enabled = enabled)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = if (primary) t.textSm else t.textXs)
                .copy(fontWeight = FontWeight.SemiBold),
            color = if (primary) t.accentOn else t.dangerText,
        )
    }
}

/** 读剪贴板；主线程读、空内容返回空串（不弹错误）。 */
internal fun readClipboard(context: Context): String {
    val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return ""
    val clip = manager.primaryClip ?: return ""
    if (clip.itemCount <= 0) return ""
    return runCatching { clip.getItemAt(0).coerceToText(context)?.toString().orEmpty() }
        .getOrDefault("")
        .trim()
}

internal const val PASTE_HINT_IDLE: String = "复制到剪贴板后点这里，不用逐字输入"
internal const val PASTE_HINT_OK: String = "已从剪贴板读到内容 · 可直接保存"
internal const val PASTE_HINT_EMPTY: String = "剪贴板为空时会提示「没读到内容」"