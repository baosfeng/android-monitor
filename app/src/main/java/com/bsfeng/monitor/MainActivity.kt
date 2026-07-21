package com.bsfeng.monitor

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.finance.ui.AddTransactionScreen
import com.bsfeng.monitor.finance.ui.FinanceMainScreen
import com.bsfeng.monitor.finance.ui.ImportScreen
import com.bsfeng.monitor.finance.ui.FinanceViewModel as FinanceViewModel
import com.bsfeng.monitor.service.ReceiverRegistrar
import com.bsfeng.monitor.ui.MainScreen
import com.bsfeng.monitor.ui.MainViewModel
import com.bsfeng.monitor.ui.SettingsScreen
import com.bsfeng.monitor.ui.SkipRuleScreen
import com.bsfeng.monitor.ui.SkipRuleViewModel
import com.bsfeng.monitor.ui.StatsScreen
import io.github.alexzhirkevich.cupertino.CupertinoNavigationBar
import io.github.alexzhirkevich.cupertino.CupertinoNavigationBarItem
import io.github.alexzhirkevich.cupertino.CupertinoScaffold
import io.github.alexzhirkevich.cupertino.ExperimentalCupertinoApi
import io.github.alexzhirkevich.cupertino.icons.CupertinoIcons
import io.github.alexzhirkevich.cupertino.icons.outlined.*
import io.github.alexzhirkevich.cupertino.icons.filled.*
import io.github.alexzhirkevich.cupertino.theme.CupertinoTheme

@OptIn(ExperimentalCupertinoApi::class)
class MainActivity : ComponentActivity() {
    private var registrar: ReceiverRegistrar? = null

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

        registrar = ReceiverRegistrar(this).also { it.registerAll() }

        setContent {
            CupertinoTheme {
                val viewModel: MainViewModel = viewModel()
                val financeViewModel: FinanceViewModel = viewModel()
                val skipRuleViewModel: SkipRuleViewModel = viewModel()
                var selectedTab by remember { mutableIntStateOf(0) }
                var financeRoute by remember { mutableStateOf<FinanceRoute>(FinanceRoute.Main) }
                var showSkipRuleScreen by remember { mutableStateOf(false) }

                val tabs = remember {
                    listOf(
                        TabItem("实时", CupertinoIcons.Outlined.House, CupertinoIcons.Filled.House),
                        TabItem("统计", CupertinoIcons.Outlined.ListBullet, CupertinoIcons.Filled.Star),
                        TabItem("记账", CupertinoIcons.Outlined.PlusCircle, CupertinoIcons.Filled.PlusCircle),
                        TabItem("设置", CupertinoIcons.Outlined.Gearshape, CupertinoIcons.Filled.Gearshape),
                    )
                }

                CupertinoScaffold(
                    modifier = Modifier.fillMaxSize(),
                    bottomBar = {
                        CupertinoNavigationBar(
                            modifier = Modifier.fillMaxWidth(),
                            windowInsets = WindowInsets(0, 0, 0, 0),
                        ) {
                            tabs.forEachIndexed { index, tab ->
                                CupertinoNavigationBarItem(
                                    selected = selectedTab == index,
                                    onClick = {
                                        selectedTab = index
                                        if (index == 2) {
                                            financeRoute = FinanceRoute.Main
                                        }
                                    },
                                    icon = {
                                        Icon(
                                            if (selectedTab == index) tab.selectedIcon else tab.icon,
                                            contentDescription = tab.title,
                                            modifier = Modifier.size(24.dp)
                                        )
                                    },
                                    label = { Text(tab.title) },
                                )
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
                            1 -> StatsScreen(
                                viewModel = viewModel,
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
                                modifier = Modifier.fillMaxSize()
                            )
                        }

                        // 开屏跳过规则管理页面（覆盖层）
                        if (showSkipRuleScreen) {
                            SkipRuleScreen(
                                onBack = {
                                    showSkipRuleScreen = false
                                    skipRuleViewModel.refresh()
                                },
                                viewModel = skipRuleViewModel
                            )
                        }
                    }
                }
            }
        }
    }
}

private data class TabItem(
    val title: String,
    val icon: androidx.compose.ui.graphics.vector.ImageVector,
    val selectedIcon: androidx.compose.ui.graphics.vector.ImageVector,
)
