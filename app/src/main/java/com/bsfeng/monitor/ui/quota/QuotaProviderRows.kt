package com.bsfeng.monitor.ui.quota

// 从 ui/quota/QuotaSettingsProviders.kt 拆出：provider 行 —— ProviderSettingsRow（名称 + 状态 + 凭据填写）、SaveButton、IntervalChips（刷新间隔选项）。负责「一行内完成 provider 配置」。

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.unit.dp
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.testTag
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.theme.dotDiameter
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.graphics.Color
import com.bsfeng.monitor.quota.QuotaSettings
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 未接入 provider 的行该长什么样（锚点 s19:18/19 的「无反应、不展开」档）。
 *
 * 抽成纯函数是为了可单测：这条档位一旦被误触发，用户会对着一个填了也不生效的输入框白忙
 * （真写 Keystore、真发请求），所以判据必须能被断言锁住，而不是散在 Compose 分支里。
 *
 * @param available 该 provider 是否真的能配置凭据（由 provider 注册表决定，见 [ProviderSettingsRow] 的形参说明）；
 * @param unavailableBadge 未接入时的行尾角标（交互口径原文「下次接入」/「手动」）。
 */
internal data class ProviderRowAffordance(
    /** 整行是否可点（false = 不进入按压态、不展开、不发 toast）。 */
    val interactive: Boolean,
    /** 是否显示右箭头（未接入行按口径「无 chevron」）。 */
    val showChevron: Boolean,
    /** 整行透明度（口径：未接入行 0.85 读作不可用）。 */
    val alpha: Float,
    /** 行尾角标：已接入走连接态角标（由调用方渲染），未接入走这里的角标文案。 */
    val unavailableBadge: String?,
    /** 收起态副行文案是否用「无公开接口 · 手动录入」这类说明替代连接状态。 */
    val useUnavailableHint: Boolean,
)

internal fun providerRowAffordance(
    available: Boolean,
    unavailableBadge: String = UNAVAILABLE_BADGE_DEFAULT,
): ProviderRowAffordance = if (available) {
    ProviderRowAffordance(
        interactive = true,
        showChevron = true,
        alpha = 1f,
        unavailableBadge = null,
        useUnavailableHint = false,
    )
} else {
    ProviderRowAffordance(
        interactive = false,
        showChevron = false,
        alpha = UNAVAILABLE_ROW_ALPHA,
        unavailableBadge = unavailableBadge,
        useUnavailableHint = true,
    )
}

/** 已接入 provider 恒可用：设置页所有已注册 provider 走这档（见 [providerRowAffordance]）。 */
internal const val UNAVAILABLE_BADGE_DEFAULT: String = "下次接入"

/** 未接入行的整行透明度（锚点 s19:18「整行 opacity 0.85 读作不可用」）。 */
internal const val UNAVAILABLE_ROW_ALPHA: Float = 0.85f

/** 未接入行的副行说明（锚点 s19:19 逐字：「无公开接口 · 手动录入」）。 */
internal const val UNAVAILABLE_HINT: String = "无公开接口 · 手动录入"

/**
 * 一行 provider：名称 / 连接状态 / 已配置凭据类型；点开在两行内完成填写。
 *
 * 展开态不做二级页面：凭据只有 1～2 条，push 一层反而让「改一条 Key」变成三次导航。
 *
 * 交互口径（锚点 s19-provider-*）：
 * - 整行按下 120ms 缩到 0.96（--dur-press）+ LIGHT；展开 220ms / 收起 150ms（--dur-overlay-in / out）；
 * - 收起后焦点回本行：展开区卸载会把焦点一起带走，走 pendingFocus 等重组收起后再请求；
 * - 保存期间按钮锁一次（连点只存一次），失败保留输入并就地提示（见 [saveRowState]）；
 * - **未接入的行**（[available] = false）：不展开、不填凭据、不发请求，按 [providerRowAffordance]
 *   渲染禁用态 —— 避免用户对着假输入框白填。
 *
 * @param available 该 provider 是否真的能配置凭据。**由 provider 注册表决定，不按 id 写死**：
 *   今天四家（ollama / deepseek / opencode-go / opendesign）都在 ServiceContainer 里注册了真实
 *   拉取实现，所以调用方一律传 true 即可；将来接入空壳 provider 时传 false 就自动变成禁用态。
 *   锚点 s19:18/19 原本要求 opencode-go / opendesign 走禁用档，但那两家已是真实 provider
 *   （各有 fetchItems 实现与单测），且 历史裁决记录 已裁决
 *   「以源码为准，行可展开」—— 所以这里不做按 id 的硬编码禁用。
 */
@Composable
internal fun ProviderSettingsRow(
    info: QuotaProviderInfo,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSave: (balance: String, usage: String) -> Unit,
    intervalMinutes: Int = QuotaSettings.defaultMinutesFor(info.id),
    onIntervalChange: (Int) -> Unit = {},
    /** 保存 + 查询在途（设置页把 ViewModel 的在途锁下传）。 */
    saving: Boolean = false,
    /** 上次保存后的失败原因；非空时在按钮下就地提示，且输入不清空。 */
    saveError: String? = null,
    /** false = 该 provider 未接入（见 KDoc）；默认 true，已注册 provider 全走这档。 */
    available: Boolean = true,
    /** 未接入时的行尾角标文案（锚点 s19 原文）。 */
    unavailableBadge: String = UNAVAILABLE_BADGE_DEFAULT,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val rowShape = RoundedCornerShape(t.radiusMd)
    val rowFocus = remember { FocusRequester() }
    var balance by remember(info.id, expanded) { mutableStateOf("") }
    var usage by remember(info.id, expanded) { mutableStateOf("") }
    val needsUsageRow = info.usageKind != null && info.usageKind != info.balanceKind
    val affordance = providerRowAffordance(available, unavailableBadge)
    // 未接入时永远不展开：即便调用方把 expanded 传成 true（例如状态残留），也不给出可填的假输入框
    val shownExpanded = expanded && affordance.interactive
    // 收起后才把焦点送回本行：此刻展开区还在树里，零帧请求会落到即将卸载的节点上
    // （写法照 Chips.kt:179-185 的 pendingFocus）
    var pendingRowFocus by remember { mutableStateOf(false) }
    LaunchedEffect(pendingRowFocus, shownExpanded) {
        if (pendingRowFocus && !shownExpanded) {
            pendingRowFocus = false
            runCatching { rowFocus.requestFocus() }
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .clip(rowShape)
            // 未接入行不进入按压态、不展开、不发 toast（锚点 s19:18/19）：
            // pressFeedback / clickable 都吃 enabled，禁用态下点击区仍在但无任何反馈
            .pressFeedback(interaction, enabled = affordance.interactive)
            .keyboardFocusRing(rowShape)
            .focusRequester(rowFocus)
            .testTag("row.provider")
            .clickable(
                enabled = affordance.interactive,
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                haptic(HapticTone.LIGHT)
                if (shownExpanded) pendingRowFocus = true
                onToggle()
            }
            .padding(t.space3)
            .alpha(affordance.alpha),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(info.displayName, style = MaterialTheme.typography.titleSmall, color = t.fg)
                Spacer(Modifier.height(t.space1))
                Text(
                    // 未接入行副行说明为什么填不了，而不是继续显示「未配置」骗人
                    if (affordance.useUnavailableHint) UNAVAILABLE_HINT else statusText(info),
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
            Spacer(Modifier.width(t.space2))
            if (!affordance.interactive) {
                Badge(affordance.unavailableBadge.orEmpty(), tone = BadgeTone.Neutral)
            } else if (info.connected) {
                Badge("已连接", tone = BadgeTone.Success)
            } else {
                Badge("未配置", tone = BadgeTone.Warn)
            }
            if (affordance.showChevron) {
                Icon(
                    Icons.AutoMirrored.Outlined.KeyboardArrowRight,
                    contentDescription = if (shownExpanded) "收起" else "展开",
                    tint = t.muted,
                    modifier = Modifier.padding(start = t.space1),
                )
            }
        }
        // 展开 220ms / 收起 150ms（--dur-overlay-in / out，锚点 s19 头部时长口径）。
        // 不用 foundation 的 expandEnter()：那个角色取 --dur-page（340ms），与本页口径不一致。
        AnimatedVisibility(
            visible = shownExpanded,
            enter = expandVertically(tween(t.durOverlayIn, easing = t.easeOut)) +
                fadeIn(tween(t.durOverlayIn, easing = t.easeOut)),
            exit = shrinkVertically(tween(t.durOverlayOut, easing = t.easeIn)) +
                fadeOut(tween(t.durOverlayOut, easing = t.easeIn)),
        ) {
            Column {
                Spacer(Modifier.height(t.space3))
                Text(
                    "余额 · ${kindLabel(info.balanceKind)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = t.fg2,
                )
                Spacer(Modifier.height(t.space1))
                QuotaTextField(
                    value = balance,
                    onValueChange = { balance = it },
                    placeholder = info.credentialHint.ifBlank { "API Key" },
                )
                if (info.credentialHelp.isNotBlank()) {
                    Spacer(Modifier.height(t.space1))
                    Text(info.credentialHelp, style = MaterialTheme.typography.bodySmall, color = t.muted)
                }
                if (needsUsageRow) {
                    Spacer(Modifier.height(t.space3))
                    Text(
                        "用量 · ${kindLabel(info.usageKind)}",
                        style = MaterialTheme.typography.labelMedium,
                        color = t.fg2,
                    )
                    Spacer(Modifier.height(t.space1))
                    QuotaTextField(
                        value = usage,
                        onValueChange = { usage = it },
                        placeholder = info.usageCredentialHint ?: "平台登录令牌",
                    )
                    Spacer(Modifier.height(t.space1))
                    Text(
                        info.usageCredentialHelp ?: "用量接口要平台登录令牌，与余额 Key 不通用；只填一个也能用。",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                    )
                }
                Spacer(Modifier.height(t.space3))
                SaveButton(
                    enabled = !saving && (balance.isNotBlank() || (needsUsageRow && usage.isNotBlank())),
                    onClick = {
                        haptic(HapticTone.SUCCESS)
                        onSave(balance.trim(), usage.trim())
                    },
                )
                if (saveError != null) {
                    Spacer(Modifier.height(t.space2))
                    // 就地提示：保存 / 查询失败时输入保留，改一改再点保存即可（锚点 s19 边界）
                    Text(
                        "保存后查询失败：" + saveError + " · 输入已保留，可修改后重试",
                        style = MaterialTheme.typography.bodySmall,
                        color = t.dangerText,
                    )
                }
                Spacer(Modifier.height(t.space4))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "刷新间隔",
                        style = MaterialTheme.typography.labelMedium,
                        color = t.fg2,
                        modifier = Modifier.weight(1f),
                    )
                    IntervalChips(selected = intervalMinutes, onSelect = onIntervalChange)
                }
                Spacer(Modifier.height(t.space1))
                Text(
                    "前台停留在额度页时按这个间隔刷新；Ollama 限流 10 次/分钟（跨设备共享），别调太小。",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
        }
    }
}

/** 保存按钮（主色 + 卡片内宽度；按下 0.96 + SUCCESS 触觉，在途 / 空输入时禁用）。 */
@Composable
private fun SaveButton(enabled: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusMd)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(t.tapMin)
            .clip(shape)
            .background(if (enabled) t.accent else t.fgFade(10))
            .pressFeedback(interaction, enabled = enabled)
            .testTag("button.save")
            .clickable(
                enabled = enabled,
                interactionSource = interaction,
                indication = LocalIndication.current,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            "保存并查询",
            style = MaterialTheme.typography.labelLarge,
            color = if (enabled) t.accentOn else t.muted,
        )
    }
}

/**
 * 刷新间隔小 chip（1 / 3 / 5 / 10 / 15 分钟）。
 *
 * 三处口径（锚点 s19-provider-* · 刷新间隔）：选中底 120ms 过渡（--dur-state）、点击区 ≥44dp、
 * 点已选中的 chip 不重复落盘也不发请求。
 */
@Composable
private fun IntervalChips(selected: Int, onSelect: (Int) -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    Row(horizontalArrangement = Arrangement.spacedBy(t.space1)) {
        QuotaSettings.MINUTE_OPTIONS.forEach { minutes ->
            val on = minutes == selected
            val interaction = remember { MutableInteractionSource() }
            val bg by animateColorAsState(
                targetValue = if (on) t.fgFade(10) else Color.Transparent,
                animationSpec = tween(t.durState),
                label = "quotaIntervalChipBg",
            )
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(t.radiusSm))
                    .background(bg)
                    .pressFeedback(interaction)
                    .testTag("chip.interval")
                    .clickable(
                        interactionSource = interaction,
                        indication = LocalIndication.current,
                    ) {
                        // 已是该间隔时不落盘、不重排闹钟、不重复震动
                        if (!intervalSelectionChanged(minutes, selected)) return@clickable
                        haptic(HapticTone.LIGHT)
                        onSelect(minutes)
                    }
                    // 点击区 44dp：纵向内边距补到下限
                    .heightIn(min = t.tapMin)
                    .padding(horizontal = t.space2),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "${minutes} 分钟",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (on) t.fg else t.muted,
                )
            }
        }
    }
}

/**
 * 点刷新间隔 chip 是否算一次真实改动。
 *
 * 点已选中的 chip 不重复落盘（锚点 s19-provider-* · 刷新间隔边界）：不算改动 → 不写盘、不震、
 * 不重排闹钟。
 */
internal fun intervalSelectionChanged(minutes: Int, selected: Int): Boolean = minutes != selected

/** 保存结束后这一行的处置。 */
internal enum class QuotaSaveRowState { WAIT, KEEP_OPEN, COLLAPSE }

/**
 * 保存结束后收不收起这一行（锚点 s19 · 「保存并查询」移除后 / 边界）。
 *
 * - 在途：等（连点只存一次，不重复收起）；
 * - 失败：留展开并保留输入（就地提示在按钮下方），不能把错误吞掉还收行；
 * - 成功：收起（收起后的焦点回本行由行本体负责）。
 */
internal fun saveRowState(
    savingProviderId: String?,
    providerId: String,
    saveError: String?,
): QuotaSaveRowState = when {
    savingProviderId == providerId -> QuotaSaveRowState.WAIT
    saveError != null -> QuotaSaveRowState.KEEP_OPEN
    else -> QuotaSaveRowState.COLLAPSE
}
// ───────────────────── 单家态 · provider chip 行 ─────────────────────
// 从 QuotaCards.kt 迁来并补锚点：共享件只给容器 modifier，chip 级 testTag 落不下去，
// 而 s18 交互口径要求 4 个 chip 各有锚点（s18-provider-ollama / -deepseek / -opencode / -opendesign）。

/**
 * provider 横向 chip 行（原型 .s18-providers）：可横滑、已配置带绿点、选中态互斥。
 *
 * 横滑只改变可视范围、不切换 provider：LazyRow 自带位移阈值，位移小于 8px 判为 click 不误触。
 */
@Composable
internal fun QuotaProviderChips(
    providers: List<QuotaProviderInfo>,
    selectedId: String,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    LazyRow(
        modifier = modifier.fillMaxWidth().testTag("s18-providers"),
        contentPadding = PaddingValues(horizontal = t.space4),
        horizontalArrangement = Arrangement.spacedBy(t.space2),
    ) {
        items(providers, key = { it.id }) { info ->
            QuotaProviderChip(
                info = info,
                selected = info.id == selectedId,
                onClick = { onSelect(info.id) },
            )
        }
    }
}

/** chip 锚点按 provider id 逐字映射到口径（不改号、不新增）；字面量落在这里才能被锚点对账扫到。 */
private fun chipTagOf(providerId: String): Modifier = when (providerId) {
    "ollama" -> Modifier.testTag("s18-provider-ollama")
    "deepseek" -> Modifier.testTag("s18-provider-deepseek")
    "opencode-go" -> Modifier.testTag("s18-provider-opencode")
    "opendesign" -> Modifier.testTag("s18-provider-opendesign")
    else -> Modifier
}

@Composable
private fun QuotaProviderChip(
    info: QuotaProviderInfo,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusPill)
    val bg by animateColorAsState(
        targetValue = if (selected) t.fgFade(7) else Color.Transparent,
        animationSpec = tween(t.durState),
        label = "providerChipBg",
    )
    val stroke by animateColorAsState(
        targetValue = if (selected) t.fgFade(24) else t.border,
        animationSpec = tween(t.durState),
        label = "providerChipStroke",
    )
    Row(
        modifier = Modifier
            .height(t.tapMin)
            .clip(shape)
            .background(bg)
            .border(1.dp, stroke, shape)
            .pressFeedback(interaction)
            .keyboardFocusRing(shape)
            .then(chipTagOf(info.id))
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
            ) {
                // 已是当前 provider 时不重复震动、不重复 toast（口径：同一操作不重复震动）
                if (selected) return@clickable
                // provider 切换等价于 Tab 切换：用中触，与列表点击的轻触区分
                haptic(HapticTone.MEDIUM)
                onClick()
            }
            .padding(horizontal = t.space4),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.space2),
    ) {
        // 绿点只表示「已配置凭据且能拉数据」，未配置的家恒为灰点
        Box(
            Modifier
                .size(t.dotDiameter)
                .clip(CircleShape)
                .background(if (info.connected) t.success else t.fgFade(30)),
        )
        Text(
            info.displayName,
            style = MaterialTheme.typography.labelLarge,
            color = if (selected) t.fg else t.fg2,
            maxLines = 1,
            modifier = Modifier.testTag("chip.provider"),
        )
    }
}
