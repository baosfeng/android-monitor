package com.bsfeng.monitor.ui.quota

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.VpnKey
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
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.FOCUS_RING_WIDTH
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 额度页的状态视图：加载 / 未配置凭据 / 用量凭据缺失 / 失败条。
 *
 * 失败条与数据卡分开：刷新失败时**保留上次成功数据**（数据层已把 stale 与 fetchedAt 标好），
 * 页面只负责在数据上方补一条「数据停在 HH:mm」，而不是把整屏换成错误页。
 *
 * 每个可交互节点都带锚点（`s18-*`）：口径的 82 行按锚点逐条对账，
 * 锚点只在对应状态下渲染 —— 同一时刻只存在一份，不会出现同名节点。
 */

/** 加载态：细行骨架，形状与真实卡片一致（先骨架后卡片，避免高度跳变）。 */
@Composable
fun QuotaLoading(modifier: Modifier = Modifier) {
    // 锚点打在卡片内部：加载态只有一个入口，调用点不必各自记得打标
    AppCard(modifier.testTag("s18-loading")) {
        StateSkeleton(rows = 5)
    }
}

/** Ollama 的 Key 管理页（配置卡里那条外链的落点）。 */
private const val OLLAMA_KEY_URL = "https://ollama.com/settings/keys"

/**
 * 未配置凭据（empty 态）：输入框 + 保存并查询 + 外链 + 帮助文案。
 *
 * Ollama 只有一个凭据；DeepSeek 的余额（`sk-` Key）与用量（平台登录令牌）不通用，
 * 所以这里按 [QuotaProviderInfo] 的凭据类型决定显示一行还是两行。
 */
@Composable
fun QuotaConfigCard(
    info: QuotaProviderInfo,
    balance: String,
    usage: String,
    onBalanceChange: (String) -> Unit,
    onUsageChange: (String) -> Unit,
    onSave: (balance: String, usage: String) -> Unit,
    modifier: Modifier = Modifier,
    /** 就地提示（上次保存的 Key 被服务端拒绝等）；为空不占位。 */
    notice: String? = null,
    /** 有 [notice] 时给出的重试入口；为空则不渲染。 */
    onRetry: (() -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    val needsUsageRow = info.usageKind != null && info.usageKind != info.balanceKind
    val canSubmit = balance.isNotBlank() || (needsUsageRow && usage.isNotBlank())
    // 卡片空白处点击不保存、不发请求 → 卡容器本身不挂 clickable
    AppCard(modifier.testTag("s18-config")) {
        Text("配置凭据后查看额度", style = MaterialTheme.typography.titleMedium, color = t.fg)
        Spacer(Modifier.height(t.space2))
        Text(
            "余额与用量来自 provider 官方接口；未配置前不会发起任何请求。",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
        if (notice != null) {
            Spacer(Modifier.height(t.space2))
            // 就地提示：保存失败时错误只说一句，不再单独切到错误态（否则与配置卡两个入口打架）
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "上次查询失败：" + notice,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.dangerText,
                    modifier = Modifier.weight(1f),
                )
                if (onRetry != null) {
                    Spacer(Modifier.width(t.space1))
                    QuotaInlineAction("重试", t.dangerText, onRetry)
                }
            }
        }
        Spacer(Modifier.height(t.space3))
        QuotaTextField(
            value = balance,
            onValueChange = onBalanceChange,
            placeholder = info.credentialHint.ifBlank { "sk-****" },
            label = info.displayName + " API Key",
            modifier = Modifier.testTag("s18-key"),
        )
        if (info.credentialHelp.isNotBlank()) {
            Spacer(Modifier.height(t.space1))
            Text(info.credentialHelp, style = MaterialTheme.typography.bodySmall, color = t.muted)
        }
        if (needsUsageRow) {
            Spacer(Modifier.height(t.space3))
            QuotaTextField(
                value = usage,
                onValueChange = onUsageChange,
                placeholder = info.usageCredentialHint ?: "平台登录令牌",
                label = info.displayName + " 用量凭据",
            )
            Spacer(Modifier.height(t.space1))
            Text(
                info.usageCredentialHelp ?: "用量接口要平台登录令牌，与上面的 Key 不通用；只填一个也能用。",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
        Spacer(Modifier.height(t.space3))
        QuotaSaveButton(
            enabled = canSubmit,
            modifier = Modifier.testTag("s18-save"),
            onClick = { onSave(balance.trim(), if (needsUsageRow) usage.trim() else "") },
        )
        // 只有 ollama 有公开的 Key 管理页；别家没有对应落地页，不摆死链
        if (info.id == OLLAMA_ID) {
            Spacer(Modifier.height(t.space1))
            QuotaKeyLink()
        }
        Spacer(Modifier.height(t.space2))
        Text(
            "Key 只保存在本机，使用 Android Keystore 加密；不写日志、不上传。",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
    }
}

/** 「保存并查询」：整行主色，按下 0.96 + SUCCESS 触感；空输入时禁用（fg 10% 底 + muted 文案）。 */
@Composable
private fun QuotaSaveButton(
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(t.tapMin)
            .clip(RoundedCornerShape(t.radiusMd))
            .background(if (enabled) t.accent else t.fgFade(10))
            .pressFeedback(interaction, enabled = enabled)
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                haptic(HapticTone.SUCCESS)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "保存并查询",
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) t.accentOn else t.muted,
            modifier = Modifier.testTag("button.save"),
        )
    }
}

/** 「在 ollama.com 获取 API Key ↗」：常驻下划线（不靠 hover 才可见）+ 压底变深一档。 */
@Composable
private fun QuotaKeyLink() {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val context = LocalContext.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val bg by animateColorAsState(
        targetValue = if (pressed) t.fgFade(5) else Color.Transparent,
        animationSpec = tween(t.durPress),
        label = "quotaKeyLinkBg",
    )
    Text(
        "在 ollama.com 获取 API Key ↗",
        style = MaterialTheme.typography.bodySmall,
        color = t.accent,
        textDecoration = TextDecoration.Underline,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("s18-key-link")
            .clip(RoundedCornerShape(t.radiusSm))
            .background(bg)
            .pressFeedback(interaction)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                haptic(HapticTone.LIGHT)
                // 无浏览器时只是留在原页（ActivityNotFound 吞掉，不弹错误）
                runCatching {
                    context.startActivity(Intent(Intent.ACTION_VIEW, OLLAMA_KEY_URL.toUri()))
                }.onFailure { if (it !is ActivityNotFoundException) throw it }
            }
            // 点击区高 ≥44dp：文字本身只有一行，靠纵向内边距撑到点击区下限
            .padding(vertical = t.space2),
    )
}

/**
 * 失败条：查询失败但仍有上次数据时叠在数据卡上方。
 *
 * [staleAtEpochSecond] 来自数据层的 fetchedAt（失败时不会前移），
 * 显示成「数据停在 HH:mm」让用户知道看到的是旧数，而不是当前值。
 * 读屏：一次性播报标题 + 副行（liveRegion），不抢走当前焦点。
 */
@Composable
fun QuotaErrorBar(
    message: String,
    staleAtEpochSecond: Long,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    retrying: Boolean = false,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    val haptic = rememberHaptic()
    val detail = if (staleAtEpochSecond > 0L) {
        "数据停在 " + QuotaFormat.clockOf(staleAtEpochSecond) + " · 刷新失败"
    } else {
        "刷新失败"
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.danger.copy(alpha = t.danger.alpha * 0.10f))
            .border(1.dp, t.danger.copy(alpha = t.danger.alpha * 0.34f), shape)
            .padding(t.space3)
            .semantics(mergeDescendants = true) {
                contentDescription = message + "，" + detail
                liveRegion = LiveRegionMode.Polite
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Outlined.VpnKey,
            contentDescription = null,
            tint = t.dangerText,
            modifier = Modifier.padding(end = t.space3),
        )
        Column(Modifier.weight(1f)) {
            Text(message, style = MaterialTheme.typography.bodySmall, color = t.dangerText)
            Spacer(Modifier.height(t.space1))
            Text(detail, style = MaterialTheme.typography.bodySmall, color = t.fg2)
        }
        // 请求中置灰并吞掉点击：连点只发一次
        Text(
            "重试",
            style = MaterialTheme.typography.labelMedium,
            color = if (retrying) t.muted else t.dangerText,
            modifier = Modifier
                .clip(RoundedCornerShape(t.radiusSm))
                .background(t.fgFade(6))
                .testTag("button.retry")
                .clickable(enabled = !retrying) {
                    haptic(HapticTone.SUCCESS)
                    onRetry()
                }
                .padding(horizontal = t.space2, vertical = t.space1),
        )
    }
}

/** 余额有数据、但用量凭据没配置时的提示卡。 */
@Composable
fun QuotaUsageUnavailableCard(
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    AppCard(modifier) {
        Text("用量", style = MaterialTheme.typography.titleMedium, color = t.fg)
        Spacer(Modifier.height(t.space2))
        Text(
            "余额已连通；再填一条用量凭据就能看请求次数、Token 与按模型明细。",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
        Spacer(Modifier.height(t.space3))
        Text(
            "去额度设置填写",
            style = MaterialTheme.typography.labelMedium,
            color = t.accent,
            modifier = Modifier
                .clip(RoundedCornerShape(t.radiusSm))
                .testTag("card.cta")
                .clickable(onClick = onOpenSettings)
                .padding(horizontal = t.space2, vertical = t.space1),
        )
    }
}

/** 用量区该渲染哪一块：账本里「有用量数据」才出用量卡，其余落点各有各的说法。 */
internal enum class QuotaUsageSlot { DATA, LOADING, FAILED, MISSING_CREDENTIAL, NONE }

/**
 * 用量区的落点判定。
 *
 * 只看「supportsUsage && usage == null」就引导「再填一条用量凭据」是假的：凭据早就配好、
 * 只是这次请求失败时，那句引导会把人送去重填一个本来没问题的令牌。
 * 已配置凭据但没数据时，先按「查询中 / 查询失败」处理，绝不再引导重填。
 */
internal fun quotaUsageSlot(
    hasUsage: Boolean,
    supportsUsage: Boolean,
    usageConfigured: Boolean,
    hasError: Boolean,
): QuotaUsageSlot = when {
    hasUsage -> QuotaUsageSlot.DATA
    !supportsUsage -> QuotaUsageSlot.NONE
    !usageConfigured -> QuotaUsageSlot.MISSING_CREDENTIAL
    hasError -> QuotaUsageSlot.FAILED
    else -> QuotaUsageSlot.LOADING
}

/** 用量查询中：紧凑骨架（余额卡已有数据，这里只缺用量，不整屏切骨架）。 */
@Composable
fun QuotaUsageLoadingCard(modifier: Modifier = Modifier) {
    AppCard(modifier) {
        StateSkeleton(rows = 2)
    }
}

/**
 * 用量查询失败：凭据已配置、请求失败时的错误态。
 *
 * 与「未配置凭据」的提示卡分开：这里主操作是重试（错误只是一次性的），换令牌是次级选择。
 */
@Composable
fun QuotaUsageFailedCard(
    message: String,
    onRetry: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    AppCard(modifier) {
        Text("用量查询失败", style = MaterialTheme.typography.titleMedium, color = t.dangerText)
        Spacer(Modifier.height(t.space2))
        Text(
            "用量凭据已配置，但这次没查到数据：" + message + "。额度数据不受影响。",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
        )
        Spacer(Modifier.height(t.space3))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(t.space2),
        ) {
            QuotaInlineAction("重试", t.dangerText, onRetry)
            QuotaInlineAction("去额度设置", t.accent, onOpenSettings)
        }
    }
}

/**
 * 卡内 / 错态里的次级文字动作：44dp 点击区 + 按压反馈 + LIGHT 触感。
 *
 * 账本没给这类新增动作锚点，按「不新增 testTag」的口径处理（锚点集合要与锚点逐字对账）。
 */
@Composable
internal fun QuotaInlineAction(
    label: String,
    tone: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(t.radiusSm))
            .pressFeedback(interaction)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .heightIn(min = t.tapMin)
            .padding(horizontal = t.space2),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            color = tone,
            modifier = Modifier.testTag("button.inline"),
        )
    }
}

/**
 * 单行凭据输入（密码遮罩；明文只在内存里出现）。
 *
 * 光标取主色、框底与描边不变；**键盘焦点**进来时换成 2dp 焦点环（触摸聚焦不画环，
 * 与全站 keyboardFocusRing 口径一致）。占位符 s18 口径固定 `sk-****`。
 */
@Composable
fun QuotaTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    /** 金额阈值等非敏感输入传 false（明文 + 数字键盘）；凭据保持遮罩。 */
    obscure: Boolean = true,
    /** 读屏标签（如「Ollama API Key」）。 */
    label: String? = null,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    var focused by remember { mutableStateOf(false) }
    val keyboardMode = LocalInputModeManager.current.inputMode == InputMode.Keyboard
    val ring = focused && keyboardMode
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(t.tapMin)
            .clip(shape)
            .background(t.surfaceWarm)
            .then(
                if (ring) {
                    Modifier.border(FOCUS_RING_WIDTH, t.accent, shape)
                } else {
                    Modifier.border(1.dp, t.border, shape)
                }
            )
            .padding(horizontal = t.space3),
        contentAlignment = Alignment.CenterStart,
    ) {
        if (value.isEmpty()) {
            Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = t.muted)
        }
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = true,
            // 必须撑满整行：BasicTextField 的默认宽度随内容走，空值时点击区只剩光标那一小点，
            // 表现就是「输入框点不进去」。
            modifier = Modifier
                .fillMaxWidth()
                .onFocusChanged { focused = it.isFocused }
                .then(
                    if (label == null) {
                        Modifier
                    } else {
                        Modifier.semantics { contentDescription = label }
                    }
                ),
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = t.fg),
            cursorBrush = SolidColor(t.accent),
            // 凭据一律遮罩：两类令牌都可能被旁人看到，没有「短的就明文」的余地
            visualTransformation = if (obscure) {
                PasswordVisualTransformation()
            } else {
                VisualTransformation.None
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = if (obscure) KeyboardType.Password else KeyboardType.Decimal,
                imeAction = ImeAction.Done,
            ),
        )
    }
}
