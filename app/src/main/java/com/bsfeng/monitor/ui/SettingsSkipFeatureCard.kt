package com.bsfeng.monitor.ui

// 从 ui/SettingsFeatureSection.kt 拆出：设置「功能 · 广告跳过」卡 —— 跳过引擎开关、监控时长窗口 chip 组、规则与白名单入口。负责「广告跳过开不开、开多久、规则在哪改」。

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material.icons.outlined.Timer
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.skip.SkipConfig
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.Chip
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.MainViewModel
import com.bsfeng.monitor.ui.theme.DesignTokens

/**
 * 设置「功能 · 广告跳过」卡 —— 跳过引擎开关、监控时长窗口 chip 组、规则与白名单入口。负责「广告跳过开不开、开多久、规则在哪改」。
 */
@Composable
internal fun SettingsSkipFeatureCard(
    t: DesignTokens,
    view: View,
    toast: ToastHostState,
    viewModel: MainViewModel,
    adSkipOn: Boolean,
    watchWindowMs: Long,
    ruleCount: Int,
    subscriptionCount: Int,
    whitelistCount: Int,
    setFeature: (Feature, Boolean) -> Unit,
    onNavigateToSkipRules: () -> Unit,
    onNavigateToWhitelist: () -> Unit,
) {
// 每行各持一份 interaction：共用一个会让两行同时进入按压态
val haptic = rememberHaptic()
val rulesInteraction = remember { MutableInteractionSource() }
val whiteInteraction = remember { MutableInteractionSource() }

// 广告跳过：关的是「跳过引擎」，不是「广告」记录 Tab（记录页照常可看）
AppCard(Modifier.padding(horizontal = t.space4).testTag("s09-skip"), padding = 0.dp) {
TokenRow(
    title = "广告跳过",
    subtitle = if (adSkipOn) {
        "规则 " + ruleCount + " 条 · 白名单 " + whitelistCount + " 个"
    } else {
        "已关闭：切 App 后不扫描页面、不执行跳过规则"
    },
    icon = Icons.Outlined.PlayArrow,
    trailing = { AppSwitch(adSkipOn, { setFeature(Feature.AD_SKIP, it) }) },
)
if (adSkipOn) {
    TokenDivider(first = false)
    TokenRow(
        title = "监控时长窗口",
        subtitle = "切换 App 后 " + (watchWindowMs / 1000) + " 秒内匹配跳过按钮",
        icon = Icons.Outlined.Timer,
    )
    // 监控时长窗口 chip 组：锚点落组容器（契约 s09-window）；Chip 内置 0.96 + LIGHT，
    // 调用点不再补一次触感（否则同一次点击震两下）
    Row(
        Modifier
            .fillMaxWidth()
            .testTag("s09-window")
            .padding(horizontal = t.space4, vertical = t.space2),
        horizontalArrangement = Arrangement.spacedBy(t.space2),
    ) {
        SkipConfig.WINDOW_OPTIONS_MS.forEach { option ->
            Chip(
                label = (option / 1000).toString() + " 秒",
                selected = option == watchWindowMs,
                onClick = {
                    viewModel.setWatchWindowMs(option)
                    toast.show("监控时长窗口：" + (option / 1000) + " 秒")
                },
            )
        }
    }
}
// 关闭态不渲染「监控时长窗口」行与 chip 组（引擎不跑，窗口值无可调语义），
// 但两行入口必须照常出现 —— 契约 s09-skip · 边界：组内两行常驻，关闭时两行仍可进入，只改副标题文案。
// 副标题由两个纯函数产出（关闭态写「已关闭」），便于单测锁定措辞。
TokenDivider(first = false)
TokenRow(
    title = "开屏跳过规则",
    modifier = Modifier
        .testTag("s09-goto-rules")
        .pressFeedback(rulesInteraction)
        .clickable(
            interactionSource = rulesInteraction,
            indication = LocalIndication.current,
        ) {
            haptic(HapticTone.LIGHT)
            onNavigateToSkipRules()
        },
    subtitle = skipRulesRowSubtitle(adSkipOn, subscriptionCount),
    icon = Icons.Outlined.PlayArrow,
    trailing = { TailNum(ruleCount.toString() + " 条"); TailChevron() },
)
TokenDivider(first = false)
TokenRow(
    title = "应用白名单",
    modifier = Modifier
        .testTag("s09-goto-white")
        .pressFeedback(whiteInteraction)
        .clickable(
            interactionSource = whiteInteraction,
            indication = LocalIndication.current,
        ) {
            haptic(HapticTone.LIGHT)
            onNavigateToWhitelist()
        },
    subtitle = whitelistRowSubtitle(adSkipOn),
    icon = Icons.Outlined.Shield,
    trailing = { TailNum(whitelistCount.toString() + " 个"); TailChevron() },
)
Spacer(Modifier.height(t.space1))
}
}

/**
 * s09-goto-rules 行副标题。
 *
 * 关闭态也保留入口：只把副标题改成「已关闭」前缀（契约 s09-skip 边界「组内两行常驻；
 * 跳过引擎关闭时两行仍可进入，只改副标题文案」），不删行、不禁用、不改锚点。
 */
internal fun skipRulesRowSubtitle(adSkipOn: Boolean, subscriptionCount: Int): String =
    if (adSkipOn) {
        "订阅源 " + subscriptionCount + " 个 · 本地合并后按顺序匹配"
    } else {
        "已关闭 · 订阅源 " + subscriptionCount + " 个，仍可进入编辑"
    }

/** s09-goto-white 行副标题；关闭态同 [skipRulesRowSubtitle] 保留入口语义。 */
internal fun whitelistRowSubtitle(adSkipOn: Boolean): String =
    if (adSkipOn) "白名单内应用不参与跳过" else "已关闭 · 仍可维护白名单"
