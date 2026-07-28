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

/**
 * 添加交易页面 — iOS 风格表单。
 */
@Composable
fun AddTransactionScreen(
    categories: List<Category>,
    accounts: List<Account>,
    onSave: (Transaction) -> Unit,
    onDismiss: () -> Unit
) {
    var txType by remember { mutableStateOf(0) }
    var amountText by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("") }
    var selectedAccount by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))) }

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
                "新增交易",
                style = MaterialTheme.typography.titleLarge,
            )
            IconButton(
                onClick = {
                    val a = amountText.toDoubleOrNull() ?: return@IconButton
                    if (a <= 0 || selectedCategory.isEmpty()) return@IconButton
                    val now = System.currentTimeMillis()
                    onSave(Transaction(
                        type = txType, categoryName = selectedCategory,
                        amount = a, accountName = selectedAccount,
                        remark = remark, tag = selectedTag,
                        date = dateText,
                        dateTime = "$dateText ${LocalDate.now().format(DateTimeFormatter.ofPattern("HH:mm"))}",
                        createdAt = now, updatedAt = now
                    ))
                },
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
                            color = if (txType == 0) FinanceColors.expense else FinanceColors.income,
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

            // 日期 + 备注
            item(key = "date_remark") {
                Spacer(Modifier.height(MonitorSpacing.sm))
            }

            item {
                InsetGroupedCard {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.md),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("日期", style = MaterialTheme.typography.titleSmall)
                        Text(dateText, style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    HorizontalDivider(
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                        modifier = Modifier.padding(start = MonitorSpacing.lg),
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
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

            item {
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
            item {
                InsetGroupedCard {
                    accounts.forEach { account ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = MonitorSpacing.lg, vertical = MonitorSpacing.md),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(account.name, style = MaterialTheme.typography.titleSmall)
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
                        onClick = {
                            val a = amountText.toDoubleOrNull() ?: return@TextButton
                            if (a <= 0 || selectedCategory.isEmpty()) return@TextButton
                            val now = System.currentTimeMillis()
                            onSave(Transaction(
                                type = txType, categoryName = selectedCategory,
                                amount = a, accountName = selectedAccount,
                                remark = remark, tag = selectedTag,
                                date = dateText,
                                dateTime = "$dateText ${LocalDate.now().format(DateTimeFormatter.ofPattern("HH:mm"))}",
                                createdAt = now, updatedAt = now
                            ))
                        },
                        enabled = canSave,
                        interactionSource = saveInteractionSource,
                    ) {
                        Text("保存", color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(MonitorSpacing.xxxl))
            }
        }
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
