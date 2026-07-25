package com.bsfeng.monitor.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.domain.model.EventType
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
        state.recentEvents.groupBy { it.eventType }.entries.toList()
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
                    Text(
                        state.todayEventCount.toString(),
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
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
                        Text(EventType.getChineseName(type), fontSize = 14.sp)
                        Text("${events.size} 次", fontSize = 14.sp, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
    }
}
