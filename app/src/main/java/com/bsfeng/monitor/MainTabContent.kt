package com.bsfeng.monitor

// 从 MainActivity.kt 拆出：Tab 内容分发 —— 按当前 selectedTab 渲染对应屏，记账 Tab 内再按 FinanceRoute 分发子页。
// 负责「选中哪个 Tab 就出哪屏」；底部栏见 MainBottomBar.kt，覆盖层见 MainOverlays.kt。

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.bsfeng.monitor.feature.AppTab
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureCatalog
import com.bsfeng.monitor.finance.ui.AddTransactionScreen
import com.bsfeng.monitor.finance.ui.BudgetScreen
import com.bsfeng.monitor.finance.ui.FinanceMainScreen
import com.bsfeng.monitor.finance.ui.FinanceStatsScreen
import com.bsfeng.monitor.finance.ui.ImportScreen
import com.bsfeng.monitor.finance.ui.RowFocusAfterDelete
import com.bsfeng.monitor.finance.ui.neighborRowIdAfterDelete
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
 * 保存 / 删除成功后的返回延迟（毫秒）。
 *
 * s14a 口径：保存 / 删除成功 260ms 后返回列表 —— 让按下（0.97 / 120ms）与 SUCCESS / REJECT
 * 触感先落地，表单不当场卸载。失败 / 校验不通过不会走到这里（见各 onSave 回调上方的注释）。
 */
private const val FINANCE_BACK_DELAY_MS = 260L

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
    /** 打开记账表单并登记来源（true = 从统计页空态进来的），返回时据此回到原页。 */
    onOpenAddTransaction: (fromMonth: String, returnToStats: Boolean) -> Unit,
    /** 返回统计页的序号：>0 表示这一屏是被表单返回重新组合出来的（统计页据此恢复空态 CTA 焦点）。 */
    statsReturnTick: Int,
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
    /** 验证器入口（true = 直接落备份页）：概览图标与设置卡共用同一个宿主。 */
    onOpenTotp: (Boolean) -> Unit = {},
) {
    // 删除后返回列表的一次性焦点复位请求（锚点 s05-del-confirm · 移除后）。
    // 删除发生在「编辑」分支、消费点在「主页」分支，两者是 when 的不同分支 ——
    // 所以状态必须提在本函数体上，否则跨分支就丢了。
    var rowFocusAfterDelete by remember { mutableStateOf<RowFocusAfterDelete?>(null) }

    when (selectedTab) {
        AppTab.OVERVIEW -> OverviewScreen(
            // 功能关闭时点概览上的「实时 / 记账」快捷入口：原地不动，不回退、不崩溃
            onOpenLive = { onSelectTab(FeatureCatalog.resolveTab(AppTab.LIVE, featureOn)) },
            onOpenFinance = { onSelectTab(FeatureCatalog.resolveTab(AppTab.FINANCE, featureOn)) },
            onOpenTrip = onOpenTrip,
            // s01-empty-cta：空态 CTA 进设置「通用」分组的无障碍服务区。
            // 顺序固定：onSelectTab 会把子页清空，所以先切 Tab 再定子页。
            onOpenSettings = {
                onSelectTab(AppTab.SETTINGS)
                onOpenSettingsSubPage(SettingsSubPage.GENERAL)
            },
            // s01-recent-1..3：切到实时 Tab，并把该条滚到可视区 —— 先把一次性聚焦目标交给
            // MainViewModel，MainScreen 消费后清空（跨 Tab 切换期间事件不丢）。
            onOpenLiveEvent = { record ->
                mainViewModel.requestLiveFocus(record.id)
                onSelectTab(FeatureCatalog.resolveTab(AppTab.LIVE, featureOn))
            },
            // 功能关闭时图标整个不渲染（入口消失，而不是点了没反应）
            onOpenTotp = { onOpenTotp(false) }.takeIf { featureOn(Feature.TOTP) },
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
                    // 从主页进表单：返回落点 = 主页
                    onAddClick = { onOpenAddTransaction(fvm.currentMonth.value, false) },
                    // 锚点 s05-del-confirm · 移除后：删除返回后把焦点复位到相邻行
                    focusRowAfterDelete = rowFocusAfterDelete,
                    onRowFocusConsumed = { rowFocusAfterDelete = null },
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
                // 返回延迟的作用域挂在本路由分支上：路由离开（成功返回 / 用户取消）即取消协程，
                // 不会出现「用户已手动离开，260ms 后又把路由写成 Main」的返回两次 / 串页。
                is FinanceRoute.AddTransaction -> {
                    val backScope = rememberCoroutineScope()
                    // s05-empty-import：导入页叠在表单**之上**，不替换它 ——
                    // 金额 / 说明 / 时间都是表单自己的 remember 局部态，一旦离开组合就整份丢失，
                    // 而交互口径要求「返回后仍可保存」。叠放（同一 Box 的兄弟节点）保住表单实例，
                    // 返回时表单原样还在；导入完成后重新读分类与账户即可。
                    var importOverlay by remember(route) { mutableStateOf(false) }
                    fun closeImportOverlay() {
                        importOverlay = false
                        // 锚点 s05-empty-import · 移除后：返回后重新读取分类与账户
                        fvm.loadCurrentMonth()
                    }
                    // 叠层打开时返回键先收叠层：MainContent 的 BackHandler 会把整条记账路由回来源页，
                    // 那样表单照样被卸载、内容照样丢 —— 所以这一层必须比它先拿到返回键。
                    BackHandler(enabled = importOverlay) { closeImportOverlay() }
                    Box(Modifier.fillMaxSize()) {
                        AddTransactionScreen(
                            categories = fvm.categories.collectAsState().value,
                            accounts = fvm.accounts.collectAsState().value,
                            // 记账首页当前浏览月（W2 的 AddTransactionScreen.fromMonth）；形如 "2026-07"
                            fromMonth = route.fromMonth,
                            // 本回调只在表单校验通过时被调用（AddTransactionScreen:259），
                            // 失败 / 校验不通过停在本页，不走下面的延迟返回。
                            onSave = { tx ->
                                fvm.addTransaction(tx)
                                backScope.launch {
                                    delay(FINANCE_BACK_DELAY_MS)
                                    // 锚点 s14a-save「成功后页面返回来处」/ s07-empty-add「保存成功后空态卡
                                    // 立即移除、KPI 与图表按新数据重绘」：保存与取消走**同一个**返回落点
                                    // （按登记来源回统计页或记账主页）。硬编码主页会让「统计空态 → 新增 → 保存」
                                    // 掉回列表页，那一屏永远看不到（N2 只修了取消这一半）。
                                    onBackToFinanceMain()
                                }
                            },
                            // s05-empty-import：「去导入历史账目」必须真进导入页，而不是关掉本页；
                            // 打开方式 = 叠在本表单之上（见上面的 importOverlay），返回后表单内容不丢
                            onImportClick = { importOverlay = true },
                            onDismiss = onBackToFinanceMain
                        )
                        if (importOverlay) {
                            ImportScreen(
                                importing = fvm.importing.collectAsState().value,
                                importResult = fvm.importResult.collectAsState().value,
                                billImportResult = fvm.billImportResult.collectAsState().value,
                                onImport = { request -> fvm.dispatchImport(request) },
                                onDismiss = { closeImportOverlay() },
                                onClearResult = { fvm.clearImportResult() },
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                }
                is FinanceRoute.EditTransaction -> {
                    val backScope = rememberCoroutineScope()
                    AddTransactionScreen(
                        categories = fvm.categories.collectAsState().value,
                        accounts = fvm.accounts.collectAsState().value,
                        editing = route.transaction,
                        onSave = { tx ->
                            fvm.updateTransaction(tx)
                            backScope.launch {
                                delay(FINANCE_BACK_DELAY_MS)
                                onFinanceRoute(FinanceRoute.Main)
                            }
                        },
                        onDelete = { tx ->
                            // 锚点 s05-del-confirm · 移除后：必须用**删除前**的明细顺序算相邻行
                            // （下一行；末行则上一行），返回列表后由 FinanceMainScreen 把焦点复位到它。
                            val order = fvm.monthlyTransactions.value.map { it.id }
                            rowFocusAfterDelete = RowFocusAfterDelete(
                                deletedId = tx.id,
                                targetId = neighborRowIdAfterDelete(order, tx.id),
                            )
                            fvm.deleteTransaction(tx.id)
                            backScope.launch {
                                delay(FINANCE_BACK_DELAY_MS)
                                onFinanceRoute(FinanceRoute.Main)
                            }
                        },
                        onDismiss = onBackToFinanceMain
                    )
                }
                is FinanceRoute.Stats -> FinanceStatsScreen(
                    currentMonth = fvm.currentMonth.collectAsState().value,
                    stats = fvm.monthlyStats.collectAsState().value,
                    categoryBreakdown = fvm.categoryBreakdown.collectAsState().value,
                    onPreviousMonth = { fvm.previousMonth() },
                    onNextMonth = { fvm.nextMonth() },
                    // s07-empty-add：空态「新增一笔」跳到新增表单，浏览月按当前统计月带过去；
                    // 登记来源 = 统计页，表单取消 / 返回键都回到这张空态卡（不是记账主页）
                    onAddClick = { onOpenAddTransaction(fvm.currentMonth.value, true) },
                    // 从表单返回时恢复空态 CTA 的焦点（锚点 s07-empty-add · 移除后）
                    returnFocusTick = statsReturnTick,
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
                    billImportResult = fvm.billImportResult.collectAsState().value,
                    onImport = { request -> fvm.dispatchImport(request) },
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
            onNavigateToTotp = if (featureOn(Feature.TOTP)) onOpenTotp else null,
            modifier = Modifier.fillMaxSize()
        )
    }
}
