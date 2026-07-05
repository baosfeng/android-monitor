package com.bsfeng.monitor.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.data.EventEntity
import com.bsfeng.monitor.event.EventType

@Composable
fun MainScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()
    val isPaused by viewModel.isPaused.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadData()
    }

    Column(modifier = modifier.fillMaxSize()) {
        // 状态栏
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isPaused) "⏸ 已暂停" else "⚫ 监控中",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { viewModel.togglePause() }) {
                            Text(if (isPaused) "恢复" else "暂停")
                        }
                        OutlinedButton(onClick = { viewModel.clearEvents() }) {
                            Text("清空")
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                val accStatus = if (state.accessibilityEnabled) "✅ 无障碍已开启" else "❌ 无障碍未开启"
                Text(accStatus, fontSize = 13.sp)
                Spacer(Modifier.height(4.dp))
                Text("今日事件: ${state.todayEventCount}")
            }
        }

        // 最近事件列表
        Text(
            "最近事件",
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            items(state.recentEvents) { event ->
                EventCard(event = event)
            }
        }
    }
}

@Composable
fun EventCard(event: EventEntity) {
    var expanded by remember { mutableStateOf(false) }

    // 从 extraJson 中提取关键信息
    val extraObj = remember(event.extraJson) { parseExtraJson(event.extraJson) }
    val navPath = remember(extraObj) { extraObj.optString("nav_path", "").ifBlank { null } }
    val pageTitle = remember(extraObj) { extraObj.optString("page_title", "").ifBlank { null } }
    val toActivityName = remember(extraObj) { extraObj.optString("to_activity_name", "").ifBlank { null } }
    val appName = remember(extraObj) { extraObj.optString("app_name", "").ifBlank { null } }
    val fromPageTitle = remember(extraObj) { extraObj.optString("from_page_title", "").ifBlank { null } }

    // 事件类型对应的 emoji 图标
    val eventIcon = when (event.eventType) {
        "APP_FOREGROUND" -> "📱"
        "ACTIVITY_CHANGE" -> "🔄"
        "VIEW_CLICKED" -> "👆"
        "NAVIGATION_CHANGE" -> "📍"
        "RETURN_TO_PAGE" -> "🔙"
        else -> "📋"
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp)
            .clickable { expanded = !expanded }
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // 时间 + 事件类型
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = eventIcon, fontSize = 14.sp)
                Spacer(Modifier.width(4.dp))
                Text(
                    text = formatTime(event.timestampMs),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    EventType.getChineseName(event.eventType),
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                // 停留时长
                val durationText = extractDuration(event.extraJson)
                if (durationText != null) {
                    Text(
                        " · $durationText",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.weight(1f))
                Text(
                    if (expanded) "▲" else "▼",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
            Spacer(Modifier.height(4.dp))

            // 面包屑导航路径（最突出显示）
            if (navPath != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "📍 ", fontSize = 13.sp)
                    Text(
                        text = navPath,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Spacer(Modifier.height(2.dp))
            }

            // 页面标题/内容标题（如果有）
            val contentTitle = pageTitle ?: toActivityName
            if (contentTitle != null && (navPath == null || !navPath.contains(contentTitle))) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "📄 ", fontSize = 12.sp)
                    Text(
                        text = contentTitle,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Spacer(Modifier.height(2.dp))
            }

            // 页面关键内容文本（content_text_1 ~ _8）
            val contentTexts = extractContentTexts(extraObj)
            if (contentTexts.isNotEmpty() && (contentTitle == null || !contentTexts.contains(contentTitle))) {
                for ((index, text) in contentTexts.withIndex()) {
                    if (index >= 3) break // 最多显示3行
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(text = "📝 ", fontSize = 10.sp)
                        Text(
                            text = text,
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1
                        )
                    }
                }
                Spacer(Modifier.height(2.dp))
            }

            // 应用名（如果有，且未被面包屑覆盖）
            if (appName != null && (navPath == null || !navPath.startsWith(appName))) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "📌 ", fontSize = 11.sp)
                    Text(
                        text = appName,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.height(2.dp))
            }

            // 原始摘要（技术性，灰色小字）
            val rawSummary = event.summary.ifBlank { null }
            if (rawSummary != null && (navPath == null || !navPath.equals(rawSummary, ignoreCase = true))
                && (contentTitle == null || !rawSummary.contains(contentTitle))) {
                Text(
                    text = rawSummary,
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                    maxLines = 2,
                    lineHeight = 14.sp
                )
                Spacer(Modifier.height(2.dp))
            }

            // 来源提示（从哪来）
            if (fromPageTitle != null && fromPageTitle != pageTitle) {
                Text(
                    text = "← $fromPageTitle",
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }

            // 展开的技术详情
            if (expanded && event.extraJson != "{}" && event.extraJson.isNotBlank()) {
                HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
                val lines = formatExtraJson(event.extraJson)
                lines.forEach { line ->
                    Text(
                        text = line,
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
                    )
                    Spacer(Modifier.height(2.dp))
                }
            }
        }
    }
}

private fun parseExtraJson(extraJson: String): org.json.JSONObject {
    return try {
        org.json.JSONObject(extraJson)
    } catch (_: Exception) {
        org.json.JSONObject()
    }
}

/** 从 extraJson 中提取 content_text_1 ~ _8 */
private fun extractContentTexts(obj: org.json.JSONObject): List<String> {
    val texts = mutableListOf<String>()
    for (i in 1..8) {
        val key = "content_text_$i"
        val value = obj.optString(key, "")
        if (value.isNotBlank() && value != "null") {
            texts.add(value)
        }
    }
    return texts
}

private fun extractDuration(extraJson: String): String? {
    if (extraJson.isBlank() || extraJson == "{}") return null
    return try {
        val obj = org.json.JSONObject(extraJson)
        val ms = obj.optLong("foreground_duration_ms", -1L)
        if (ms <= 0) return null
        val seconds = ms / 1000
        val minutes = seconds / 60
        val hours = minutes / 60
        when {
            hours > 0 -> "${hours}小时${minutes % 60}分钟"
            minutes > 0 -> "${minutes}分钟${seconds % 60}秒"
            seconds >= 1 -> "${seconds}秒"
            else -> null
        }
    } catch (_: Exception) { null }
}

private fun formatExtraJson(extraJson: String): List<String> {
    if (extraJson.isBlank() || extraJson == "{}") return emptyList()
    return try {
        val obj = org.json.JSONObject(extraJson)
        val lines = mutableListOf<String>()
        val fieldNames = mapOf(
            "package_name" to "包名",
            "app_name" to "应用名称",
            "from_package" to "从(包名)",
            "from_activity" to "从(页面)",
            "to_class" to "到(类名)",
            "activity_class" to "页面类名",
            "activity_name" to "页面名称",
            "page_title" to "页面标题",
            "from_page_title" to "上一级页面标题",
            "nav_path" to "导航路径",
            "nav_depth" to "导航深度",
            "nav_index" to "导航索引",
            "foreground_duration_ms" to "停留时长(ms)",
            "from_app_name" to "从(应用)",
            "from_activity_name" to "从(页面描述)",
            "to_activity_name" to "到(页面描述)",
            "nav_path" to "导航路径"
        )
        for (key in obj.keys()) {
            val value = obj.optString(key, "")
            if (value.isNotBlank() && value != "null") {
                val label = fieldNames[key] ?: key
                lines.add("$label: $value")
            }
        }
        lines.ifEmpty { listOf(extraJson) }
    } catch (_: Exception) { listOf(extraJson) }
}

private fun formatTime(ms: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = ms }
    return String.format("%02d:%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND))
}
