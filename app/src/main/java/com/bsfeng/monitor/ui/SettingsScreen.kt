package com.bsfeng.monitor.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.util.AppUtils

@Composable
fun SettingsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val state by viewModel.state.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()

    Column(modifier = modifier.fillMaxSize().padding(16.dp)) {
        Text("设置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(16.dp))

        // 无障碍服务状态
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("无障碍服务", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                val accEnabled = state.accessibilityEnabled
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = if (accEnabled) "✅ 已开启" else "❌ 未开启",
                        fontSize = 14.sp,
                        color = if (accEnabled) MaterialTheme.colorScheme.primary
                               else MaterialTheme.colorScheme.error
                    )
                }
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }) {
                    Text(if (accEnabled) "无障碍设置" else "前往设置开启")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 监控开关
        Card(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("监控开关", fontWeight = FontWeight.Bold)
                    Text(if (isPaused) "已暂停" else "运行中", fontSize = 14.sp)
                }
                Button(onClick = { viewModel.togglePause() }) {
                    Text(if (isPaused) "恢复" else "暂停")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 电池优化
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("电池优化", fontWeight = FontWeight.Bold)
                Text("将 App 设为无限制以防止被系统关闭", fontSize = 14.sp)
                Spacer(Modifier.height(8.dp))
                Button(onClick = {
                    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                    context.startActivity(intent)
                }) {
                    Text("前往设置")
                }
            }
        }

        Spacer(Modifier.height(12.dp))

        // 关于信息
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("关于", fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Text("版本: 1.0.0", fontSize = 14.sp)
                Text("设备 ID: ${viewModel.getDeviceId().take(8)}...", fontSize = 14.sp)
                Text("包名: com.bsfeng.monitor", fontSize = 14.sp)
            }
        }
    }
}
