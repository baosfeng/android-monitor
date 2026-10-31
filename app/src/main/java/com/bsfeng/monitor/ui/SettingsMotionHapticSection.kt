package com.bsfeng.monitor.ui

// 二级页「外观与动效」：外观与主题（入口行）/ 动效强度 + 卡顿自动降档 / 触觉反馈与强度。
//
// 为什么合成一页：这三项都在回答「界面长什么样、动起来什么手感」，
// 而且改动都要立刻可见 —— 用户切换后会退回这一页反复比，分两个二级页要来回跳。
//
// 状态值一律放行尾（契约 s09 页层级规则 5）：当前主题名、当前档位、触觉开没开都在行尾，
// 副标题只写「这一项是什么」，不写「当前是什么」。

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.ui.restoreFocusAfterFrames
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.Chip
import com.bsfeng.monitor.ui.foundation.HapticConfig
import com.bsfeng.monitor.ui.foundation.HapticStrength
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.MotionTier
import com.bsfeng.monitor.ui.foundation.performHaptic
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.ThemeRepository

/**
 * 「外观与动效」页正文：一张卡三行 —— 外观与主题 / 动效强度 / 触觉反馈。
 */
@Composable
internal fun SettingsAppearanceSection(rt: SettingsScreenRuntime) {
    val t = rt.t
    // 锚点 s09-appearance 原本落在「通用」页的外观分组容器上；本次外观行搬进本页，
    // 锚点跟着落在同一张卡上（契约锚点不随信息架构变更而消失）。
    AppCard(
        Modifier
            .padding(horizontal = t.space4)
            .testTag("s09-appearance"),
        padding = 0.dp,
    ) {
        SettingsThemeRow(rt)
        TokenDivider(first = false)
        // 动效强度：状态值（当前档位）放行尾，档位 chip 组在下方
        TokenRow(
            title = "动效强度",
            subtitle = motionTierSubtitle(rt.motionTier),
            icon = Icons.Outlined.Animation,
            trailing = { TailNum(motionTierLabel(rt.motionTier)) },
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = t.space4, vertical = t.space2),
            horizontalArrangement = Arrangement.spacedBy(t.space2),
        ) {
            MotionTier.entries.forEach { tier ->
                Chip(
                    label = motionTierLabel(tier),
                    selected = rt.motionTier == tier,
                    onClick = {
                        if (rt.motionConfig.setTier(tier)) {
                            rt.view.performHaptic(HapticTone.LIGHT)
                        }
                    },
                )
            }
        }
        // 卡顿自动降档：只在真的降过档时出现，并提供一键回默认
        if (rt.autoDowngraded) {
            TokenRow(
                title = "已自动降档",
                subtitle = "检测到卡顿，已自动切到常规",
                subtitleColor = t.warnText,
                icon = Icons.Outlined.Speed,
                trailing = {
                    AppButton(
                        text = "恢复默认",
                        onClick = {
                            rt.motionConfig.resetToDefault()
                            rt.view.performHaptic(HapticTone.LIGHT)
                        },
                        variant = AppButtonVariant.Ghost,
                    )
                },
            )
        }
        TokenDivider(first = false)
        TokenRow(
            title = "触觉反馈与强度",
            subtitle = if (rt.hapticEnabled) {
                hapticStrengthSubtitle(rt.hapticStrength) + " · 遵循系统触觉总开关"
            } else {
                "已关闭：所有操作都不震"
            },
            subtitleColor = if (rt.hapticEnabled) t.muted else t.meta,
            icon = Icons.Outlined.Vibration,
            trailing = {
                TailNum(if (rt.hapticEnabled) hapticStrengthLabel(rt.hapticStrength) else "关")
                AppSwitch(
                    rt.hapticEnabled,
                    { enabled ->
                        HapticConfig.setEnabled(enabled)
                        rt.view.performHaptic(HapticTone.LIGHT)
                    },
                    contentDescription = "触觉反馈与强度",
                )
            },
        )
        // 关闭触觉时整组降透明度：与「已关闭」文案一致，不做成假开关
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = if (rt.hapticEnabled) 1f else 0.55f }
                .padding(horizontal = t.space4, vertical = t.space2),
            horizontalArrangement = Arrangement.spacedBy(t.space2),
        ) {
            HapticStrength.entries.forEach { strength ->
                Chip(
                    label = hapticStrengthLabel(strength),
                    selected = rt.hapticStrength == strength,
                    onClick = {
                        if (HapticConfig.setStrength(strength)) {
                            rt.view.performHaptic(HapticTone.LIGHT)
                        }
                    },
                )
            }
        }
    }
}

/**
 * 外观与主题行：跳 s13 外观屏，尾部显示当前主题名。
 *
 * 契约 s13-head · 退出后：焦点回本行、滚动位置保持。外观屏是覆盖层（设置页收不到关闭回调），
 * 由 [AppearanceFocusReturn] 的代次信号衔接；代次在「本行组合期间」新增才回焦。
 */
@Composable
private fun SettingsThemeRow(rt: SettingsScreenRuntime) {
    val t = rt.t
    val themeInteraction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    val rowFocus = remember { FocusRequester() }
    val returnGeneration = AppearanceFocusReturn.generation
    var seenGeneration by remember { mutableIntStateOf(returnGeneration) }
    LaunchedEffect(returnGeneration) {
        if (!appearanceReturnFocusPending(returnGeneration, seenGeneration)) return@LaunchedEffect
        val generation = returnGeneration
        // 等退场动画把覆盖层卸下再请求；请求在复位之前（见 restoreFocusAfterFrames）
        restoreFocusAfterFrames(
            frames = 2,
            awaitFrame = { withFrameNanos { } },
            requestFocus = { runCatching { rowFocus.requestFocus() }.isSuccess },
            reset = { seenGeneration = generation },
        )
    }
    TokenRow(
        title = "外观与主题",
        modifier = Modifier
            .testTag("s09-goto-theme")
            .focusRequester(rowFocus)
            .pressFeedback(themeInteraction)
            .clickable(
                interactionSource = themeInteraction,
                indication = LocalIndication.current,
            ) {
                haptic(HapticTone.LIGHT)
                val open = rt.onNavigateToAppearance
                if (open != null) open() else rt.localAppearanceState.value = true
            },
        subtitle = ThemeRepository.themeIds.size.toString() + " 套浅色主题 · 切换即时生效",
        icon = Icons.Outlined.Palette,
        trailing = { TailNum(ThemeRepository.displayName(rt.themeId)); TailChevron() },
    )
}
