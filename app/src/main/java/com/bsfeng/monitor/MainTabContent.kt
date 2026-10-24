package com.bsfeng.monitor

// 从 MainActivity.kt 拆出：Tab 内容分发 —— 按当前 selectedTab 渲染对应屏，记账 Tab 内再按 FinanceRoute 分发子页。
// 负责「选中哪个 Tab 就出哪屏」；底部栏见 MainBottomBar.kt，覆盖层见 MainOverlays.kt。

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.feature.AppTab
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureCatalog
import com.bsfeng.monitor.finance.ui.AddTransactionScreen
import com.bsfeng.monitor.finance.ui.BudgetScreen
import com.bsfeng.monitor.finance.ui.FinanceMainScreen
import com.bsfeng.monitor.finance.ui.FinanceStatsScreen
import com.bsfeng.monitor.finance.ui.ImportScreen
import com.bsfeng.monitor.transfer.ui.TransferScreen
import com.bsfeng.monitor.ui.AdScreen
import com.bsfeng.monitor.ui.MainScreen
import com.bsfeng.monitor.ui.MainViewModel
import com.bsfeng.monitor.ui.OverviewScreen
import com.bsfeng.monitor.ui.SettingsScreen
import com.bsfeng.monitor.ui.SettingsSubPage
import com.bsfeng.monitor.ui.quota.QuotaScreen
import com.bsfeng.monitor.finance.ui.FinanceViewModel

/**
 * 当前 Tab 的内容区（不含底部栏、不含覆盖层）。
 *
 * 所有「跳到别的 Tab / 打开覆盖层 / 改记账路由」都走回调，本文件不持有导航状态：
 * 状态仍集中在 [MainActivity] 的 setContent 里，改动前后时序不变。
 */
@Composable
internal fun MainTabContent(
    selectedTab: AppTab,
    mainViewModel: MainViewModel,
    financeViewModel: FinanceViewModel?,
    financeRoute: FinanceRoute,
    financeEntryKey: Int,
    featureOn: (Feature) -> Boolean,
    onSelectTab: (AppTab) -> Unit,
    onFinanceRoute: (FinanceRoute) -> Unit,
    onBackToFinanceMain: () -> Unit,
    onOpenTrip: () -> Unit,
    onOpenSkipRules: () -> Unit,
    onOpenWhitelist: () -> Unit,
    onOpenAppearance: () -> Unit,
    onOpenGallery: () -> Unit,
    onOpenQuotaSettings: () -> Unit,
    settingsSubPage: SettingsSubPage?,
    onOpenSettingsSubPage: (SettingsSubPage) -> Unit,
    onCloseSettingsSubPage: () -> Unit,
) {
    when (selectedTab) {
        AppTab.OVERVIEW -> OverviewScreen(
            // 功能关闭时点概览上的「实时 / 记账」快捷入口：原地不动，不回退、不崩溃
            onOpenLive = { onSelectTab(FeatureCatalog.resolveTab(AppTab.LIVE, featureOn)) },
            onOpenFinance = { onSelectTab(FeatureCatalog.resolveTab(AppTab.FINANCE, featureOn)) },
            onOpenTrip = onOpenTrip,
            modifier = Modifier.fillMaxSize()
        )
        AppTab.LIVE -> MainScreen(
            viewModel = mainViewModel,
            modifier = Modifier.fillMaxSize()
        )
        AppTab.AD -> AdScreen(
            // 跳过引擎关闭后规则管理不可进（广告记录页本身照常可看）
            onOpenRules = { if (featureOn(Feature.AD_SKIP)) onOpenSkipRules() },
            adViewModel = viewModel(),
            modifier = Modifier.fillMaxSize()
        )
        AppTab.TRANSFER -> TransferScreen(modifier = Modifier.fillMaxSize())
        AppTab.FINANCE -> financeViewModel?.let { fvm ->
            when (val route = financeRoute) {
                is FinanceRoute.Main -> FinanceMainScreen(
                    viewModel = fvm,
                    entryKey = financeEntryKey,
                    onAddClick = {
                        onFinanceRoute(
                            FinanceRoute.AddTransaction(
                                fvm.currentMonth.value
                            )
                        )
                    },
                    onImportClick = {
                        onFinanceRoute(FinanceRoute.Import)
                    },
                    onEditClick = { tx ->
                        onFinanceRoute(FinanceRoute.EditTransaction(tx))
                    },
                    onStatsClick = { onFinanceRoute(FinanceRoute.Stats) },
                    onBudgetClick = { onFinanceRoute(FinanceRoute.Budget) },
                    modifier = Modifier.fillMaxSize()
                )
                is FinanceRoute.AddTransaction -> AddTransactionScreen(
                    categories = fvm.categories.collectAsState().value,
                    accounts = fvm.accounts.collectAsState().value,
                    // 记账首页当前浏览月（W2 的 AddTransactionScreen.fromMonth）；形如 "2026-07"
                    fromMonth = route.fromMonth,
                    onSave = { tx ->
                        fvm.addTransaction(tx)
                        onFinanceRoute(FinanceRoute.Main)
                    },
                    onDismiss = onBackToFinanceMain
                )
                is FinanceRoute.EditTransaction -> AddTransactionScreen(
                    categories = fvm.categories.collectAsState().value,
                    accounts = fvm.accounts.collectAsState().value,
                    editing = route.transaction,
                    onSave = { tx ->
                        fvm.updateTransaction(tx)
                        onFinanceRoute(FinanceRoute.Main)
                    },
                    onDelete = { tx ->
                        fvm.deleteTransaction(tx.id)
                        onFinanceRoute(FinanceRoute.Main)
                    },
                    onDismiss = onBackToFinanceMain
                )
                is FinanceRoute.Stats -> FinanceStatsScreen(
                    currentMonth = fvm.currentMonth.collectAsState().value,
                    stats = fvm.monthlyStats.collectAsState().value,
                    categoryBreakdown = fvm.categoryBreakdown.collectAsState().value,
                    onPreviousMonth = { fvm.previousMonth() },
                    onNextMonth = { fvm.nextMonth() },
                    onBack = onBackToFinanceMain,
                    modifier = Modifier.fillMaxSize()
                )
                is FinanceRoute.Budget -> BudgetScreen(
                    viewModel = fvm,
                    onBack = onBackToFinanceMain,
                    modifier = Modifier.fillMaxSize()
                )
                is FinanceRoute.Import -> ImportScreen(
                    importing = fvm.importing.collectAsState().value,
                    importResult = fvm.importResult.collectAsState().value,
                    onImport = { path -> fvm.importFromPanda(path) },
                    onDismiss = onBackToFinanceMain,
                    onClearResult = { fvm.clearImportResult() }
                )
            }
        }
        AppTab.QUOTA -> QuotaScreen(
            onOpenSettings = onOpenQuotaSettings,
            modifier = Modifier.fillMaxSize()
        )
        AppTab.SETTINGS -> SettingsScreen(
            viewModel = mainViewModel,
            subPage = settingsSubPage,
            onOpenSubPage = onOpenSettingsSubPage,
            onCloseSubPage = onCloseSettingsSubPage,
            onNavigateToSkipRules = onOpenSkipRules,
            onNavigateToWhitelist = onOpenWhitelist,
            onNavigateToAppearance = onOpenAppearance,
            onNavigateToGallery = onOpenGallery,
            onNavigateToQuotaSettings = onOpenQuotaSettings,
            modifier = Modifier.fillMaxSize()
        )
    }
}
