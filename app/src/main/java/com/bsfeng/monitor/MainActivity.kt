package com.bsfeng.monitor

import android.app.NotificationManager
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.network.EventReporter
import com.bsfeng.monitor.service.ReceiverRegistrar
import com.bsfeng.monitor.service.scheduleAlarmCheck
import com.bsfeng.monitor.ui.MainScreen
import com.bsfeng.monitor.ui.MainViewModel
import com.bsfeng.monitor.ui.SettingsScreen
import com.bsfeng.monitor.ui.StatsScreen
import com.bsfeng.monitor.util.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private sealed class Screen(val title: String, val icon: ImageVector) {
    data object Home : Screen("实时", Icons.Default.Home)
    data object Stats : Screen("统计", Icons.Default.Info)
    data object Settings : Screen("设置", Icons.Default.Settings)
}

class MainActivity : ComponentActivity() {
    private val screens = listOf(Screen.Home, Screen.Stats, Screen.Settings)
    private var registrar: ReceiverRegistrar? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (!nm.areNotificationsEnabled()) {
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), 1001)
        }

        registrar = ReceiverRegistrar(this).also { it.registerAll() }
        scheduleAlarmCheck(this)

        // 启动时注册设备（如无Token）+ 触发首次数据上报
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                if (AppUtils.getToken() == null) {
                    EventReporter(this@MainActivity).registerDevice()
                }
                // 无论是否刚注册，都尝试上报一次积压事件
                EventReporter(this@MainActivity).uploadBatch()
            }
        }

        setContent {
            MaterialTheme {
                val viewModel: MainViewModel = viewModel()
                var selectedScreen by remember { mutableStateOf<Screen>(Screen.Home) }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            screens.forEach { screen ->
                                NavigationBarItem(
                                    selected = selectedScreen == screen,
                                    onClick = { selectedScreen = screen },
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
