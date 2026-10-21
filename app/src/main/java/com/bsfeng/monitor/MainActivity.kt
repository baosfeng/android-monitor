package com.bsfeng.monitor

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
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
import com.bsfeng.monitor.finance.ui.ImportScreen
import com.bsfeng.monitor.finance.ui.FinanceViewModel as FinanceViewModel
import com.bsfeng.monitor.ui.MainScreen
import com.bsfeng.monitor.ui.MainViewModel
import com.bsfeng.monitor.ui.SettingsScreen
import com.bsfeng.monitor.ui.SkipRuleScreen
import com.bsfeng.monitor.ui.SkipRuleViewModel
import com.bsfeng.monitor.ui.AdScreen
import com.bsfeng.monitor.ui.AdViewModel
import com.bsfeng.monitor.ui.WhitelistScreen
import com.bsfeng.monitor.ui.WhitelistViewModel
import com.bsfeng.monitor.ui.components.AppearanceScreen
import com.bsfeng.monitor.ui.theme.DesignTokenCatalog
import com.bsfeng.monitor.ui.components.BrandSplash
import com.bsfeng.monitor.ui.components.ComponentGalleryScreen
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.transfer.service.TransferService
import com.bsfeng.monitor.transfer.ui.TransferScreen
import com.bsfeng.monitor.transfer.ui.TransferViewModel
import com.bsfeng.monitor.ui.OverviewScreen
import com.bsfeng.monitor.ui.foundation.MonitorEdgeToEdge
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.accentFade
import com.bsfeng.monitor.ui.theme.MonitorTheme
import com.bsfeng.monitor.ui.theme.ThemeRepository
import androidx.compose.ui.graphics.vector.ImageVector

class MainActivity : ComponentActivity() {

    /** 从通知点进来的目标 Tab 下标（默认 0 = 概览；下标 = [AppTab] 枚举顺序）。 */
    private var startTab by mutableIntStateOf(0)

    /** 自增序号：同一条通知再点一次也要重新落点（只对比下标会漏掉「已在同一 Tab」的情况）。 */
    private var startTabRequest by mutableIntStateOf(0)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startTab = intent.getIntExtra(EXTRA_START_TAB, startTab)
        startTabRequest++
    }

    private sealed class FinanceRoute {
        data object Main : FinanceRoute()
        data class AddTransaction(val fromMonth: String) : FinanceRoute()
        data class EditTransaction(val transaction: Transaction) : FinanceRoute()
        data object Stats : FinanceRoute()
        data object Budget : FinanceRoute()
        data object Import : FinanceRoute()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // 全应用仅浅色主题：窗口外观（系统栏透明 + 图标定色）统一由 MonitorEdgeToEdge 负责
        MonitorEdgeToEdge.apply(this)

        startTab = intent?.getIntExtra(EXTRA_START_TAB, 0) ?: 0

        // 主题持久化：必须在 setContent 之前读，保证首帧就是用户选定的主题
        ThemeRepository.init(applicationContext)

        // 自验钩子（演示件）：把当前主题全部 token 的运行时值打到 logcat，tag=TokenDump。
        // 用法：adb logcat -d -s TokenDump  →  逐条比对 CSS 原文值。仅「调试模式」打开时输出。
        if (com.bsfeng.monitor.util.DebugConfig.demoVisible) {
            val active = ThemeRepository.currentThemeId()
            android.util.Log.i("TokenDump", "theme=" + active + " tokens=" + DesignTokenCatalog.dumpAll(active).size)
            DesignTokenCatalog.dumpAll(active).forEach { android.util.Log.i("TokenDump", it) }
        }

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        // POST_NOTIFICATIONS 是 API 33+ 的运行时权限：29–32 没有该权限，直接申请会被拒
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !nm.areNotificationsEnabled()) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        setContent {
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
                val skipRuleViewModel: SkipRuleViewModel? = if (adSkipOn) viewModel() else null
                val whitelistViewModel: WhitelistViewModel? = if (adSkipOn) viewModel() else null
                val tokens = LocalDesignTokens.current
                // 6 Tab：概览(s01) / 实时(s02) / 广告(s03) / 记账(s04) / 传送(局域网互传) / 设置(s09)
                // 动态列表见 tabs：关闭的功能不生成 Tab，选中项被关闭时回退概览
                var selectedTab by rememberSaveable { mutableStateOf(AppTab.OVERVIEW) }
                var financeRoute by rememberSaveable(stateSaver = FinanceRouteSaver) {
                    mutableStateOf<FinanceRoute>(FinanceRoute.Main)
                }
                // 每次切到记账 Tab 或从子页返回时自增，驱动记账首页把列表滚回顶部
                var financeEntryKey by remember { mutableIntStateOf(0) }
                var showSkipRuleScreen by remember { mutableStateOf(false) }
                var showWhitelistScreen by remember { mutableStateOf(false) }
                var showGallery by remember { mutableStateOf(false) }
                var showAppearance by remember { mutableStateOf(false) }
                var showSplash by remember { mutableStateOf(true) }
                val view = LocalView.current
                val overlayOpen = showSkipRuleScreen || showWhitelistScreen || showGallery || showAppearance
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
                // 记账子页面（统计/预算/编辑/导入）下按系统返回键回到记账首页
                BackHandler(enabled = selectedTab == AppTab.FINANCE && financeRoute != FinanceRoute.Main) {
                    financeRoute = FinanceRoute.Main
                    financeEntryKey++
                }

                // 通知点击「直达传送页」：目标功能已关闭时回退概览（不崩溃、不留空 Tab）
                LaunchedEffect(startTabRequest) {
                    // 首次冷启动没有深链（下标 0）时不覆盖 rememberSaveable 恢复的 Tab；
                    // 从通知再次进入时一律按深链落点走（同一 Tab 连点也生效，靠序号而非下标比较）
                    if (startTabRequest > 0 || startTab != 0) {
                        selectedTab = FeatureCatalog.resolveTab(
                            FeatureCatalog.tabOfIndex(startTab),
                        ) { feature -> enabledFeatures[feature] == true }
                    }
                }

                Box(Modifier.fillMaxSize().pageBackdrop()) {
                    Scaffold(
                        modifier = Modifier.fillMaxSize(),
                        containerColor = Color.Transparent,
                        bottomBar = {
                            if (bottomBarVisible) {
                                // 原型 .tabbar / .tab（00-shell.html:174-197）：
                                // 底 = color-mix(surface 84%)、上边 1px --border-soft、选中胶囊 = color-mix(accent 14%)、字 = accent / muted
                                val barColors = NavigationBarItemDefaults.colors(
                                    selectedIconColor = tokens.accent,
                                    selectedTextColor = tokens.accent,
                                    indicatorColor = tokens.accentFade(14),
                                    unselectedIconColor = tokens.muted,
                                    unselectedTextColor = tokens.muted,
                                )
                                NavigationBar(
                                    modifier = Modifier.drawWithContent {
                                        drawContent()
                                        drawLine(
                                            color = tokens.borderSoft,
                                            start = Offset(0f, 0f),
                                            end = Offset(size.width, 0f),
                                            strokeWidth = 1.dp.toPx(),
                                        )
                                    },
                                    containerColor = tokens.surface.copy(alpha = tokens.surface.alpha * 0.84f),
                                    tonalElevation = 0.dp,
                                ) {
                                    tabs.forEach { tab ->
                                        val isSelected = selectedTab == tab
                                        val tabColor by animateColorAsState(
                                            targetValue = if (isSelected) tokens.accent else tokens.muted,
                                            animationSpec = tokens.springOf<Color>(MotionToken.State),
                                        )
                                        NavigationBarItem(
                                            colors = barColors,
                                            selected = isSelected,
                                            onClick = {
                                                // 已关闭的功能即使被点到也不会留空页：走 resolveTab 兜底到概览
                                                selectedTab = FeatureCatalog.resolveTab(tab, ::featureOn)
                                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                if (tab == AppTab.FINANCE) {
                                                    financeRoute = FinanceRoute.Main
                                                    financeEntryKey++
                                                }
                                            },
                                            icon = {
                                                Icon(
                                                    tab.icon(isSelected),
                                                    contentDescription = tab.title,
                                                    modifier = Modifier.size(22.dp),
                                                    tint = tabColor,
                                                )
                                            },
                                            label = {
                                                // 6 个 Tab 时单项宽度约 65dp：labelSmall(12sp) + 单行不折行
                                                Text(
                                                    text = tab.title,
                                                    color = tabColor,
                                                    style = MaterialTheme.typography.labelSmall,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                )
                                            },
                                        )
                                    }
                                }
                            }
                        },
                    ) { paddingValues ->
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(paddingValues)
                        ) {
                            when (selectedTab) {
                                AppTab.OVERVIEW -> OverviewScreen(
                                    // 功能关闭时点概览上的「实时 / 记账」快捷入口：原地不动，不回退、不崩溃
                                    onOpenLive = { selectedTab = FeatureCatalog.resolveTab(AppTab.LIVE, ::featureOn) },
                                    onOpenFinance = { selectedTab = FeatureCatalog.resolveTab(AppTab.FINANCE, ::featureOn) },
                                    modifier = Modifier.fillMaxSize()
                                )
                                AppTab.LIVE -> MainScreen(
                                    viewModel = viewModel,
                                    modifier = Modifier.fillMaxSize()
                                )
                                AppTab.AD -> AdScreen(
                                    // 跳过引擎关闭后规则管理不可进（广告记录页本身照常可看）
                                    onOpenRules = { if (featureOn(Feature.AD_SKIP)) showSkipRuleScreen = true },
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
                                                financeRoute = FinanceRoute.AddTransaction(
                                                    fvm.currentMonth.value
                                                )
                                            },
                                            onImportClick = {
                                                financeRoute = FinanceRoute.Import
                                            },
                                            onEditClick = { tx ->
                                                financeRoute = FinanceRoute.EditTransaction(tx)
                                            },
                                            onStatsClick = { financeRoute = FinanceRoute.Stats },
                                            onBudgetClick = { financeRoute = FinanceRoute.Budget },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        is FinanceRoute.AddTransaction -> AddTransactionScreen(
                                            categories = fvm.categories.collectAsState().value,
                                            accounts = fvm.accounts.collectAsState().value,
                                            // 记账首页当前浏览月（W2 的 AddTransactionScreen.fromMonth）；形如 "2026-07"
                                            fromMonth = route.fromMonth,
                                            onSave = { tx ->
                                                fvm.addTransaction(tx)
                                                financeRoute = FinanceRoute.Main
                                            },
                                            onDismiss = { financeRoute = FinanceRoute.Main; financeEntryKey++ }
                                        )
                                        is FinanceRoute.EditTransaction -> AddTransactionScreen(
                                            categories = fvm.categories.collectAsState().value,
                                            accounts = fvm.accounts.collectAsState().value,
                                            editing = route.transaction,
                                            onSave = { tx ->
                                                fvm.updateTransaction(tx)
                                                financeRoute = FinanceRoute.Main
                                            },
                                            onDelete = { tx ->
                                                fvm.deleteTransaction(tx.id)
                                                financeRoute = FinanceRoute.Main
                                            },
                                            onDismiss = { financeRoute = FinanceRoute.Main; financeEntryKey++ }
                                        )
                                        is FinanceRoute.Stats -> FinanceStatsScreen(
                                            currentMonth = fvm.currentMonth.collectAsState().value,
                                            stats = fvm.monthlyStats.collectAsState().value,
                                            categoryBreakdown = fvm.categoryBreakdown.collectAsState().value,
                                            onPreviousMonth = { fvm.previousMonth() },
                                            onNextMonth = { fvm.nextMonth() },
                                            onBack = { financeRoute = FinanceRoute.Main; financeEntryKey++ },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        is FinanceRoute.Budget -> BudgetScreen(
                                            viewModel = fvm,
                                            onBack = { financeRoute = FinanceRoute.Main; financeEntryKey++ },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        is FinanceRoute.Import -> ImportScreen(
                                            importing = fvm.importing.collectAsState().value,
                                            importResult = fvm.importResult.collectAsState().value,
                                            onImport = { path -> fvm.importFromPanda(path) },
                                            onDismiss = { financeRoute = FinanceRoute.Main; financeEntryKey++ },
                                            onClearResult = { fvm.clearImportResult() }
                                        )
                                    }
                                }
                                AppTab.SETTINGS -> SettingsScreen(
                                    viewModel = viewModel,
                                    onNavigateToSkipRules = { showSkipRuleScreen = true },
                                    onNavigateToWhitelist = { showWhitelistScreen = true },
                                    onNavigateToAppearance = { showAppearance = true },
                                    onNavigateToGallery = { showGallery = true },
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                        }
                    }

                    // 整屏覆盖层：与 Scaffold 同级（不吃内容区 padding），遮罩与底衬铺到系统栏之下
                    // 开屏跳过规则管理页面（覆盖层）— 从右侧滑入
                    // 覆盖层跟着功能开关走：跳过引擎关闭时 VM 为 null，这里不渲染
                    AnimatedVisibility(
                        visible = showSkipRuleScreen && skipRuleViewModel != null,
                        enter = slideInHorizontally() + fadeIn(),
                        exit = slideOutHorizontally() + fadeOut()
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            val vm = skipRuleViewModel ?: return@Box
                            BackHandler {
                                showSkipRuleScreen = false
                                vm.refresh()
                            }
                            SkipRuleScreen(
                                onBack = {
                                    showSkipRuleScreen = false
                                    vm.refresh()
                                },
                                viewModel = vm
                            )
                        }
                    }

                    // 广告白名单页面（覆盖层）— 从右侧滑入
                    AnimatedVisibility(
                        visible = showWhitelistScreen && whitelistViewModel != null,
                        enter = slideInHorizontally() + fadeIn(),
                        exit = slideOutHorizontally() + fadeOut()
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            val vm = whitelistViewModel ?: return@Box
                            BackHandler(showWhitelistScreen) {
                                showWhitelistScreen = false
                                vm.refresh()
                            }
                            WhitelistScreen(
                                onBack = {
                                    showWhitelistScreen = false
                                    vm.refresh()
                                },
                                viewModel = vm
                            )
                        }
                    }

                    // s12 组件展厅（占位屏，W2 接手）
                    AnimatedVisibility(
                        visible = showGallery,
                        enter = slideInHorizontally() + fadeIn(),
                        exit = slideOutHorizontally() + fadeOut()
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            BackHandler(showGallery) { showGallery = false }
                            ComponentGalleryScreen(onBack = { showGallery = false })
                        }
                    }

                    // s13 外观 / 主题切换（占位屏，W3 接手）
                    AnimatedVisibility(
                        visible = showAppearance,
                        enter = slideInHorizontally() + fadeIn(),
                        exit = slideOutHorizontally() + fadeOut()
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            BackHandler(showAppearance) { showAppearance = false }
                            AppearanceScreen(onBack = { showAppearance = false })
                        }
                    }

                    // 开屏：品牌 C2 动效，时序同原型 §8 —— 1600ms 起 240ms ease-in 淡出、1900ms 结束；
                    // 减弱动效时落终态后 400ms 收起、700ms 结束（见 ui/components/BrandSplash.kt）。
                    if (showSplash) {
                        BrandSplash(onFinished = { showSplash = false })
                    }
                }
            }
        }
    }

    companion object {
        /** 启动/通知点击时指定目标 Tab 的 Intent extra。 */
        const val EXTRA_START_TAB = "com.bsfeng.monitor.EXTRA_START_TAB"

        // 历史下标（= AppTab 枚举顺序）：通知里仍按 int 传，落点由 FeatureCatalog 转成 AppTab。
        // 顺序不要改（旧通知的 int 会错位），新增 Tab 追加在末尾。
        const val LIVE_TAB = 1
        const val AD_TAB = 2
        const val FINANCE_TAB = 3
        const val TRANSFER_TAB = 4
        const val SETTINGS_TAB = 5

        /** 旋转 / 进程重建后仍停留在原子页：把路由编码成可保存的原语列表。 */
        private val FinanceRouteSaver: Saver<FinanceRoute, Any> = listSaver(
            save = { route ->
                when (route) {
                    is FinanceRoute.Main -> listOf("main")
                    is FinanceRoute.AddTransaction -> listOf("add", route.fromMonth)
                    is FinanceRoute.EditTransaction -> with(route.transaction) {
                        listOf(
                            "edit", id, type, categoryName, amount, accountName, remark, tag,
                            date, dateTime, createdAt, updatedAt, source, sourceId,
                        )
                    }
                    is FinanceRoute.Stats -> listOf("stats")
                    is FinanceRoute.Budget -> listOf("budget")
                    is FinanceRoute.Import -> listOf("import")
                }
            },
            restore = { saved ->
                when (saved.firstOrNull() as? String) {
                    "add" -> FinanceRoute.AddTransaction(saved.getOrNull(1) as? String ?: "")
                    "edit" -> if (saved.size < 14) FinanceRoute.Main else FinanceRoute.EditTransaction(
                        Transaction(
                            id = saved[1] as Long,
                            type = saved[2] as Int,
                            categoryName = saved[3] as String,
                            amount = saved[4] as Double,
                            accountName = saved[5] as String,
                            remark = saved[6] as String,
                            tag = saved[7] as String,
                            date = saved[8] as String,
                            dateTime = saved[9] as String,
                            createdAt = saved[10] as Long,
                            updatedAt = saved[11] as Long,
                            source = saved[12] as String,
                            sourceId = saved[13] as String,
                        )
                    )
                    "stats" -> FinanceRoute.Stats
                    "budget" -> FinanceRoute.Budget
                    "import" -> FinanceRoute.Import
                    else -> FinanceRoute.Main
                }
            },
        )
    }
}

/** Tab 图标（选中态用实心图标，未选中用线描；与改造前一一对应）。 */
private fun AppTab.icon(selected: Boolean): ImageVector = when (this) {
    AppTab.OVERVIEW -> if (selected) Icons.Filled.DateRange else Icons.Outlined.DateRange
    AppTab.LIVE -> if (selected) Icons.Filled.Home else Icons.Outlined.Home
    AppTab.AD -> Icons.Outlined.Visibility
    AppTab.FINANCE -> if (selected) Icons.Filled.AddCircle else Icons.Outlined.AddCircle
    AppTab.TRANSFER -> Icons.Outlined.SwapHoriz
    AppTab.SETTINGS -> if (selected) Icons.Filled.Settings else Icons.Outlined.Settings
}
