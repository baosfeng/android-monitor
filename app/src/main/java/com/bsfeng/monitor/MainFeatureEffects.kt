package com.bsfeng.monitor

// 从 MainActivity.kt 拆出：主界面的功能开关联动 —— Tab 被关闭时回退概览并收起覆盖层、
// 关闭「传送」时立刻停服务与发现线程、前后台切换时通知传送 ViewModel。
// 负责「开关一拨，正在跑的东西跟着停」；界面装配仍在 MainActivity。

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.bsfeng.monitor.feature.AppTab
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureCatalog
import com.bsfeng.monitor.transfer.service.TransferService
import com.bsfeng.monitor.transfer.ui.TransferViewModel
import android.content.Context
import androidx.compose.ui.platform.LocalContext

/**
 * 功能开关的副作用：开关变化 → Tab 回退 / 覆盖层收起 / 传送服务停启 / 前后台可见性。
 *
 * 全部拆成独立 effect，保持与改动前一致的触发时机（不合并成一个大 effect）。
 */
@Composable
internal fun MainFeatureEffects(
    enabledFeatures: Map<Feature, Boolean>,
    featureOn: (Feature) -> Boolean,
    adSkipOn: Boolean,
    selectedTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
    onHideSkipRules: () -> Unit,
    onHideWhitelist: () -> Unit,
    lifecycleOwner: LifecycleOwner,
    transferViewModel: TransferViewModel?,
) {
    val context = LocalContext.current
    // 当前选中 Tab 被关闭（用户在设置里关掉）→ 立即回退概览，不留空白页
    LaunchedEffect(enabledFeatures) {
    if (!FeatureCatalog.isTabVisible(selectedTab, featureOn)) {
        onSelectTab(FeatureCatalog.FALLBACK_TAB)
    }
    // 页面同时被关掉时，覆盖层也跟着收起（规则管理 / 白名单属于广告跳过）
    if (!adSkipOn) {
        onHideSkipRules()
        onHideWhitelist()
    }
    }

    // 关闭「传送」：立刻停掉服务与发现线程（不等下次前后台切换）
    LaunchedEffect(featureOn(Feature.TRANSPORT)) {
    if (!featureOn(Feature.TRANSPORT)) {
        TransferService.stop(context)
    }
    }

    // 「后台接收」关闭时：前台启动监听、后台停服务（ServiceModePolicy 的 UI 侧接线）
    // 功能关闭时连观察者都不挂：开关一关，这里没有任何传送相关的回调在跑
    DisposableEffect(lifecycleOwner, transferViewModel) {
    val vm = transferViewModel
    if (vm == null) {
        onDispose { }
    } else {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                // 只更新可见性：接收由用户显式开启（磁贴 / 传送页），App 打开不自动启动
                Lifecycle.Event.ON_START -> vm.onAppForeground()
                Lifecycle.Event.ON_STOP -> vm.onAppBackground()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    }
}
