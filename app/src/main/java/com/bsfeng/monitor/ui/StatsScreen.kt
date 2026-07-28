package com.bsfeng.monitor.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.domain.model.RecordType
import io.github.alexzhirkevich.cupertino.ExperimentalCupertinoApi
import io.github.alexzhirkevich.cupertino.section.SectionStyle
import io.github.alexzhirkevich.cupertino.section.section

@OptIn(ExperimentalCupertinoApi::class)
@Composable
fun StatsScreen(
    viewModel: MainViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.state.collectAsState()

    LaunchedEffect(Unit) {
        viewModel.loadData()
    }

    val grouped = remember(state.recentEvents) {
        state.recentEvents.groupBy { it.type::class.simpleName ?: "" }.entries.toList()
    }

    LazyColumn(modifier = modifier.fillMaxSize().padding(top = 8.dp)) {
        // 今日统计 — 总览
        item(key = "stats_title") {
            Text(
                "今日统计",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        section(
            style = SectionStyle.InsetGrouped,
        ) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("事件总数", fontSize = 15.sp)
                    val animatedEventCount by animateIntAsState(
                        targetValue = state.todayEventCount,
                        animationSpec = tween(durationMillis = 600)
                    )
                    Text(
                        "$animatedEventCount",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // App 使用时长排行
        item(key = "usage_header") {
            Text(
                "App 使用时长",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        section(
            style = SectionStyle.InsetGrouped,
        ) {
            if (state.appUsageStats.isEmpty()) {
                item {
                    Text(
                        "暂无数据",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            } else {
                state.appUsageStats.forEachIndexed { index, stat ->
                    item(key = "usage_$index") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // App 名
                            Text(
                                text = stat.appName,
                                fontSize = 15.sp,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                            // 使用时长
                            Text(
                                text = if (stat.totalMinutes > 0) "${stat.totalMinutes} 分钟" else "<1 分钟",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(12.dp))
                            // 打开次数
                            Text(
                                text = "${stat.openCount} 次",
                                fontSize = 12.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (index < state.appUsageStats.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                thickness = 0.5.dp
                            )
                        }
                    }
                }
            }
        }

        // 屏幕事件时间线
        item(key = "screen_header") {
            Text(
                "屏幕状态",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        section(
            style = SectionStyle.InsetGrouped,
        ) {
            if (state.screenEvents.isEmpty()) {
                item {
                    Text(
                        "暂无数据",
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                }
            } else {
                // 显示最新的 10 条屏幕事件（倒序，最新的在上）
                val displayScreens = state.screenEvents.reversed().take(10)
                displayScreens.forEachIndexed { index, record ->
                    item(key = "screen_$index") {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 时间
                            Text(
                                text = formatTime(record.timestamp),
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.width(60.dp)
                            )
                            // 状态
                            Text(
                                text = record.label,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Medium
                            )
                        }
                        if (index < displayScreens.lastIndex) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                thickness = 0.5.dp
                            )
                        }
                    }
                }
            }
        }

        // 事件类型分布
        item(key = "type_header") {
            Text(
                "事件类型分布",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
        section(
            style = SectionStyle.InsetGrouped,
        ) {
            grouped.forEach { (type, events) ->
                item(key = type) { _ ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(type, fontSize = 14.sp)
                        Text("${events.size} 次", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}

/**
 * 格式化时间戳为 HH:mm:ss。
 */
private fun formatTime(timestamp: Long): String {
    val cal = java.util.Calendar.getInstance().apply { timeInMillis = timestamp }
    return String.format("%02d:%02d:%02d", cal.get(java.util.Calendar.HOUR_OF_DAY),
        cal.get(java.util.Calendar.MINUTE), cal.get(java.util.Calendar.SECOND))
}
