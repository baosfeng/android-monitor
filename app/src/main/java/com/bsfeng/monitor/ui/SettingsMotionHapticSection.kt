package com.bsfeng.monitor.ui

// 从 ui/SettingsScreen.kt 拆出：设置「动效与触感」分组 —— 动效档位 / 卡顿自动降档 / 触觉反馈开关 / 触觉强度。负责「动画与震动的强弱」。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Animation
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.outlined.Vibration
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppSwitch
import com.bsfeng.monitor.ui.components.Chip
import com.bsfeng.monitor.ui.components.SectionHeader
import com.bsfeng.monitor.ui.foundation.HapticConfig
import com.bsfeng.monitor.ui.foundation.HapticStrength
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.MotionTier
import com.bsfeng.monitor.ui.foundation.performHaptic
import android.view.View
import androidx.compose.runtime.Composable
import com.bsfeng.monitor.ui.foundation.MotionConfig
import com.bsfeng.monitor.ui.theme.DesignTokens

/**
 * 设置「动效与触感」分组 —— 动效档位 / 卡顿自动降档 / 触觉反馈开关 / 触觉强度。负责「动画与震动的强弱」。
 */
@Composable
internal fun SettingsMotionHapticSection(
    t: DesignTokens,
    view: View,
    motionConfig: MotionConfig,
    motionTier: MotionTier,
    autoDowngraded: Boolean,
    hapticEnabled: Boolean,
    hapticStrength: HapticStrength,
) {
// ===== 动效与触感：动效档位 / 卡顿自动降档 / 触觉开关 / 触觉强度 =====
SectionHeader("动效与触感")
Column(Modifier.fillMaxWidth()) {
    GroupLabel("动效")
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        TokenRow(
            title = "动效强度",
            subtitle = motionTierSubtitle(motionTier),
            icon = Icons.Outlined.Animation,
            trailing = {
                TailNum(motionTierLabel(motionTier))
            },
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
                    selected = motionTier == tier,
                    onClick = {
                        if (motionConfig.setTier(tier)) {
                            view.performHaptic(HapticTone.LIGHT)
                        }
                    },
                )
            }
        }
        if (autoDowngraded) {
            TokenDivider(first = false)
            TokenRow(
                title = "已自动降档",
                subtitle = "检测到卡顿，已自动切到常规",
                subtitleColor = t.warnText,
                icon = Icons.Outlined.Speed,
                trailing = {
                    AppButton(
                        text = "恢复默认",
                        onClick = {
                            motionConfig.resetToDefault()
                            view.performHaptic(HapticTone.LIGHT)
                        },
                        variant = AppButtonVariant.Ghost,
                    )
                },
            )
        }
        Spacer(Modifier.height(t.space1))
    }
}

Column(Modifier.fillMaxWidth()) {
    GroupLabel("触感")
    AppCard(Modifier.padding(horizontal = t.space4), padding = 0.dp) {
        TokenRow(
            title = "触觉反馈",
            subtitle = if (hapticEnabled) {
                "操作时轻震一下（遵循系统触觉总开关）"
            } else {
                "已关闭：所有操作都不震"
            },
            icon = Icons.Outlined.Vibration,
            trailing = {
                AppSwitch(hapticEnabled, { enabled ->
                    HapticConfig.setEnabled(enabled)
                    view.performHaptic(HapticTone.LIGHT)
                })
            },
        )
        TokenDivider(first = false)
        TokenRow(
            title = "触觉强度",
            subtitle = if (hapticEnabled) {
                hapticStrengthSubtitle(hapticStrength)
            } else {
                "开启触觉反馈后可用"
            },
            subtitleColor = if (hapticEnabled) t.muted else t.meta,
            icon = Icons.Outlined.Tune,
        )
        // 关闭触觉时整组降透明度：与「未开启」文案一致，不做成假开关
        Row(
            Modifier
                .fillMaxWidth()
                .graphicsLayer { alpha = if (hapticEnabled) 1f else 0.55f }
                .padding(horizontal = t.space4, vertical = t.space2),
            horizontalArrangement = Arrangement.spacedBy(t.space2),
        ) {
            HapticStrength.entries.forEach { strength ->
                Chip(
                    label = hapticStrengthLabel(strength),
                    selected = hapticStrength == strength,
                    onClick = {
                        if (HapticConfig.setStrength(strength)) {
                            view.performHaptic(HapticTone.LIGHT)
                        }
                    },
                )
            }
        }
        Spacer(Modifier.height(t.space1))
    }
}
}
