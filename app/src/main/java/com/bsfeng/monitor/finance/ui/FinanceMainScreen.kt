package com.bsfeng.monitor.finance.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.finance.model.FinanceStats
import com.bsfeng.monitor.finance.model.Transaction
import io.github.alexzhirkevich.cupertino.ExperimentalCupertinoApi
import io.github.alexzhirkevich.cupertino.section.SectionStyle
import io.github.alexzhirkevich.cupertino.section.section

/**
 * 记账首页。
 */
@OptIn(ExperimentalCupertinoApi::class)
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
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("记账", fontSize = 34.sp, fontWeight = FontWeight.Bold)
                    if (monthDisplay.isNotEmpty()) {
                        Text(monthDisplay, fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 2.dp))
                    }
                }
            }

            // 月份切换
            item(key = "month_selector") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val prevInteractionSource = remember { MutableInteractionSource() }
                    val isPrevPressed by prevInteractionSource.collectIsPressedAsState()
                    val prevScale by animateFloatAsState(
                        targetValue = if (isPrevPressed) 0.85f else 1f,
                        animationSpec = spring(dampingRatio = 0.5f, stiffness = 700f)
                    )
                    TextButton(
                        onClick = { viewModel.previousMonth() },
                        modifier = Modifier.graphicsLayer { scaleX = prevScale; scaleY = prevScale },
                        interactionSource = prevInteractionSource
                    ) {
                        Text("◀", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                    }
                    Text(monthDisplay, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                    val nextInteractionSource = remember { MutableInteractionSource() }
                    val isNextPressed by nextInteractionSource.collectIsPressedAsState()
                    val nextScale by animateFloatAsState(
                        targetValue = if (isNextPressed) 0.85f else 1f,
                        animationSpec = spring(dampingRatio = 0.5f, stiffness = 700f)
                    )
                    TextButton(
                        onClick = { viewModel.nextMonth() },
                        modifier = Modifier.graphicsLayer { scaleX = nextScale; scaleY = nextScale },
                        interactionSource = nextInteractionSource
                    ) {
                        Text("▶", fontSize = 16.sp, color = MaterialTheme.colorScheme.primary)
                    }
                }
            }

            // 月度概览
            item(key = "overview") {
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        val animatedExpense by animateFloatAsState(
                            targetValue = stats.totalExpense.toFloat(),
                            animationSpec = tween(durationMillis = 600)
                        )
                        val animatedIncome by animateFloatAsState(
                            targetValue = stats.totalIncome.toFloat(),
                            animationSpec = tween(durationMillis = 600)
                        )
                        val animatedBalance by animateFloatAsState(
                            targetValue = (stats.totalIncome - stats.totalExpense).toFloat(),
                            animationSpec = tween(durationMillis = 600)
                        )
                        val animatedCount by animateIntAsState(
                            targetValue = stats.transactionCount,
                            animationSpec = tween(durationMillis = 600)
                        )
                        Text(
                            "¥${String.format("%.2f", animatedExpense.toDouble())}",
                            fontSize = 28.sp, fontWeight = FontWeight.Bold,
                            color = Color(0xFFFF3B30),
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Text(
                            "本月支出", fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.fillMaxWidth(),
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.height(16.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            StatItem("收入", "¥${String.format("%.2f", animatedIncome.toDouble())}", Color(0xFF34C759))
                            StatItem("结余", "¥${String.format("%.2f", animatedBalance.toDouble())}", Color(0xFF007AFF))
                            StatItem("笔数", "${animatedCount}", Color(0xFF8E8E93))
                        }
                        if (stats.budgetProgress > 0f) {
                            Spacer(Modifier.height(12.dp))
                            Column {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text("预算", fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(
                                        "${(stats.budgetProgress * 100).toInt()}%",
                                        fontSize = 12.sp,
                                        color = if (stats.isOverBudget) Color(0xFFFF3B30) else Color(0xFF34C759)
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                LinearProgressIndicator(
                                    progress = { stats.budgetProgress },
                                    modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                                    color = if (stats.isOverBudget) Color(0xFFFF3B30) else Color(0xFF007AFF),
                                    trackColor = Color(0xFFE5E5EA)
                                )
                            }
                        }
                    }
                }
            }

            // 导入入口
            item(key = "import_btn") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onImportClick) {
                        Text("导入数据", fontSize = 13.sp)
                    }
                }
            }

            // 交易列表标题
            item(key = "list_header") {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("交易记录", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
                    Text("共 ${transactions.size} 笔", fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (loading) {
                item(key = "loading") {
                    Box(
                        modifier = Modifier.fillMaxWidth().padding(32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
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
                            fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            textAlign = TextAlign.Center, lineHeight = 22.sp
                        )
                    }
                }
            } else {
                groupedTransactions.forEach { (date, txs) ->
                    item(key = "date_$date") {
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp)
                        ) {
                            Text(date, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                        }
                    }
                    section(style = SectionStyle.InsetGrouped) {
                        txs.forEach { tx ->
                            item(key = tx.id) { _ ->
                                TransactionRowContent(transaction = tx)
                            }
                        }
                    }
                }
            }

            // 底部留白
            item(key = "bottom") { Spacer(Modifier.height(96.dp)) }
        }

        // FAB
        Box(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .windowInsetsBottomHeight(WindowInsets.navigationBars)
        ) {
            var fabPressed by remember { mutableStateOf(false) }
            val fabScale by animateFloatAsState(
                targetValue = if (fabPressed) 0.88f else 1f,
                animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f)
            )
            FloatingActionButton(
                onClick = onAddClick,
                modifier = Modifier
                    .padding(16.dp)
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
                containerColor = Color(0xFF007AFF),
                contentColor = Color.White,
                shape = RoundedCornerShape(28.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "添加")
            }
        }
    }
}

@Composable
private fun StatItem(label: String, value: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = color)
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** 单条交易行 */
@OptIn(ExperimentalCupertinoApi::class)
@Composable
private fun TransactionRowContent(transaction: Transaction) {
    val catColor = remember(transaction.categoryName) {
        stableCategoryColor(transaction.categoryName, transaction.type)
    }

    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        CategoryDot(
            categoryName = transaction.categoryName,
            color = catColor, size = 40
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(transaction.categoryName, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (transaction.remark.isNotEmpty()) {
                Text(transaction.remark, fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Row {
                if (transaction.accountName.isNotEmpty()) {
                    Text(transaction.accountName, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
                if (transaction.tag.isNotEmpty()) {
                    if (transaction.accountName.isNotEmpty()) {
                        Text(" · ", fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                    }
                    Text(transaction.tag, fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f))
                }
            }
        }
        Text(
            text = if (transaction.isExpense) "-¥${transaction.displayAmount}" else "+¥${transaction.displayAmount}",
            fontSize = 15.sp, fontWeight = FontWeight.Medium,
            color = if (transaction.isExpense) MaterialTheme.colorScheme.onSurface
            else Color(0xFF34C759)
        )
    }
}

/** 基于分类名哈希的稳定颜色 */
private fun stableCategoryColor(categoryName: String, type: Int): Color {
    if (type == 1) return Color(0xFF34C759)
    if (categoryName.isBlank()) return Color(0xFF8E8E93)
    val hash = categoryName.hashCode() and 0x7FFFFFFF
    return categoryPalette[hash % categoryPalette.size]
}
