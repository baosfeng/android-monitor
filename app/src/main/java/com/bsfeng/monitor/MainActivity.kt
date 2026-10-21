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

    /** 从通知点进来的目标 Tab（默认 0 = 概览）。 */
    private var startTab by mutableIntStateOf(0)

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        startTab = intent.getIntExtra(EXTRA_START_TAB, startTab)
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
                val transferViewModel: TransferViewModel = viewModel()
                val lifecycleOwner = LocalLifecycleOwner.current
                val financeViewModel: FinanceViewModel = viewModel()
                val skipRuleViewModel: SkipRuleViewModel = viewModel()
                val whitelistViewModel: WhitelistViewModel = viewModel()
                val tokens = LocalDesignTokens.current
                // 6 Tab：0 概览(s01) / 1 实时(s02) / 2 广告(s03) / 3 记账(s04) / 4 传送(局域网互传) / 5 设置(s09)
                var selectedTab by remember { mutableIntStateOf(0) }
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
                val financeSubPage = selectedTab == FINANCE_TAB && financeRoute != FinanceRoute.Main
                val bottomBarVisible = !overlayOpen && !showSplash && !financeSubPage

                // 「后台接收」关闭时：前台启动监听、后台停服务（ServiceModePolicy 的 UI 侧接线）
                DisposableEffect(lifecycleOwner, transferViewModel) {
                    val observer = LifecycleEventObserver { _, event ->
                        when (event) {
                            // 只更新可见性：接收由用户显式开启（磁贴 / 传送页），App 打开不自动启动
                            Lifecycle.Event.ON_START -> transferViewModel.onAppForeground()
                            Lifecycle.Event.ON_STOP -> transferViewModel.onAppBackground()
                            else -> Unit
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }
                // 记账子页面（统计/预算/编辑/导入）下按系统返回键回到记账首页
                BackHandler(enabled = selectedTab == FINANCE_TAB && financeRoute != FinanceRoute.Main) {
                    financeRoute = FinanceRoute.Main
                    financeEntryKey++
                }

                val tabs = remember {
                    listOf(
                        TabItem("概览", Icons.Outlined.DateRange, Icons.Filled.DateRange),
                        TabItem("实时", Icons.Outlined.Home, Icons.Filled.Home),
                        TabItem("广告", Icons.Outlined.Visibility, Icons.Outlined.Visibility),
                        TabItem("记账", Icons.Outlined.AddCircle, Icons.Filled.AddCircle),
                        TabItem("传送", Icons.Outlined.SwapHoriz, Icons.Outlined.SwapHoriz),
                        TabItem("设置", Icons.Outlined.Settings, Icons.Filled.Settings),
                    )
                }

                // 通知点击「直达传送页」
                LaunchedEffect(startTab) {
                    if (startTab in tabs.indices) selectedTab = startTab
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
                                    tabs.forEachIndexed { index, tab ->
                                        val isSelected = selectedTab == index
                                        val tabColor by animateColorAsState(
                                            targetValue = if (isSelected) tokens.accent else tokens.muted,
                                            animationSpec = tokens.springOf<Color>(MotionToken.State),
                                        )
                                        NavigationBarItem(
                                            colors = barColors,
                                            selected = isSelected,
                                            onClick = {
                                                selectedTab = index
                                                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                                if (index == FINANCE_TAB) {
                                                    financeRoute = FinanceRoute.Main
                                                    financeEntryKey++
                                                }
                                            },
                                            icon = {
                                                Icon(
                                                    if (isSelected) tab.selectedIcon else tab.icon,
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
                                0 -> OverviewScreen(
                                    onOpenLive = { selectedTab = LIVE_TAB },
                                    onOpenFinance = { selectedTab = FINANCE_TAB },
                                    modifier = Modifier.fillMaxSize()
                                )
                                LIVE_TAB -> MainScreen(
                                    viewModel = viewModel,
                                    modifier = Modifier.fillMaxSize()
                                )
                                AD_TAB -> AdScreen(
                                    onOpenRules = { showSkipRuleScreen = true },
                                    adViewModel = viewModel(),
                                    modifier = Modifier.fillMaxSize()
                                )
                                TRANSFER_TAB -> TransferScreen(modifier = Modifier.fillMaxSize())
                                FINANCE_TAB -> {
                                    when (val route = financeRoute) {
                                        is FinanceRoute.Main -> FinanceMainScreen(
                                            viewModel = financeViewModel,
                                            entryKey = financeEntryKey,
                                            onAddClick = {
                                                financeRoute = FinanceRoute.AddTransaction(
                                                    financeViewModel.currentMonth.value
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
                                            categories = financeViewModel.categories.collectAsState().value,
                                            accounts = financeViewModel.accounts.collectAsState().value,
                                            // 记账首页当前浏览月（W2 的 AddTransactionScreen.fromMonth）；形如 "2026-07"
                                            fromMonth = route.fromMonth,
                                            onSave = { tx ->
                                                financeViewModel.addTransaction(tx)
                                                financeRoute = FinanceRoute.Main
                                            },
                                            onDismiss = { financeRoute = FinanceRoute.Main; financeEntryKey++ }
                                        )
                                        is FinanceRoute.EditTransaction -> AddTransactionScreen(
                                            categories = financeViewModel.categories.collectAsState().value,
                                            accounts = financeViewModel.accounts.collectAsState().value,
                                            editing = route.transaction,
                                            onSave = { tx ->
                                                financeViewModel.updateTransaction(tx)
                                                financeRoute = FinanceRoute.Main
                                            },
                                            onDelete = { tx ->
                                                financeViewModel.deleteTransaction(tx.id)
                                                financeRoute = FinanceRoute.Main
                                            },
                                            onDismiss = { financeRoute = FinanceRoute.Main; financeEntryKey++ }
                                        )
                                        is FinanceRoute.Stats -> FinanceStatsScreen(
                                            currentMonth = financeViewModel.currentMonth.collectAsState().value,
                                            stats = financeViewModel.monthlyStats.collectAsState().value,
                                            categoryBreakdown = financeViewModel.categoryBreakdown.collectAsState().value,
                                            onPreviousMonth = { financeViewModel.previousMonth() },
                                            onNextMonth = { financeViewModel.nextMonth() },
                                            onBack = { financeRoute = FinanceRoute.Main; financeEntryKey++ },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        is FinanceRoute.Budget -> BudgetScreen(
                                            viewModel = financeViewModel,
                                            onBack = { financeRoute = FinanceRoute.Main; financeEntryKey++ },
                                            modifier = Modifier.fillMaxSize()
                                        )
                                        is FinanceRoute.Import -> ImportScreen(
                                            importing = financeViewModel.importing.collectAsState().value,
                                            importResult = financeViewModel.importResult.collectAsState().value,
                                            onImport = { path -> financeViewModel.importFromPanda(path) },
                                            onDismiss = { financeRoute = FinanceRoute.Main; financeEntryKey++ },
                                            onClearResult = { financeViewModel.clearImportResult() }
                                        )
                                    }
                                }
                                SETTINGS_TAB -> SettingsScreen(
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
                    AnimatedVisibility(
                        visible = showSkipRuleScreen,
                        enter = slideInHorizontally() + fadeIn(),
                        exit = slideOutHorizontally() + fadeOut()
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            BackHandler {
                                showSkipRuleScreen = false
                                skipRuleViewModel.refresh()
                            }
                            SkipRuleScreen(
                                onBack = {
                                    showSkipRuleScreen = false
                                    skipRuleViewModel.refresh()
                                },
                                viewModel = skipRuleViewModel
                            )
                        }
                    }

                    // 广告白名单页面（覆盖层）— 从右侧滑入
                    AnimatedVisibility(
                        visible = showWhitelistScreen,
                        enter = slideInHorizontally() + fadeIn(),
                        exit = slideOutHorizontally() + fadeOut()
                    ) {
                        Box(Modifier.fillMaxSize()) {
                            BackHandler(showWhitelistScreen) {
                                showWhitelistScreen = false
                                whitelistViewModel.refresh()
                            }
                            WhitelistScreen(
                                onBack = {
                                    showWhitelistScreen = false
                                    whitelistViewModel.refresh()
                                },
                                viewModel = whitelistViewModel
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

private data class TabItem(
    val title: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)
