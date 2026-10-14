package com.bsfeng.monitor.finance.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.ui.theme.FinanceColors
import com.bsfeng.monitor.ui.theme.IOSColors
import com.bsfeng.monitor.ui.theme.InsetGroupedCard
import com.bsfeng.monitor.ui.theme.MonitorSpacing
import com.bsfeng.monitor.ui.theme.Radius
import com.bsfeng.monitor.ui.theme.categoryPalette
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * 添加 / 编辑交易页面 — iOS 风格表单。
 *
 * @param editing 非空表示编辑已有交易：表单预填该交易，保存时保留 id/createdAt/source，
 *                并允许通过 [onDelete] 删除；为空表示新增。
 */
@Composable
fun AddTransactionScreen(
    categories: List<Category>,
    accounts: List<Account>,
    onSave: (Transaction) -> Unit,
    onDismiss: () -> Unit,
    editing: Transaction? = null,
    onDelete: ((Transaction) -> Unit)? = null,
) {
    val editingId = editing?.id
    val isEditing = editing != null
    // 退款（负数支出）编辑时金额按绝对值展示，保存时还原负号
    val isRefundEntry = editing?.isRefundEntry == true

    var txType by remember(editingId) { mutableStateOf(editing?.type ?: TYPE_EXPENSE) }
    var amountText by remember(editingId) {
        mutableStateOf(
            editing?.let { String.format(Locale.CHINA, "%.2f", kotlin.math.abs(it.amount)) } ?: ""
        )
    }
    var selectedCategory by remember(editingId) { mutableStateOf(editing?.categoryName ?: "") }
    var selectedAccount by remember(editingId) { mutableStateOf(editing?.accountName ?: "") }
    var remark by remember(editingId) { mutableStateOf(editing?.remark ?: "") }
    var selectedTag by remember(editingId) { mutableStateOf(editing?.tag ?: "") }
    var dateText by remember(editingId) {
        mutableStateOf(editing?.date?.takeIf { it.isNotBlank() } ?: todayText())
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showRemarkEditor by remember { mutableStateOf(false) }
    var showDateEditor by remember { mutableStateOf(false) }

    val filteredCategories = remember(categories, txType) {
        categories.filter { it.type == txType }
    }

    LaunchedEffect(filteredCategories, accounts) {
        if (selectedCategory.isEmpty() && filteredCategories.isNotEmpty()) {
            selectedCategory = filteredCategories.first().name
        }
        if (selectedAccount.isEmpty() && accounts.isNotEmpty()) {
            selectedAccount = accounts.first().name
        }
    }

    val amount = amountText.toDoubleOrNull() ?: 0.0
    val canSave = amount > 0 && selectedCategory.isNotEmpty()

    fun buildTransaction(): Transaction {
        val magnitude = amountText.toDoubleOrNull() ?: 0.0
        val signedAmount = if (isRefundEntry) -magnitude else magnitude
        val now = System.currentTimeMillis()
        val dateTime = Transaction.buildDateTime(dateText)
        val base = editing
        return if (base != null) {
            base.copy(
                type = txType,
                categoryName = selectedCategory,
                amount = signedAmount,
                accountName = selectedAccount,
                remark = remark,
                tag = selectedTag,
                date = dateText,
                dateTime = dateTime,
                updatedAt = now,
            )
        } else {
            Transaction(
                type = txType,
                categoryName = selectedCategory,
                amount = signedAmount,
                accountName = selectedAccount,
                remark = remark,
                tag = selectedTag,
                date = dateText,
                dateTime = dateTime,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        // 导航栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = MonitorSpacing.sm, vertical = MonitorSpacing.sm),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "取消",
                    tint = MaterialTheme.colorScheme.primary)
            }
            Text(
                if (isEditing) "编辑交易" else "新增交易",
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(
                onClick = { if (canSave) onSave(buildTransaction()) },
                enabled = canSave,
            ) {
                Icon(Icons.Default.Check, contentDescription = "保存",
                    tint = if (canSave) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // 金额输入
            item(key = "amount") {
                Spacer(Modifier.height(MonitorSpacing.lg))
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = MonitorSpacing.lg),
                    contentAlignment = Alignment.Center,
                ) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                        placeholder = { Text("0.00",
                            style = MaterialTheme.typography.headlineLarge.copy(
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f))
                        ) },
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = MaterialTheme.typography.headlineLarge.fontSize,
                            fontWeight = FontWeight.Bold,
                            color = if (txType == TYPE_EXPENSE) FinanceColors.expense else FinanceColors.income,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent,
                        ),
                    )
                }
                if (isRefundEntry) {
                    // 退款交易的金额是负数存储，编辑时按绝对值输入
                    Text(
                        "退款交易：保存后按负金额存储，用于抵扣当月支出",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = MonitorSpacing.lg),
                    )
                }
                Spacer(Modifier.height(MonitorSpacing.sm))
            }

            // 类型切换
            item(key = "type_switch") {
                SegmentedControl(
                    options = listOf("支出", "收入"),
                    selectedIndex = txType,
                    onSelect = { txType = it }
                )
            }

            // 日期 + 备注（均可点击编辑）
            item(key = "date_remark_spacer") {
                Spacer(Modifier.height(MonitorSpacing.sm))
            }

            item(key = "date_remark") {
                InsetGroupedCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showDateEditor = true }
                            .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.md),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("日期", style = MaterialTheme.typography.titleSmall)
                        Text(dateText, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = MonitorSpacing.lg),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { showRemarkEditor = true }
                            .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.md),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("备注", style = MaterialTheme.typography.titleSmall)
                        Text(remark.ifBlank { "添加备注" }, style = MaterialTheme.typography.titleSmall,
                            color = if (remark.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 分类选择
            item(key = "category_header") {
                Spacer(Modifier.height(MonitorSpacing.sm))
                Text(
                    "选择分类",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm),
                )
            }

            item(key = "category_grid") {
                InsetGroupedCard {
                    val chunked = filteredCategories.chunked(4)
                    Column(modifier = Modifier.padding(MonitorSpacing.sm)) {
                        for (row in chunked) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly,
                            ) {
                                for (cat in row) {
                                    val isSelected = cat.name == selectedCategory
                                    val catInteractionSource = remember(cat.name) { MutableInteractionSource() }
                                    val isCatPressed by catInteractionSource.collectIsPressedAsState()
                                    val catScale by animateFloatAsState(
                                        targetValue = if (isCatPressed) 0.85f else 1f,
                                        animationSpec = spring(dampingRatio = 0.4f, stiffness = 600f),
                                    )
                                    Column(
                                        modifier = Modifier
                                            .clickable(
                                                interactionSource = catInteractionSource,
                                                indication = null,
                                            ) { selectedCategory = cat.name }
                                            .padding(MonitorSpacing.xs)
                                            .graphicsLayer {
                                                scaleX = catScale
                                                scaleY = catScale
                                            },
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(24.dp))
                                                .background(
                                                    if (isSelected) getCategoryColorStatic(cat.name)
                                                    else IOSColors.lightGray
                                                ),
                                            contentAlignment = Alignment.Center,
                                        ) {
                                            Text(
                                                text = cat.name.take(1),
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold,
                                                style = MaterialTheme.typography.bodyMedium,
                                            )
                                        }
                                        Spacer(Modifier.height(MonitorSpacing.xs))
                                        Text(
                                            text = cat.name,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                        )
                                    }
                                }
                                val remaining = 4 - row.size
                                repeat(remaining) { Spacer(Modifier.weight(1f)) }
                            }
                        }
                    }
                }
            }

            // 账户选择
            item(key = "account_header") {
                Spacer(Modifier.height(MonitorSpacing.sm))
                Text(
                    "选择账户",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm),
                )
            }
            item(key = "account_list") {
                InsetGroupedCard {
                    accounts.forEach { account ->
                        val isSelected = account.name == selectedAccount
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedAccount = account.name }
                                .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.md),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                account.name,
                                style = MaterialTheme.typography.titleSmall,
                                color = if (isSelected) MaterialTheme.colorScheme.primary
                                else MaterialTheme.colorScheme.onSurface,
                            )
                            Text("¥${account.displayBalance}", style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (account != accounts.last()) {
                            HorizontalDivider(
                                thickness = 0.5.dp,
                                color = MaterialTheme.colorScheme.outlineVariant,
                                modifier = Modifier.padding(start = MonitorSpacing.lg),
                            )
                        }
                    }
                }
            }

            // 保存按钮
            item(key = "save") {
                Spacer(Modifier.height(MonitorSpacing.lg))
                val saveInteractionSource = remember { MutableInteractionSource() }
                val isSavePressed by saveInteractionSource.collectIsPressedAsState()
                val saveScale by animateFloatAsState(
                    targetValue = if (isSavePressed) 0.95f else 1f,
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f),
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = MonitorSpacing.lg)
                        .clip(RoundedCornerShape(Radius.md))
                        .graphicsLayer { scaleX = saveScale; scaleY = saveScale }
                        .background(
                            if (canSave) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        ),
                    contentAlignment = Alignment.Center,
                ) {
                    TextButton(
                        onClick = { if (canSave) onSave(buildTransaction()) },
                        enabled = canSave,
                        interactionSource = saveInteractionSource,
                    ) {
                        Text(
                            if (isEditing) "保存修改" else "保存",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }

            // 删除（仅编辑态）
            if (isEditing && onDelete != null) {
                item(key = "delete") {
                    Spacer(Modifier.height(MonitorSpacing.md))
                    val deleteInteractionSource = remember { MutableInteractionSource() }
                    val isDeletePressed by deleteInteractionSource.collectIsPressedAsState()
                    val deleteScale by animateFloatAsState(
                        targetValue = if (isDeletePressed) 0.95f else 1f,
                        animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f),
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MonitorSpacing.lg)
                            .clip(RoundedCornerShape(Radius.md))
                            .graphicsLayer { scaleX = deleteScale; scaleY = deleteScale }
                            .background(FinanceColors.expense.copy(alpha = 0.12f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        TextButton(
                            onClick = { showDeleteConfirm = true },
                            interactionSource = deleteInteractionSource,
                        ) {
                            Text(
                                "删除这笔交易",
                                color = FinanceColors.expense,
                                fontWeight = FontWeight.SemiBold,
                            )
                        }
                    }
                }
            }

            item(key = "bottom") { Spacer(Modifier.height(MonitorSpacing.xxxl)) }
        }
    }

    // 备注编辑器
    if (showRemarkEditor) {
        FinanceInputDialog(
            title = "备注",
            initialValue = remark,
            placeholder = "例如：和朋友聚餐",
            onConfirm = {
                remark = it
                showRemarkEditor = false
            },
            onDismiss = { showRemarkEditor = false },
        )
    }

    // 日期编辑器
    if (showDateEditor) {
        FinanceInputDialog(
            title = "日期",
            initialValue = dateText,
            placeholder = "yyyy-MM-dd",
            errorMessage = "日期格式应为 yyyy-MM-dd",
            isValid = { text -> runCatching { LocalDate.parse(text.trim()) }.isSuccess },
            onConfirm = {
                dateText = it.trim()
                showDateEditor = false
            },
            onDismiss = { showDateEditor = false },
        )
    }

    // 删除确认
    if (showDeleteConfirm && editing != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            shape = RoundedCornerShape(Radius.xl),
            title = { Text("删除这笔交易？", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "${editing.categoryName} ¥${editing.displayAmount}，删除后无法恢复。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete(editing)
                }) {
                    Text("删除", color = FinanceColors.expense, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
        )
    }
}

/** 分段控制器（支出/收入） */
@Composable
private fun SegmentedControl(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.sm)
            .height(32.dp)
            .clip(RoundedCornerShape(Radius.sm))
            .background(IOSColors.lightGray),
        horizontalArrangement = Arrangement.SpaceEvenly,
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selectedIndex
            val segScale by animateFloatAsState(
                targetValue = if (isSelected) 1f else 0.95f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f),
            )
            Box(
                modifier = Modifier
                    .weight(1f).fillMaxHeight()
                    .then(if (isSelected) Modifier
                        .graphicsLayer { scaleX = segScale; scaleY = segScale }
                        .clip(RoundedCornerShape(7.dp))
                        .background(MaterialTheme.colorScheme.surface) else Modifier
                            .graphicsLayer { scaleX = segScale; scaleY = segScale })
                    .clickable { onSelect(index) },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = option,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

/** 稳定分类颜色 */
private fun getCategoryColorStatic(name: String): Color {
    if (name.isBlank()) return IOSColors.gray
    val hash = name.hashCode() and 0x7FFFFFFF
    return categoryPalette[hash % categoryPalette.size]
}

/** 今日日期 yyyy-MM-dd */
private fun todayText(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

/** 交易类型：支出 */
private const val TYPE_EXPENSE = 0
