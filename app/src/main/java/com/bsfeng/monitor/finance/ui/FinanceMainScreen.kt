package com.bsfeng.monitor.finance.ui

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.FinanceStats
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.theme.CategoryDot
import com.bsfeng.monitor.ui.theme.FinanceColors
import com.bsfeng.monitor.ui.theme.IOSColors
import com.bsfeng.monitor.ui.theme.IOSDivider
import com.bsfeng.monitor.ui.theme.IOSRow
import com.bsfeng.monitor.ui.theme.InsetGroupedCard
import com.bsfeng.monitor.ui.theme.MonitorIconSize
import com.bsfeng.monitor.ui.theme.MonitorSpacing
import com.bsfeng.monitor.ui.theme.Radius
import com.bsfeng.monitor.ui.theme.categoryPalette

/**
 * 记账首页。
 */
@Composable
fun FinanceMainScreen(
    viewModel: FinanceViewModel,
    onAddClick: () -> Unit,
    onImportClick: () -> Unit,
    onEditClick: (Transaction) -> Unit = {},
    onStatsClick: () -> Unit = {},
    onBudgetClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    val view = LocalView.current
    val currentMonth by viewModel.currentMonth.collectAsState()
    val transactions by viewModel.monthlyTransactions.collectAsState()
    val stats by viewModel.monthlyStats.collectAsState()
    val loading by viewModel.loading.collectAsState()

    val monthDisplay = remember(currentMonth) {
        viewModel.getMonthDisplayName(currentMonth)
    }

    val groupedTransactions = remember(transactions) {
        viewModel.getTransactionsGroupedByDate(transactions)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // 大标题
            item(key = "title") {
                Column(modifier = Modifier.padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm)) {
                    Text(
                        "记账",
                        style = MaterialTheme.typography.headlineLarge,
                    )
                    if (monthDisplay.isNotEmpty()) {
                        Text(
                            monthDisplay,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = MonitorSpacing.xxs),
                        )
                    }
                }
            }

            // 月份切换
            item(key = "month_selector") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.xs),
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
                        onClick = { viewModel.previousMonth() },
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
                        onClick = { viewModel.nextMonth() },
                        modifier = Modifier.graphicsLayer { scaleX = nextScale; scaleY = nextScale },
                        interactionSource = nextInteractionSource,
                    ) {
                        Text("▶", style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // 月度概览
            item(key = "overview") {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm),
                    shape = RoundedCornerShape(Radius.lg),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp),
                ) {
                    Column(modifier = Modifier.padding(MonitorSpacing.lg)) {
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
                        val animatedCount by animateIntAsState(
                            targetValue = stats.transactionCount,
                            animationSpec = tween(durationMillis = 600),
                        )
                        Text(
                            "¥${String.format("%.2f", animatedExpense.toDouble())}",
                            style = MaterialTheme.typography.headlineMedium,
                            color = FinanceColors.expense,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                        Text(
                            "本月支出",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center,
                        )
                        Spacer(Modifier.height(MonitorSpacing.lg))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                        ) {
                            StatItem("收入", "¥${String.format("%.2f", animatedIncome.toDouble())}", FinanceColors.income)
                            StatItem("结余", "¥${String.format("%.2f", animatedBalance.toDouble())}", FinanceColors.budget)
                            StatItem("笔数", "${animatedCount}", IOSColors.gray)
                        }
                        if (stats.budgetProgress > 0f) {
                            Spacer(Modifier.height(MonitorSpacing.md))
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                ) {
                                    Text("预算",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        "${(stats.budgetProgress * 100).toInt()}%",
                                        style = MaterialTheme.typography.labelMedium,
                                        color = if (stats.isOverBudget) FinanceColors.expense else FinanceColors.income,
                                    )
                                }
                                Spacer(Modifier.height(MonitorSpacing.xs))
                                LinearProgressIndicator(
                                    progress = { stats.budgetProgress },
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(Radius.xxs)),
                                    color = if (stats.isOverBudget) FinanceColors.expense else FinanceColors.budget,
                                    trackColor = IOSColors.lightGray,
                                )
                            }
                        }
                    }
                }
            }

            // 功能入口：统计分析 / 预算设置
            item(key = "entries") {
                InsetGroupedCard {
                    IOSRow(
                        title = "统计分析",
                        subtitle = "月度收支与分类占比",
                        trailing = { EntryChevron() },
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            onStatsClick()
                        },
                    )
                    IOSDivider()
                    IOSRow(
                        title = "预算设置",
                        subtitle = "本月总预算与分类预算",
                        trailing = { EntryChevron() },
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            onBudgetClick()
                        },
                    )
                }
            }

            // 导入入口
            item(key = "import_btn") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.xs),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onImportClick) {
                        Text("导入数据", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            // 交易列表标题
            item(key = "list_header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("交易记录",
                        style = MaterialTheme.typography.headlineSmall,
                    )
                    Text("共 ${transactions.size} 笔",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (loading) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(MonitorSpacing.xxxl),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(MonitorIconSize.lg),
                            strokeWidth = 2.dp,
                        )
                    }
                }
            } else if (transactions.isEmpty()) {
                item(key = "empty") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(MonitorSpacing.huge),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            "暂无交易记录\n点击右下角 + 添加第一笔",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center,
                            lineHeight = MaterialTheme.typography.titleSmall.lineHeight,
                        )
                    }
                }
            } else {
                groupedTransactions.forEach { (date, txs) ->
                    item(key = "date_$date") {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = MonitorSpacing.lg, end = MonitorSpacing.lg,
                                    top = MonitorSpacing.md, bottom = MonitorSpacing.sm.minus(2.dp)),
                        ) {
                            Text(date,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.SemiBold)
                        }
                    }
                    item {
                        InsetGroupedCard {
                            txs.forEach { tx ->
                                TransactionRowContent(
                                    transaction = tx,
                                    onClick = { onEditClick(tx) },
                                )
                                if (tx != txs.last()) {
                                    HorizontalDivider(
                                        thickness = 0.5.dp,
                                        color = MaterialTheme.colorScheme.outlineVariant,
                                        modifier = Modifier.padding(start = MonitorSpacing.lg),
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // 底部留白
            item(key = "bottom") { Spacer(Modifier.height(96.dp)) }
        }

        // FAB
        //
        // ⚠️ 不要用 windowInsetsBottomHeight(...) 在外面包一层 Box：
        // 该容器高度≈导航栏 inset，FAB 会溢出到内容区之外并被底部导航栏盖住，
        // 导致「看不见 + 点不到」，整个记账入口报废（2026-10 真机验证踩坑）。
        var fabPressed by remember { mutableStateOf(false) }
        val fabScale by animateFloatAsState(
            targetValue = if (fabPressed) 0.88f else 1f,
            animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f),
        )
        FloatingActionButton(
            onClick = onAddClick,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(MonitorSpacing.lg)
                .graphicsLayer { scaleX = fabScale; scaleY = fabScale }
                .pointerInput(Unit) {
                    detectTapGestures(
                        onPress = {
                            fabPressed = true
                            tryAwaitRelease()
                            fabPressed = false
                        }
                    )
                },
            containerColor = FinanceColors.budget,
            contentColor = Color.White,
            shape = RoundedCornerShape(28.dp),
        ) {
            Icon(Icons.Default.Add, contentDescription = "添加")
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold, color = color)
        Text(label, style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 单条交易行（点击进入编辑） */
@Composable
private fun TransactionRowContent(transaction: Transaction, onClick: () -> Unit) {
    val catColor = remember(transaction.categoryName) {
        stableCategoryColor(transaction.categoryName, transaction.type)
    }
    val view = LocalView.current

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                onClick()
            }
            .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm.plus(2.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CategoryDot(
            categoryName = transaction.categoryName,
            color = catColor, size = 40.dp,
        )
        Spacer(Modifier.width(MonitorSpacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Text(transaction.categoryName,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Medium)
            if (transaction.remark.isNotEmpty()) {
                Text(transaction.remark,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Row {
                if (transaction.accountName.isNotEmpty()) {
                    Text(transaction.accountName,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
                if (transaction.tag.isNotEmpty()) {
                    if (transaction.accountName.isNotEmpty()) {
                        Text(" · ",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                    Text(transaction.tag,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        }
        // 退款（负数支出）按收入色与 + 号展示，避免出现 "-¥-12.00"
        val amountIsPositive = !transaction.isExpense || transaction.isRefundEntry
        Text(
            text = if (amountIsPositive) "+¥${transaction.displayAmount}" else "-¥${transaction.displayAmount}",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Medium,
            color = if (amountIsPositive) FinanceColors.income
            else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/** 入口行右侧箭头 */
@Composable
private fun EntryChevron() {
    Icon(
        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(MonitorIconSize.md),
    )
}

/** 基于分类名哈希的稳定颜色 */
private fun stableCategoryColor(categoryName: String, type: Int): Color {
    if (type == 1) return FinanceColors.income
    if (categoryName.isBlank()) return IOSColors.gray
    val hash = categoryName.hashCode() and 0x7FFFFFFF
    return categoryPalette[hash % categoryPalette.size]
}
