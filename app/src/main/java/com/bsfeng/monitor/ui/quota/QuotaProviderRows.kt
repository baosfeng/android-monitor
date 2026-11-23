package com.bsfeng.monitor.ui.quota

// provider 账号行（设置页）：ProviderSettingsRow（名称 + 状态 + 凭据填写 + 重命名 / 删除）、
// AccountRowHeader、AuthoredAccountPanel、SaveButton、IntervalChips（刷新间隔选项）。
// 单家视图的 chip 行在 QuotaProviderChips.kt。

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.quota.QuotaSettings
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 未接入 provider 的行该长什么样（锚点 s19:18/19 的「无反应、不展开」档）。
 *
 * 抽成纯函数是为了可单测：这条档位一旦被误触发，用户会对着一个填了也不生效的输入框白忙
 * （真写 Keystore、真发请求），所以判据必须能被断言锁住，而不是散在 Compose 分支里。
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
 * 一行账号：名称 / 连接状态 / 已配置凭据类型；点开在行内完成「改 Key / 改名 / 删账号」。
 *
 * 展开态不做二级页面：凭据只有 1～2 条，push 一层反而让「改一条 Key」变成三次导航。
 *
 * 交互口径（锚点 s19-provider-*）：
 * - 整行按下 120ms 缩到 0.96（--dur-press）+ LIGHT；展开 220ms / 收起 150ms（--dur-overlay-in / out）；
 * - 收起后焦点回本行：展开区卸载会把焦点一起带走，走 pendingFocus 等重组收起后再请求；
 * - 保存期间按钮锁一次（连点只存一次），失败保留输入并就地提示（见 [saveRowState]）；
 * - **已配置**的账号在保存按钮下方多一条「清除凭据」（danger 文字按钮），先二次确认再断开；
 * - 账号管理两条：**重命名**（就地输入，空串 = 回默认名）与**删除此账号**
 *   （只剩一个账号时隐藏，删账号会清它的凭据 + 快照 + 状态）。
 *
 * @param info 账号实例的展示模型（[QuotaProviderInfo.instanceId] 是键）
 * @param available 该 provider 类型是否真的能配置凭据（由注册表决定，不按 id 写死）
 */
@Composable
internal fun ProviderSettingsRow(
    info: QuotaProviderInfo,
    expanded: Boolean,
    onToggle: () -> Unit,
    onSave: (balance: String, usage: String) -> Unit,
    /** 确认断开后的回调：清凭据 + 清快照 + 状态回落（见 [QuotaViewModel.onClearCredentialsFor]）。 */
    onClear: () -> Unit = {},
    /** 改名（空串 = 回默认名）。 */
    onRename: (String) -> Unit = {},
    /** 删这个账号；只剩一个账号时入口不出现，所以调用方不必再判一次。 */
    onDelete: () -> Unit = {},
    intervalMinutes: Int = QuotaSettings.defaultMinutesFor(info.providerTypeId),
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
    var balance by remember(info.instanceId, expanded) { mutableStateOf("") }
    var usage by remember(info.instanceId, expanded) { mutableStateOf("") }
    // 改名输入：展开时用当前 label 打底（空 = 未改名，输入框留空即回落默认名）
    var nameDraft by remember(info.instanceId, expanded) { mutableStateOf(info.label) }
    val needsUsageRow = info.usageKind != null && info.usageKind != info.balanceKind
    val affordance = providerRowAffordance(available, unavailableBadge)
    // 清除入口：已配置才出现，在途（保存或清除）不可点 —— 判据在 QuotaCredentialClear.kt 且有单测
    val clearAction = credentialClearAction(configured = info.connected && affordance.interactive, busy = saving)
    // 破坏性操作先确认：一次误触就删掉本机唯一一份 Key，没有撤销路径
    var confirmClear by remember(info.instanceId) { mutableStateOf(false) }
    var confirmDelete by remember(info.instanceId) { mutableStateOf(false) }
    // 未接入时永远不展开：即便调用方把 expanded 传成 true（例如状态残留），也不给出可填的假输入框
    val shownExpanded = expanded && affordance.interactive
    // 收起后才把焦点送回本行：此刻展开区还在树里，零帧请求会落到即将卸载的节点上
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
            // 未接入行不进入按压态、不展开、不发 toast（锚点 s19:18/19）
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
            // 原型 .row{padding: var(--space-2) 0}：纵向 8（左右缩进由 .group 的 16 提供），
            // 用 12 会让每行高 8dp、四行把下面的预警区整体推下去
            .padding(horizontal = t.space3, vertical = t.space2)
            .alpha(affordance.alpha),
    ) {
        AccountRowHeader(
            info = info,
            affordance = affordance,
            shownExpanded = shownExpanded,
        )
        // 展开 220ms / 收起 150ms（--dur-overlay-in / out，锚点 s19 头部时长口径）。
        AnimatedVisibility(
            visible = shownExpanded,
            enter = expandVertically(tween(t.durOverlayIn, easing = t.easeOut)) +
                fadeIn(tween(t.durOverlayIn, easing = t.easeOut)),
            exit = shrinkVertically(tween(t.durOverlayOut, easing = t.easeIn)) +
                fadeOut(tween(t.durOverlayOut, easing = t.easeIn)),
        ) {
            AuthoredAccountPanel(
                info = info,
                balance = balance,
                usage = usage,
                nameDraft = nameDraft,
                needsUsageRow = needsUsageRow,
                saving = saving,
                saveError = saveError,
                clearAction = clearAction,
                intervalMinutes = intervalMinutes,
                onBalanceChange = { balance = it },
                onUsageChange = { usage = it },
                onNameChange = { nameDraft = it },
                onSave = {
                    haptic(HapticTone.SUCCESS)
                    onSave(balance.trim(), usage.trim())
                },
                onRename = {
                    haptic(HapticTone.SUCCESS)
                    onRename(nameDraft.trim())
                },
                onClearClick = { confirmClear = true },
                onDeleteClick = { confirmDelete = true },
                onIntervalChange = onIntervalChange,
            )
        }
    }

    // 二次确认：写清后果（删什么、删完要做什么），确认键 danger 语义且不比「取消」更显眼
    ClearCredentialsConfirmDialog(
        visible = confirmClear,
        onDismiss = { confirmClear = false },
        onConfirm = {
            confirmClear = false
            onClear()
        },
    )
    DeleteAccountConfirmDialog(
        visible = confirmDelete,
        accountName = info.displayName,
        onDismiss = { confirmDelete = false },
        onConfirm = {
            confirmDelete = false
            haptic(HapticTone.REJECT)
            onDelete()
        },
    )
}

/** 行头：账号名 + 副行状态 + 连接角标 + chevron。 */
@Composable
private fun AccountRowHeader(
    info: QuotaProviderInfo,
    affordance: ProviderRowAffordance,
    shownExpanded: Boolean,
) {
    val t = LocalDesignTokens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(info.displayName, style = MaterialTheme.typography.titleSmall, color = t.fg)
            // 原型 .row-body{gap:2px}：标题与副行之间只有 2（space1=4 会让每行高 2dp）
            Spacer(Modifier.height(2.dp))
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
}

/** 保存按钮（主色 + 卡片内宽度；按下 0.96 + SUCCESS 触觉，在途 / 空输入时禁用）。 */
@Composable
internal fun SaveButton(enabled: Boolean, onClick: () -> Unit) {
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
internal fun IntervalChips(selected: Int, onSelect: (Int) -> Unit) {
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