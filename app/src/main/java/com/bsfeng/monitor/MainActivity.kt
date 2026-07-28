package com.bsfeng.monitor

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import android.view.HapticFeedbackConstants
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.spring
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
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.outlined.AddCircle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Visibility
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.activity.compose.BackHandler
import com.bsfeng.monitor.finance.ui.AddTransactionScreen
import com.bsfeng.monitor.finance.ui.FinanceMainScreen
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
import com.bsfeng.monitor.ui.theme.MonitorTheme
import androidx.compose.ui.graphics.vector.ImageVector

class MainActivity : ComponentActivity() {

    private sealed class FinanceRoute {
        data object Main : FinanceRoute()
        data class AddTransaction(val fromMonth: String) : FinanceRoute()
        data class Stats(val fromMonth: String) : FinanceRoute()
        data object Import : FinanceRoute()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        setContent {
            MonitorTheme {
                val viewModel: MainViewModel = viewModel()
                val financeViewModel: FinanceViewModel = viewModel()
                val skipRuleViewModel: SkipRuleViewModel = viewModel()
                val whitelistViewModel: WhitelistViewModel = viewModel()
                var selectedTab by remember { mutableIntStateOf(0) }
                var financeRoute by remember { mutableStateOf<FinanceRoute>(FinanceRoute.Main) }
                var showSkipRuleScreen by remember { mutableStateOf(false) }
                var showWhitelistScreen by remember { mutableStateOf(false) }
                val view = LocalView.current

                val tabs = remember {
                    listOf(
                        TabItem("实时", Icons.Outlined.Home, Icons.Filled.Home),
                        TabItem("广告", Icons.Outlined.Visibility, Icons.Outlined.Visibility),
                        TabItem("记账", Icons.Outlined.AddCircle, Icons.Filled.AddCircle),
                        TabItem("设置", Icons.Outlined.Settings, Icons.Filled.Settings),
                    )
                }

                Scaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        if (!showSkipRuleScreen && !showWhitelistScreen) {
                            NavigationBar {
                                tabs.forEachIndexed { index, tab ->
                                    val isSelected = selectedTab == index
                                    val tabColor by animateColorAsState(
                                        targetValue = if (isSelected) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurfaceVariant,
                                        animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
                                    )
                                    NavigationBarItem(
                                        selected = isSelected,
                                        onClick = {
                                            selectedTab = index
                                            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                                            if (index == 2) {
                                                financeRoute = FinanceRoute.Main
                                            }
                                        },
                                        icon = {
                                            Icon(
                                                if (isSelected) tab.selectedIcon else tab.icon,
                                                contentDescription = tab.title,
                                                modifier = Modifier.size(24.dp),
                                                tint = tabColor,
                                            )
                                        },
                                        label = {
                                            Text(tab.title, color = tabColor)
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
                            0 -> MainScreen(
                                viewModel = viewModel,
                                modifier = Modifier.fillMaxSize()
                            )
                            1 -> AdScreen(
                                adViewModel = viewModel(),
                                modifier = Modifier.fillMaxSize()
                            )
                            2 -> {
                                when (val route = financeRoute) {
                                    is FinanceRoute.Main -> FinanceMainScreen(
                                        viewModel = financeViewModel,
                                        onAddClick = {
                                            financeRoute = FinanceRoute.AddTransaction(
                                                financeViewModel.currentMonth.value
                                            )
                                        },
                                        onImportClick = {
                                            financeRoute = FinanceRoute.Import
                                        },
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    is FinanceRoute.AddTransaction -> AddTransactionScreen(
                                        categories = financeViewModel.categories.collectAsState().value,
                                        accounts = financeViewModel.accounts.collectAsState().value,
                                        onSave = { tx ->
                                            financeViewModel.addTransaction(tx)
                                            financeRoute = FinanceRoute.Main
                                        },
                                        onDismiss = { financeRoute = FinanceRoute.Main }
                                    )
                                    is FinanceRoute.Import -> ImportScreen(
                                        importing = financeViewModel.importing.collectAsState().value,
                                        importResult = financeViewModel.importResult.collectAsState().value,
                                        onImport = { path -> financeViewModel.importFromPanda(path) },
                                        onDismiss = { financeRoute = FinanceRoute.Main },
                                        onClearResult = { financeViewModel.clearImportResult() }
                                    )
                                    else -> {}
                                }
                            }
                            3 -> SettingsScreen(
                                viewModel = viewModel,
                                onNavigateToSkipRules = { showSkipRuleScreen = true },
                                onNavigateToWhitelist = { showWhitelistScreen = true },
                                modifier = Modifier.fillMaxSize()
                            )
                        }

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
                    }
                }
            }
        }
    }
}

private data class TabItem(
    val title: String,
    val icon: ImageVector,
    val selectedIcon: ImageVector,
)
