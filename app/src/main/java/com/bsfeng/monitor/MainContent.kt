package com.bsfeng.monitor

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AccountBalanceWallet
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.feature.AppTab
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureCatalog
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.finance.ui.AddTransactionScreen
import com.bsfeng.monitor.finance.ui.BudgetScreen
import com.bsfeng.monitor.finance.ui.FinanceMainScreen
import com.bsfeng.monitor.finance.ui.FinanceStatsScreen
import com.bsfeng.monitor.finance.ui.FinanceViewModel as FinanceViewModel
import com.bsfeng.monitor.finance.ui.ImportScreen
import com.bsfeng.monitor.transfer.service.TransferService
import com.bsfeng.monitor.transfer.ui.TransferScreen
import com.bsfeng.monitor.transfer.ui.TransferViewModel
import com.bsfeng.monitor.ui.AdScreen
import com.bsfeng.monitor.ui.AdViewModel
import com.bsfeng.monitor.ui.MainScreen
import com.bsfeng.monitor.ui.MainViewModel
import com.bsfeng.monitor.ui.OverviewScreen
import com.bsfeng.monitor.ui.SettingsScreen
import com.bsfeng.monitor.ui.SettingsSubPage
import com.bsfeng.monitor.ui.SkipRuleScreen
import com.bsfeng.monitor.ui.SkipRuleViewModel
import com.bsfeng.monitor.ui.WhitelistScreen
import com.bsfeng.monitor.ui.WhitelistViewModel
import com.bsfeng.monitor.ui.components.AppearanceScreen
import com.bsfeng.monitor.ui.components.BrandSplash
import com.bsfeng.monitor.ui.components.ComponentGalleryScreen
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.ui.foundation.AnimatedScreenOverlay
import com.bsfeng.monitor.ui.foundation.JankMonitor
import com.bsfeng.monitor.ui.foundation.MonitorEdgeToEdge
import com.bsfeng.monitor.ui.foundation.MotionConfig
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.ui.quota.QuotaScreen
import com.bsfeng.monitor.ui.quota.QuotaSettingsScreen
import com.bsfeng.monitor.ui.theme.DesignTokenCatalog
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.MonitorTheme
import com.bsfeng.monitor.ui.theme.ThemeRepository
import com.bsfeng.monitor.ui.theme.accentFade
import com.bsfeng.monitor.ui.trip.TripScreen

// 从 MainActivity.kt 拆出：主界面内容（主题包裹 + 6 Tab 脚手架 + 各页面路由 + 浮层）。
// Activity 只负责窗口级的边到边、通知权限与卡顿采样；界面在这里组合，便于单独阅读。


/**
 * 主界面内容：主题包裹 + 6 Tab 脚手架 + 各页面路由与浮层。
 *
 * 功能开关在 composable 入口读一次，关闭的功能不创建对应 ViewModel（不建状态、不读库、不起线程）。
 *
 * @param startTab 通知点进来的目标 Tab 下标
 * @param startTabRequest 深链请求序号（同一 Tab 连点也生效）
 * @param openAddTransactionRequest 「快捷记一笔」直达序号（0 = 无请求）：命中后落点从记账首页换成新增页
 */
@Composable
internal fun MainContent(
    startTab: Int,
    startTabRequest: Int,
    applicationContext: android.content.Context,
    openAddTransactionRequest: Int = 0,
) {
    MonitorTheme {
            val viewModel: MainViewModel = viewModel()
            // 功能开关：一处读，下面所有 Tab / 页面 / 服务的取舍都按它判断
            val featureFlags = FeatureFlags.get()
            val enabledFeatures by featureFlags.enabled.collectAsState()
            fun featureOn(feature: Feature): Boolean = enabledFeatures[feature] == true
            val adSkipOn = featureOn(Feature.AD_SKIP)
            val lifecycleOwner = LocalLifecycleOwner.current
            // 关闭的功能不创建对应 ViewModel：不建状态、不读库、不起线程。
            // 传送到 MV 的 init 会把引擎标记为可见，所以关闭时连实例都不能要。
            val transferViewModel: TransferViewModel? =
                if (featureOn(Feature.TRANSPORT)) viewModel() else null
            val financeViewModel: FinanceViewModel? =
                if (featureOn(Feature.FINANCE)) viewModel() else null
            val tokens = LocalDesignTokens.current
            // 6 Tab：概览(s01) / 实时(s02) / 广告(s03) / 记账(s04) / 传送(局域网互传) / 设置(s09)
            // 动态列表见 tabs：关闭的功能不生成 Tab，选中项被关闭时回退概览
            var selectedTab by rememberSaveable { mutableStateOf(AppTab.OVERVIEW) }
            var financeRoute by rememberSaveable(stateSaver = FinanceRouteSaver) {
                mutableStateOf<FinanceRoute>(FinanceRoute.Main)
            }
            // 每次切到记账 Tab 或从子页返回时自增，驱动记账首页把列表滚回顶部
            var financeEntryKey by remember { mutableIntStateOf(0) }
            // 记账表单是从哪进来的：true = 统计页空态「新增一笔」，false = 记账主页（FAB / 空态 CTA / 小组件）。
            // 之前所有子页返回都硬编码回「记账主页」，从统计空态点「新增一笔」再取消会掉回列表页，
            // 焦点、滚动位置、「共 N 个分类」一起丢（契约 s07-empty-add · 移除后「空态卡原位保留」）。
            var addReturnsToStats by rememberSaveable { mutableStateOf(false) }
            // 返回统计页时自增：这一屏是「被表单返回」重新组合出来的，统计页据此把焦点放回空态 CTA
            var statsReturnTick by remember { mutableIntStateOf(0) }

            /** 打开记账表单并记住来源（页面路由集中在下面，本函数只做「记来源 + 改路由」一件事）。 */
            fun openAddTransaction(fromMonth: String, returnToStats: Boolean) {
                addReturnsToStats = returnToStats
                financeRoute = FinanceRoute.AddTransaction(fromMonth)
            }

            /**
             * 记账子页的统一返回落点：**从哪来回哪去**。
             *
             * 表单（AddTransaction）按登记过的来源回统计页或记账主页；统计 / 预算 / 导入等子页一律回主页。
             * 表单从统计页返回时 +1 [statsReturnTick]，让统计页恢复「空态 CTA 焦点」——表单页会把
             * 统计页整屏卸载，返回后是重新组合的一屏，焦点与滚动都不会自动回来。
             */
            fun backToFinanceSource() {
                if (financeRoute is FinanceRoute.AddTransaction && addReturnsToStats) {
                    financeRoute = FinanceRoute.Stats
                    statsReturnTick++
                } else {
                    financeRoute = FinanceRoute.Main
                    financeEntryKey++
                    // 回主页 = 离开统计页：清掉一次性回焦信号，避免下次正常进统计页误抢焦点
                    statsReturnTick = 0
                }
                addReturnsToStats = false
            }
            var showSkipRuleScreen by remember { mutableStateOf(false) }
            var showWhitelistScreen by remember { mutableStateOf(false) }
            var showGallery by remember { mutableStateOf(false) }
            var showAppearance by remember { mutableStateOf(false) }
            // 额度设置（从额度页齿轮 / 设置页分组 push）
            var showQuotaSettings by remember { mutableStateOf(false) }
            // 设置二级页：存枚举名（String 进 Bundle），旋转 / 进程重建后能回到同一页；
            // 空串 = 停留在一级页。Tab 切换时重置（见 onSelectTab 与深链兜底）。
            var settingsSubPageName by rememberSaveable { mutableStateOf("") }
            val settingsSubPage = SettingsSubPage.entries.firstOrNull { it.name == settingsSubPageName }
            // s16 每日行程（从概览「今日行程」卡 push）
            var showTripScreen by remember { mutableStateOf(false) }
            var showSplash by remember { mutableStateOf(true) }
            // 跳过引擎关闭时仍允许「只读查看」规则与白名单（契约 s09:37「组内两行常驻」）：
            // VM 的 init 只读本地库、不起服务，但也不该在关闭态无谓创建——首次打开覆盖层时才建。
            val skipRuleViewModel: SkipRuleViewModel? =
                if (adSkipOn || showSkipRuleScreen) viewModel() else null
            val whitelistViewModel: WhitelistViewModel? =
                if (adSkipOn || showWhitelistScreen) viewModel() else null
            val view = LocalView.current
            val overlayOpen = showSkipRuleScreen || showWhitelistScreen || showGallery ||
                showAppearance || showTripScreen || showQuotaSettings ||
                (selectedTab == AppTab.SETTINGS && settingsSubPage != null)
            // 原型二级页（记账子页 / 设置子页）无 Tab 栏
            val financeSubPage = selectedTab == AppTab.FINANCE && financeRoute != FinanceRoute.Main
            val bottomBarVisible = !overlayOpen && !showSplash && !financeSubPage

            // 底部 Tab 由开关驱动：关闭的功能不生成 Tab
            val tabs = remember(enabledFeatures) {
                FeatureCatalog.visibleTabs { feature -> enabledFeatures[feature] == true }
            }

            // 当前选中 Tab 被关闭（用户在设置里关掉）→ 立即回退概览，不留空白页
            LaunchedEffect(enabledFeatures) {
                if (!FeatureCatalog.isTabVisible(selectedTab, ::featureOn)) {
                    selectedTab = FeatureCatalog.FALLBACK_TAB
                }
                // 页面同时被关掉时，覆盖层也跟着收起（规则管理 / 白名单属于广告跳过）
                if (!adSkipOn) {
                    showSkipRuleScreen = false
                    showWhitelistScreen = false
                }
            }

            // 关闭「传送」：立刻停掉服务与发现线程（不等下次前后台切换）
            LaunchedEffect(featureOn(Feature.TRANSPORT)) {
                if (!featureOn(Feature.TRANSPORT)) {
                    TransferService.stop(applicationContext)
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
            // 记账子页面（统计/预算/编辑/导入）下按系统返回键回到**来源页**（表单来自统计空态时回统计页）
            BackHandler(enabled = selectedTab == AppTab.FINANCE && financeRoute != FinanceRoute.Main) {
                backToFinanceSource()
            }

            // 通知点击「直达传送页」：目标功能已关闭时回退概览（不崩溃、不留空 Tab）
            LaunchedEffect(startTabRequest) {
                // 首次冷启动没有深链（下标 0）时不覆盖 rememberSaveable 恢复的 Tab；
                // 从通知再次进入时一律按深链落点走（同一 Tab 连点也生效，靠序号而非下标比较）
                if (startTabRequest > 0 || startTab != 0) {
                    settingsSubPageName = ""
                    selectedTab = FeatureCatalog.resolveTab(
                        FeatureCatalog.tabOfIndex(startTab),
                    ) { feature -> enabledFeatures[feature] == true }
                }
            }

            // 小组件「快捷记一笔」：记账 Tab + 直接进当月的新增页。
            // 与上面的 startTab 深链分开成两个 effect：目标 Tab 由 startTab 决定，
            // 这里只负责把记账 Tab 的子页换成 AddTransaction，两者互不覆盖。
            LaunchedEffect(openAddTransactionRequest) {
                if (openAddTransactionRequest <= 0) return@LaunchedEffect
                val target = FeatureCatalog.resolveTab(
                    FeatureCatalog.tabOfIndex(MainActivity.FINANCE_TAB),
                ) { feature -> enabledFeatures[feature] == true }
                // 记账功能被关掉时 resolveTab 回退概览：此时按普通深链走，不留一张空表单
                if (target != AppTab.FINANCE) return@LaunchedEffect
                settingsSubPageName = ""
                selectedTab = target
                // 小组件直达：不是从统计空态进来的，返回一律回记账主页
                openAddTransaction(FinanceViewModel.currentMonthStr(), returnToStats = false)
            }

            Box(Modifier.fillMaxSize().pageBackdrop()) {
                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    bottomBar = {
                        if (bottomBarVisible) {
                            // 底栏组件见 MainBottomBar.kt；关闭的功能点不到空页，走 resolveTab 兜底概览
                            MainBottomBar(
                                tabs = tabs,
                                selectedTab = selectedTab,
                                featureOn = ::featureOn,
                                onSelectTab = { tab ->
                                    selectedTab = tab
                                    // 切 Tab 一律回一级页，不留半屏二级页
                                    settingsSubPageName = ""
                                    if (tab == AppTab.FINANCE) {
                                        financeRoute = FinanceRoute.Main
                                        financeEntryKey++
                                        // 切 Tab 一律回一级页：之前登记的「表单来源」与回焦信号都不再有效
                                        addReturnsToStats = false
                                        statsReturnTick = 0
                                    }
                                },
                                onTapHaptic = {
                                    view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                },
                            )
                        }
                    },
                ) { paddingValues ->
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(paddingValues)
                    ) {
                        MainTabContent(
                            selectedTab = selectedTab,
                            mainViewModel = viewModel,
                            financeViewModel = financeViewModel,
                            financeRoute = financeRoute,
                            financeEntryKey = financeEntryKey,
                            featureOn = ::featureOn,
                            onSelectTab = {
                                selectedTab = it
                                settingsSubPageName = ""
                            },
                            onFinanceRoute = { next ->
                                financeRoute = next
                                // 一次性信号（返回统计页回焦 CTA）只对「刚回来的那一屏」有效：
                                // 任何不落在统计页的跳转都清掉它，否则下一次正常进统计页会误抢焦点。
                                if (next !is FinanceRoute.Stats) statsReturnTick = 0
                                // 「表单来源」由 openAddTransaction 登记；走别的路由说明这次表单已经结束
                                if (next !is FinanceRoute.AddTransaction) addReturnsToStats = false
                            },
                            onOpenAddTransaction = ::openAddTransaction,
                            statsReturnTick = statsReturnTick,
                            onBackToFinanceMain = ::backToFinanceSource,
                            onOpenTrip = { showTripScreen = true },
                            onOpenSkipRules = { showSkipRuleScreen = true },
                            onOpenWhitelist = { showWhitelistScreen = true },
                            onOpenAppearance = { showAppearance = true },
                            onOpenGallery = { showGallery = true },
                            onOpenQuotaSettings = { showQuotaSettings = true },
                            settingsSubPage = settingsSubPage,
                            onOpenSettingsSubPage = { settingsSubPageName = it.name },
                            onCloseSettingsSubPage = { settingsSubPageName = "" },
                        )
                    }
                }


            MainOverlays(
                showSkipRuleScreen = showSkipRuleScreen,
                showWhitelistScreen = showWhitelistScreen,
                showGallery = showGallery,
                showQuotaSettings = showQuotaSettings,
                showAppearance = showAppearance,
                showTripScreen = showTripScreen,
                showSplash = showSplash,
                skipRuleViewModel = skipRuleViewModel,
                whitelistViewModel = whitelistViewModel,
                onCloseSkipRules = {
                    showSkipRuleScreen = false
                    skipRuleViewModel?.refresh()
                },
                onCloseWhitelist = {
                    showWhitelistScreen = false
                    whitelistViewModel?.refresh()
                },
                onCloseGallery = { showGallery = false },
                onCloseQuotaSettings = { showQuotaSettings = false },
                onCloseAppearance = { showAppearance = false },
                onCloseTrip = { showTripScreen = false },
                onOpenLocationSettings = {
                    // s16-empty-cta / s16-goto-location：关覆盖层 → 切设置 Tab → 打开「功能」子页
                    // （定位权限卡在该子页末位；契约只要求「跳到设置页定位权限项」，不额外做精确滚动）
                    showTripScreen = false
                    selectedTab = AppTab.SETTINGS
                    settingsSubPageName = SettingsSubPage.FEATURE.name
                },
                onSplashFinished = { showSplash = false },
            )
            }
    }
}
