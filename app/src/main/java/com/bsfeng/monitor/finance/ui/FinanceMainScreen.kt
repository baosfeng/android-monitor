package com.bsfeng.monitor.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.finance.model.FinanceStats
import com.bsfeng.monitor.finance.model.Transaction

/**
 * 记账首页 — iOS 风格。
 *
 * 性能优化说明：
 * - 分类颜色使用稳定哈希算法，无全局可变状态
 * - 分组结果使用 remember 缓存
 * - 派生值使用 derivedStateOf
 * - 列表 item 使用 key
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FinanceMainScreen(
    viewModel: FinanceViewModel,
    onAddClick: () -> Unit,
    onImportClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentMonth by viewModel.currentMonth.collectAsState()
    val transactions by viewModel.monthlyTransactions.collectAsState()
    val stats by viewModel.monthlyStats.collectAsState()
    val loading by viewModel.loading.collectAsState()

    // 缓存派生值：月份显示名
    val monthDisplay = remember(currentMonth) {
        viewModel.getMonthDisplayName(currentMonth)
    }

    // 缓存派生值：按日期分组（仅当 transactions 变化时重新计算）
    val groupedTransactions = remember(transactions) {
        viewModel.getTransactionsGroupedByDate(transactions)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(iOSColors.systemBackground)
    ) {
        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            // iOS 大标题
            item(key = "title") {
                iOSLargeTitle(
                    title = "记账",
                    subtitle = monthDisplay
                )
            }

            // 月份切换
            item(key = "month_selector") {
                MonthSelector(
                    currentMonth = monthDisplay,
                    onPrevious = { viewModel.previousMonth() },
                    onNext = { viewModel.nextMonth() },
                    onToday = { viewModel.goToCurrentMonth() }
                )
            }

            // 月度概览卡片
            item(key = "overview") {
                MonthlyOverviewCard(stats = stats)
            }

            // 导入入口
            item(key = "import_btn") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onImportClick) {
                        Text("导入数据", fontSize = iOSFontSizes.footnote)
                    }
                }
            }

            // 交易列表标题
            item(key = "list_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "交易记录",
                        fontSize = iOSFontSizes.title3,
                        fontWeight = FontWeight.SemiBold,
                        color = iOSColors.labelPrimary
                    )
                    Text(
                        "共 ${transactions.size} 笔",
                        fontSize = iOSFontSizes.footnote,
                        color = iOSColors.labelSecondary
                    )
                }
            }

            if (loading) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = iOSColors.gray,
                            strokeWidth = 2.dp
                        )
                    }
                }
            } else if (transactions.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(48.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            "暂无交易记录\n点击右下角 + 添加第一笔",
                            fontSize = iOSFontSizes.subhead,
                            color = iOSColors.labelTertiary,
                            textAlign = TextAlign.Center,
                            lineHeight = 22.sp
                        )
                    }
                }
            } else {
                // 按日期分组展示
                groupedTransactions.forEach { (date, txs) ->
                    item(key = "date_$date") {
                        DateSectionHeader(date = date, count = txs.size)
                    }
                    items(txs, key = { it.id }) { tx ->
                        TransactionRow(
                            transaction = tx,
                            onDelete = { viewModel.deleteTransaction(tx.id) }
                        )
                    }
                }
            }

            // 底部留白
            item(key = "bottom_spacer") { Spacer(Modifier.height(72.dp)) }
        }

        // FAB
        FloatingActionButton(
            onClick = onAddClick,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(16.dp),
            containerColor = iOSColors.blue,
            contentColor = Color.White,
            shape = RoundedCornerShape(28.dp)
        ) {
            Icon(Icons.Default.Add, contentDescription = "添加")
        }
    }
}

/** 月份切换器 */
@Composable
private fun MonthSelector(
    currentMonth: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onToday: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(onClick = onPrevious) {
            Text("◀", fontSize = 16.sp, color = iOSColors.blue)
        }
        Text(
            text = currentMonth,
            fontSize = iOSFontSizes.body,
            fontWeight = FontWeight.SemiBold,
            color = iOSColors.labelPrimary
        )
        TextButton(onClick = onNext) {
            Text("▶", fontSize = 16.sp, color = iOSColors.blue)
        }
    }
}

/** 月度概览卡片 */
@Composable
private fun MonthlyOverviewCard(stats: FinanceStats) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = iOSColors.groupedBackground),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "¥${stats.displayExpense}",
                fontSize = iOSFontSizes.title1,
                fontWeight = FontWeight.Bold,
                color = iOSColors.red,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )
            Text(
                text = "本月支出",
                fontSize = iOSFontSizes.footnote,
                color = iOSColors.labelSecondary,
                modifier = Modifier.fillMaxWidth(),
                textAlign = TextAlign.Center
            )

            Spacer(Modifier.height(16.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                StatItem(label = "收入", value = "¥${stats.displayIncome}", color = iOSColors.green)
                StatItem(label = "结余", value = "¥${stats.displayBalance}", color = iOSColors.blue)
                StatItem(label = "笔数", value = "${stats.transactionCount}", color = iOSColors.gray)
            }

            if (stats.budgetProgress > 0f) {
                Spacer(Modifier.height(12.dp))
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("预算", fontSize = iOSFontSizes.caption, color = iOSColors.labelSecondary)
                        Text(
                            "${(stats.budgetProgress * 100).toInt()}%",
                            fontSize = iOSFontSizes.caption,
                            color = if (stats.isOverBudget) iOSColors.red else iOSColors.green
                        )
                    }
                    Spacer(Modifier.height(4.dp))
                    LinearProgressIndicator(
                        progress = { stats.budgetProgress },
                        modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                        color = if (stats.isOverBudget) iOSColors.red else iOSColors.blue,
                        trackColor = iOSColors.gray5
                    )
                }
            }
        }
    }
}

/** 概览小项 */
@Composable
private fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            fontSize = iOSFontSizes.subhead,
            fontWeight = FontWeight.SemiBold,
            color = color
        )
        Text(
            text = label,
            fontSize = iOSFontSizes.caption,
            color = iOSColors.labelSecondary
        )
    }
}

/** 日期分组头部 */
@Composable
private fun DateSectionHeader(date: String, count: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)
    ) {
        Text(
            text = date,
            fontSize = iOSFontSizes.subhead,
            fontWeight = FontWeight.SemiBold,
            color = iOSColors.labelPrimary
        )
    }
}

/** 单条交易行 — 稳定 key + 稳定颜色映射 */
@Composable
private fun TransactionRow(
    transaction: Transaction,
    onDelete: () -> Unit
) {
    val catColor = remember(transaction.categoryName) {
        stableCategoryColor(transaction.categoryName, transaction.type)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CategoryDot(
            categoryName = transaction.categoryName,
            color = catColor,
            size = 40
        )

        Spacer(Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = transaction.categoryName,
                fontSize = iOSFontSizes.body,
                fontWeight = FontWeight.Medium,
                color = iOSColors.labelPrimary
            )
            if (transaction.remark.isNotEmpty()) {
                Text(
                    text = transaction.remark,
                    fontSize = iOSFontSizes.footnote,
                    color = iOSColors.labelSecondary,
                    maxLines = 1
                )
            }
            Row {
                if (transaction.accountName.isNotEmpty()) {
                    Text(
                        text = transaction.accountName,
                        fontSize = iOSFontSizes.caption,
                        color = iOSColors.labelTertiary
                    )
                }
                if (transaction.tag.isNotEmpty()) {
                    if (transaction.accountName.isNotEmpty()) {
                        Text(" · ", fontSize = iOSFontSizes.caption, color = iOSColors.labelTertiary)
                    }
                    Text(
                        text = transaction.tag,
                        fontSize = iOSFontSizes.caption,
                        color = iOSColors.labelTertiary
                    )
                }
            }
        }

        Text(
            text = if (transaction.isExpense) "-¥${transaction.displayAmount}" else "+¥${transaction.displayAmount}",
            fontSize = iOSFontSizes.body,
            fontWeight = FontWeight.Medium,
            color = if (transaction.isExpense) iOSColors.labelPrimary else iOSColors.green
        )
    }
}

// =============================================
// 稳定分类颜色算法（无全局可变状态）
// =============================================

/** 基于分类名哈希的稳定颜色（同一分类永远同色，无副作用） */
private fun stableCategoryColor(categoryName: String, type: Int): Color {
    if (type == 1) return iOSColors.green // 收入统一绿色
    if (categoryName.isBlank()) return iOSColors.gray
    val hash = categoryName.hashCode() and 0x7FFFFFFF
    return categoryPalette[hash % categoryPalette.size]
}
