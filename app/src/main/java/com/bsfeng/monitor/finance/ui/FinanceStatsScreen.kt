package com.bsfeng.monitor.finance.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.FinanceStats
import com.bsfeng.monitor.ui.theme.FinanceColors
import com.bsfeng.monitor.ui.theme.IOSColors
import com.bsfeng.monitor.ui.theme.IOSNavigationBar
import com.bsfeng.monitor.ui.theme.InsetGroupedCard
import com.bsfeng.monitor.ui.theme.MonitorSpacing
import com.bsfeng.monitor.ui.theme.Radius

/**
 * 记账统计页面。
 * 月度概览 + 分类支出环形图。
 */
@Composable
fun FinanceStatsScreen(
    currentMonth: String,
    stats: FinanceStats,
    categoryBreakdown: Map<String, Double>,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val monthDisplay = currentMonth.replace("-", "年") + "月"
    val total = categoryBreakdown.values.sum()
    val sortedEntries = remember(categoryBreakdown) {
        categoryBreakdown.entries
            .sortedByDescending { it.value }
            .take(10)
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 导航栏
        item(key = "navbar") {
            IOSNavigationBar(title = "统计", onBack = onBack)
        }

        // 月份切换
        item {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = MonitorSpacing.lg),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val prevInteractionSource = remember { MutableInteractionSource() }
                val isPrevPressed by prevInteractionSource.collectIsPressedAsState()
                val prevScale by animateFloatAsState(
                    targetValue = if (isPrevPressed) 0.85f else 1f,
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 700f),
                )
                TextButton(
                    onClick = onPreviousMonth,
                    modifier = Modifier.graphicsLayer { scaleX = prevScale; scaleY = prevScale },
                    interactionSource = prevInteractionSource,
                ) {
                    Text("◀", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
                Text(monthDisplay, style = MaterialTheme.typography.titleLarge)
                val nextInteractionSource = remember { MutableInteractionSource() }
                val isNextPressed by nextInteractionSource.collectIsPressedAsState()
                val nextScale by animateFloatAsState(
                    targetValue = if (isNextPressed) 0.85f else 1f,
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 700f),
                )
                TextButton(
                    onClick = onNextMonth,
                    modifier = Modifier.graphicsLayer { scaleX = nextScale; scaleY = nextScale },
                    interactionSource = nextInteractionSource,
                ) {
                    Text("▶", style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary)
                }
            }
        }

        // 收支卡片
        item {
            val animatedExpense by animateFloatAsState(
                targetValue = stats.totalExpense.toFloat(),
                animationSpec = tween(durationMillis = 600),
            )
            val animatedIncome by animateFloatAsState(
                targetValue = stats.totalIncome.toFloat(),
                animationSpec = tween(durationMillis = 600),
            )
            val animatedBalance by animateFloatAsState(
                targetValue = (stats.totalIncome - stats.totalExpense).toFloat(),
                animationSpec = tween(durationMillis = 600),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(MonitorSpacing.md),
            ) {
                StatCard(
                    title = "支出",
                    amount = "¥${String.format("%.2f", animatedExpense.toDouble())}",
                    color = FinanceColors.expense, modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "收入",
                    amount = "¥${String.format("%.2f", animatedIncome.toDouble())}",
                    color = FinanceColors.income, modifier = Modifier.weight(1f)
                )
                StatCard(
                    title = "结余",
                    amount = "¥${String.format("%.2f", animatedBalance.toDouble())}",
                    color = if (stats.balance >= 0) FinanceColors.budget else FinanceColors.expense,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 分类支出环形图
        if (categoryBreakdown.isNotEmpty()) {
            item {
                Spacer(Modifier.height(MonitorSpacing.sm))
            }
            item {
                InsetGroupedCard {
                    Text(
                        "支出分类占比",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(MonitorSpacing.lg, MonitorSpacing.lg, MonitorSpacing.lg, MonitorSpacing.sm),
                    )

                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .padding(MonitorSpacing.lg),
                        contentAlignment = Alignment.Center,
                    ) {
                        DonutChart(data = categoryBreakdown, total = total)
                    }

                    sortedEntries.forEachIndexed { index, entry ->
                        val color = getChartColor(index)
                        val percent = if (total > 0) (entry.value / total * 100).toInt() else 0
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm.plus(2.dp)),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(RoundedCornerShape(Radius.xxs))
                                        .background(color)
                                )
                                Spacer(Modifier.width(MonitorSpacing.sm))
                                Text(entry.key, style = MaterialTheme.typography.titleSmall)
                            }
                            Text(
                                "¥${String.format("%.0f", entry.value)} ($percent%)",
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (index < sortedEntries.lastIndex) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(start = MonitorSpacing.lg),
                            )
                        }
                    }
                }
            }
            item { Spacer(Modifier.height(MonitorSpacing.xxl)) }
        } else {
            item {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(MonitorSpacing.huge),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "暂无本月的分类数据",
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }

        item { Spacer(Modifier.height(MonitorSpacing.xxxl)) }
    }
}

@Composable
private fun StatCard(
    title: String,
    amount: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(Radius.lg),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(MonitorSpacing.md),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(amount, style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = color, maxLines = 1)
            Spacer(Modifier.height(MonitorSpacing.xxs))
            Text(title, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 环形图（Compose Canvas 自绘），带旋转展开入场动画 */
@Composable
private fun DonutChart(data: Map<String, Double>, total: Double) {
    val animationProgress = remember { Animatable(0f) }
    LaunchedEffect(data, total) {
        animationProgress.snapTo(0f)
        animationProgress.animateTo(1f,
            animationSpec = tween(durationMillis = 800, easing = FastOutSlowInEasing)
        )
    }

    Canvas(modifier = Modifier.size(150.dp)) {
        if (total <= 0) return@Canvas
        val strokeWidth = 30f
        val radius = (size.minDimension - strokeWidth) / 2
        val center = Offset(size.width / 2, size.height / 2)
        val arcSize = Size(radius * 2, radius * 2)
        var startAngle = -90f
        val sorted = data.entries.sortedByDescending { it.value }
        for ((index, entry) in sorted.withIndex()) {
            val sweepAngle = (entry.value / total * 360).toFloat() * animationProgress.value
            drawArc(
                color = getChartColor(index),
                startAngle = startAngle, sweepAngle = sweepAngle,
                useCenter = false,
                topLeft = Offset(center.x - radius, center.y - radius),
                size = arcSize, style = Stroke(width = strokeWidth),
            )
            startAngle += sweepAngle
        }
    }
}

private val chartColors = listOf(
    IOSColors.red, IOSColors.orange, IOSColors.yellow,
    IOSColors.green, IOSColors.lightBlue, IOSColors.blue,
    IOSColors.purple, IOSColors.pink, IOSColors.gray,
    IOSColors.indigo,
)

private fun getChartColor(index: Int): Color = chartColors[index % chartColors.size]
