package com.bsfeng.monitor

// 从 MainActivity.kt 拆出：整屏覆盖层组 —— 规则管理 / 白名单 / 组件展厅 / 额度设置 / 外观 / 每日行程 + 开屏动效。
// 负责「盖在 Scaffold 之上、铺到系统栏之下」的二级页；显示状态仍由 MainActivity 持有。

import androidx.compose.runtime.Composable
import com.bsfeng.monitor.ui.AppearanceScreen
import com.bsfeng.monitor.ui.SkipRuleScreen
import com.bsfeng.monitor.ui.SkipRuleViewModel
import com.bsfeng.monitor.ui.WhitelistScreen
import com.bsfeng.monitor.ui.WhitelistViewModel
import com.bsfeng.monitor.ui.components.BrandSplash
import com.bsfeng.monitor.ui.components.ComponentGalleryScreen
import com.bsfeng.monitor.ui.foundation.AnimatedScreenOverlay
import com.bsfeng.monitor.ui.quota.QuotaSettingsScreen
import com.bsfeng.monitor.ui.trip.TripScreen

/**
 * 整屏覆盖层：与 Scaffold 同级（不吃内容区 padding），遮罩与底衬铺到系统栏之下。
 *
 * 转场（右侧滑入 + 淡入）与返回键统一由 [AnimatedScreenOverlay] 提供，取 token 并响应减弱动效。
 * 每个覆盖层跟着功能开关走：跳过引擎关闭时 VM 为 null，这里不渲染。
 */
@Composable
internal fun MainOverlays(
    showSkipRuleScreen: Boolean,
    showWhitelistScreen: Boolean,
    showGallery: Boolean,
    showQuotaSettings: Boolean,
    showAppearance: Boolean,
    showTripScreen: Boolean,
    showSplash: Boolean,
    skipRuleViewModel: SkipRuleViewModel?,
    whitelistViewModel: WhitelistViewModel?,
    onCloseSkipRules: () -> Unit,
    onCloseWhitelist: () -> Unit,
    onCloseGallery: () -> Unit,
    onCloseQuotaSettings: () -> Unit,
    onCloseAppearance: () -> Unit,
    onCloseTrip: () -> Unit,
    /** s16 空态 CTA / 定位提示行：关掉行程覆盖层并跳到设置页定位项。null 时不渲染那两个锚点。 */
    onOpenLocationSettings: (() -> Unit)? = null,
    onSplashFinished: () -> Unit,
) {
    // 整屏覆盖层：与 Scaffold 同级（不吃内容区 padding），遮罩与底衬铺到系统栏之下
    // 转场（右侧滑入 + 淡入）与返回键统一由 AnimatedScreenOverlay 提供，取 token 并响应减弱动效
    // 开屏跳过规则管理页面（覆盖层）— 从右侧滑入
    // 覆盖层跟着功能开关走：跳过引擎关闭时 VM 为 null，这里不渲染
    AnimatedScreenOverlay(
        visible = showSkipRuleScreen && skipRuleViewModel != null,
        onBack = {
            onCloseSkipRules()
            skipRuleViewModel?.refresh()
        },
    ) {
        val vm = skipRuleViewModel ?: return@AnimatedScreenOverlay
        SkipRuleScreen(
            onBack = {
                onCloseSkipRules()
                vm.refresh()
            },
            viewModel = vm
        )
    }

    // 广告白名单页面（覆盖层）— 从右侧滑入
    AnimatedScreenOverlay(
        visible = showWhitelistScreen && whitelistViewModel != null,
        onBack = {
            onCloseWhitelist()
            whitelistViewModel?.refresh()
        },
    ) {
        val vm = whitelistViewModel ?: return@AnimatedScreenOverlay
        WhitelistScreen(
            onBack = {
                onCloseWhitelist()
                vm.refresh()
            },
            viewModel = vm
        )
    }

    // s12 组件展厅（占位屏，W2 接手）
    AnimatedScreenOverlay(
        visible = showGallery,
        onBack = { onCloseGallery() },
    ) {
        ComponentGalleryScreen(onBack = { onCloseGallery() })
    }

    // 额度设置：从额度页齿轮或设置页分组 push
    AnimatedScreenOverlay(
        visible = showQuotaSettings,
        onBack = { onCloseQuotaSettings() },
    ) {
        QuotaSettingsScreen(onBack = { onCloseQuotaSettings() })
    }

    // s13 外观 / 主题切换（占位屏，W3 接手）
    AnimatedScreenOverlay(
        visible = showAppearance,
        onBack = { onCloseAppearance() },
    ) {
        AppearanceScreen(onBack = { onCloseAppearance() })
    }

    // s16 每日行程：整屏覆盖 + 返回（底部 Tab 栏由 overlayOpen 一并收起）
    AnimatedScreenOverlay(
        visible = showTripScreen,
        onBack = { onCloseTrip() },
    ) {
        TripScreen(
            onBack = { onCloseTrip() },
            onOpenLocationSettings = onOpenLocationSettings,
        )
    }

    // 开屏：品牌 C2 动效，时序同原型 §8 —— 1600ms 起 240ms ease-in 淡出、1900ms 结束；
    // 减弱动效时落终态后 400ms 收起、700ms 结束（见 ui/components/BrandSplash.kt）。
    if (showSplash) {
        BrandSplash(onFinished = onSplashFinished)
    }
}
