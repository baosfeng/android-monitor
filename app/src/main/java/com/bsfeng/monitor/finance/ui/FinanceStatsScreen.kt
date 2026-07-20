package com.bsfeng.monitor.finance.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.finance.model.FinanceStats

/**
 * 记账统计页面 — iOS 风格。
 * 包含月度概览 + 分类支出环形图。
 */
@Composable
fun FinanceStatsScreen(
    currentMonth: String,
    stats: FinanceStats,
    categoryBreakdown: Map<String, Double>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    modifier: Modifier = Modifier
) {
    val monthDisplay = currentMonth.replace("-", "年") + "月"

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(iOSColors.systemBackground)
    ) {
        // 大标题
        item {
            iOSLargeTitle(title = "统计")
        }

        // 月份切换
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(onClick = onPreviousMonth) { Text("◀", fontSize = 16.sp, color = iOSColors.blue) }
                Text(monthDisplay, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = iOSColors.labelPrimary)
                TextButton(onClick = onNextMonth) { Text("▶", fontSize = 16.sp, color = iOSColors.blue) }
            }
        }

        // 收支卡片
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 支出卡
                StatCard(
                    title = "支出",
                    amount = "¥${stats.displayExpense}",
                    color = iOSColors.red,
                    modifier = Modifier.weight(1f)
                )
                // 收入卡
                StatCard(
                    title = "收入",
                    amount = "¥${stats.displayIncome}",
                    color = iOSColors.green,
                    modifier = Modifier.weight(1f)
                )
                // 结余卡
                StatCard(
                    title = "结余",
                    amount = "¥${stats.displayBalance}",
                    color = if (stats.balance >= 0) iOSColors.blue else iOSColors.red,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 分类支出环形图
        if (categoryBreakdown.isNotEmpty()) {
            item {
                Spacer(Modifier.height(8.dp))
                iOSGroupedList {
                    Text(
                        "支出分类占比",
                        fontSize = iOSFontSizes.subhead,
                        fontWeight = FontWeight.SemiBold,
                        color = iOSColors.labelPrimary,
                        modifier = Modifier.padding(16.dp, 16.dp, 16.dp, 8.dp)
                    )

                    // 环形图
                    val total = categoryBreakdown.values.sum()
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .padding(16.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        DonutChart(
                            data = categoryBreakdown,
                            total = total
                        )
                    }

                    // 分类列表
                    HorizontalDivider(color = iOSColors.gray6, thickness = 0.5.dp)

                    val sortedEntries = categoryBreakdown.entries
                        .sortedByDescending { it.value }
                        .take(10)

                    for ((index, entry) in sortedEntries.withIndex()) {
                        val color = getChartColor(index)
                        val percent = if (total > 0) (entry.value / total * 100).toInt() else 0
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(color)
                                )
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    entry.key,
                                    fontSize = iOSFontSizes.subhead,
                                    color = iOSColors.labelPrimary
                                )
                            }
                            Text(
                                "¥${String.format("%.0f", entry.value)} ($percent%)",
                                fontSize = iOSFontSizes.subhead,
                                color = iOSColors.labelSecondary
                            )
                        }
                        if (index < sortedEntries.size - 1) {
                            HorizontalDivider(
                                color = iOSColors.gray6,
                                thickness = 0.5.dp,
                                modifier = Modifier.padding(start = 32.dp)
                            )
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }
        } else {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(48.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "暂无本月的分类数据",
                        fontSize = iOSFontSizes.subhead,
                        color = iOSColors.labelTertiary,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

/** 统计小卡片 */
@Composable
private fun StatCard(
    title: String,
    amount: String,
    color: Color,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = iOSColors.groupedBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                amount,
                fontSize = iOSFontSizes.subhead,
                fontWeight = FontWeight.Bold,
                color = color,
                maxLines = 1
            )
            Spacer(Modifier.height(2.dp))
            Text(
                title,
                fontSize = iOSFontSizes.caption,
                color = iOSColors.labelSecondary
            )
        }
    }
}

/** 环形图（使用 Compose Canvas 自绘） */
@Composable
private fun DonutChart(
    data: Map<String, Double>,
    total: Double
) {
    Canvas(
        modifier = Modifier.size(150.dp)
    ) {
        if (total <= 0) return@Canvas

        val strokeWidth = 30f
        val radius = (size.minDimension - strokeWidth) / 2
        val center = Offset(size.width / 2, size.height / 2)
        val topLeft = Offset(
            center.x - radius,
            center.y - radius
        )
        val arcSize = Size(radius * 2, radius * 2)

        var startAngle = -90f // 从顶部开始

        val sorted = data.entries.sortedByDescending { it.value }
        for ((index, entry) in sorted.withIndex()) {
            val sweepAngle = (entry.value / total * 360).toFloat()
            drawArc(
                color = getChartColor(index),
                startAngle = startAngle,
                sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = topLeft,
                size = arcSize,
                style = Stroke(width = strokeWidth)
            )
            startAngle += sweepAngle
        }
    }
}

private val chartColors = listOf(
    Color(0xFFFF3B30), Color(0xFFFF9500), Color(0xFFFFCC00),
    Color(0xFF34C759), Color(0xFF5AC8FA), Color(0xFF007AFF),
    Color(0xFFAF52DE), Color(0xFFFF2D55), Color(0xFF8E8E93),
    Color(0xFF5856D6)
)

private fun getChartColor(index: Int): Color =
    chartColors[index % chartColors.size]
