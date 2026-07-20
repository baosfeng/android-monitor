package com.bsfeng.monitor

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.finance.ui.FinanceMainScreen
import com.bsfeng.monitor.finance.ui.AddTransactionScreen
import com.bsfeng.monitor.finance.ui.FinanceStatsScreen
import com.bsfeng.monitor.finance.ui.ImportScreen
import com.bsfeng.monitor.finance.ui.FinanceViewModel as FinanceViewModel
import com.bsfeng.monitor.service.ReceiverRegistrar
import com.bsfeng.monitor.ui.MainScreen
import com.bsfeng.monitor.ui.MainViewModel
import com.bsfeng.monitor.ui.SettingsScreen
import com.bsfeng.monitor.ui.StatsScreen

private sealed class Screen(val title: String, val icon: ImageVector) {
    data object Home : Screen("实时", Icons.Default.Home)
    data object Stats : Screen("统计", Icons.Default.Info)
    data object Finance : Screen("记账", Icons.Default.Star)
    data object Settings : Screen("设置", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {
    private val screens = listOf(Screen.Home, Screen.Stats, Screen.Finance, Screen.Settings)
    private var registrar: ReceiverRegistrar? = null

    // 记账内部导航
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
            MaterialTheme {
                val viewModel: MainViewModel = viewModel()
                val financeViewModel: FinanceViewModel = viewModel()
                var selectedScreen by remember { mutableStateOf<Screen>(Screen.Home) }
                // 记账内部路由
                var financeRoute by remember { mutableStateOf<FinanceRoute>(FinanceRoute.Main) }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            screens.forEach { screen ->
                                NavigationBarItem(
                                    selected = selectedScreen == screen,
                                    onClick = {
                                        selectedScreen = screen
                                        // 切回记账时重置到主页
                                        if (screen is Screen.Finance) {
                                            financeRoute = FinanceRoute.Main
                                        }
                                    },
                                    icon = { Icon(screen.icon, contentDescription = screen.title) },
                                    label = { Text(screen.title) }
                                )
                            }
                        }
                    }
                ) { paddingValues ->
                    when (selectedScreen) {
                        Screen.Home -> MainScreen(
                            viewModel = viewModel,
                            modifier = Modifier.padding(paddingValues)
                        )
                        Screen.Stats -> StatsScreen(
                            viewModel = viewModel,
                            modifier = Modifier.padding(paddingValues)
                        )
                        Screen.Finance -> {
                            when (financeRoute) {
                                is FinanceRoute.Main -> FinanceMainScreen(
                                    viewModel = financeViewModel,
                                    onAddClick = {
                                        financeRoute = FinanceRoute.AddTransaction(financeViewModel.currentMonth.value)
                                    },
                                    onImportClick = {
                                        financeRoute = FinanceRoute.Import
                                    },
                                    modifier = Modifier.padding(paddingValues)
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
                                    onClearResult = { financeViewModel.clearImportResult() },
                                    modifier = Modifier.padding(paddingValues)
                                )
                                else -> {}
                            }
                        }
                        Screen.Settings -> SettingsScreen(
                            viewModel = viewModel,
                            modifier = Modifier.padding(paddingValues)
                        )
                    }
                }
            }
        }
    }
}
