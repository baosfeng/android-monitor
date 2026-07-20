package com.bsfeng.monitor.finance.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.finance.model.Account
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 添加交易页面 — iOS 风格表单。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddTransactionScreen(
    categories: List<Category>,
    accounts: List<Account>,
    onSave: (Transaction) -> Unit,
    onDismiss: () -> Unit
) {
    // 表单状态
    var txType by remember { mutableStateOf(0) } // 0=支出, 1=收入
    var amountText by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("") }
    var selectedAccount by remember { mutableStateOf("") }
    var remark by remember { mutableStateOf("") }
    var selectedTag by remember { mutableStateOf("") }
    var dateText by remember { mutableStateOf(LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))) }

    // 筛选当前类型的分类
    val filteredCategories = remember(categories, txType) {
        categories.filter { it.type == txType }
    }

    // 默认选择第一个分类和账户
    LaunchedEffect(filteredCategories, accounts) {
        if (selectedCategory.isEmpty() && filteredCategories.isNotEmpty()) {
            selectedCategory = filteredCategories.first().name
        }
        if (selectedAccount.isEmpty() && accounts.isNotEmpty()) {
            selectedAccount = accounts.first().name
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(iOSColors.systemBackground)
    ) {
        // iOS 风格导航栏
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(iOSColors.groupedBackground)
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "取消", tint = iOSColors.blue)
            }
            Text(
                "新增交易",
                fontSize = 17.sp,
                fontWeight = FontWeight.SemiBold,
                color = iOSColors.labelPrimary
            )
            TextButton(
                onClick = {
                    val amount = amountText.toDoubleOrNull() ?: return@TextButton
                    if (amount <= 0) return@TextButton
                    if (selectedCategory.isEmpty()) return@TextButton
                    val now = System.currentTimeMillis()
                    onSave(
                        Transaction(
                            type = txType,
                            categoryName = selectedCategory,
                            amount = amount,
                            accountName = selectedAccount,
                            remark = remark,
                            tag = selectedTag,
                            date = dateText,
                            dateTime = "$dateText ${LocalDate.now().format(DateTimeFormatter.ofPattern("HH:mm"))}",
                            createdAt = now,
                            updatedAt = now
                        )
                    )
                }
            ) {
                Icon(Icons.Default.Check, contentDescription = "保存", tint = if (amountText.toDoubleOrNull() ?: 0.0 > 0) iOSColors.blue else iOSColors.gray3)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize()
        ) {
            // 金额输入
            item {
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                        placeholder = { Text("0.00", fontSize = 40.sp, color = iOSColors.gray3) },
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 40.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (txType == 0) iOSColors.red else iOSColors.green,
                            textAlign = TextAlign.Center
                        ),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        )
                    )
                }
                Spacer(Modifier.height(8.dp))
            }

            // 类型切换（iOS SegmentedControl）
            item {
                iOSSegmentedControl(
                    options = listOf("支出", "收入"),
                    selectedIndex = txType,
                    onSelect = { txType = it }
                )
            }

            // 日期
            item {
                Spacer(Modifier.height(8.dp))
                iOSGroupedList {
                    iOSListRow(label = "日期", value = dateText, showArrow = true) // TODO: DatePicker
                    HorizontalDivider(color = iOSColors.gray6, thickness = 0.5.dp)
                    iOSListRow(label = "备注", value = remark.ifBlank { "添加备注" }, showArrow = true)
                }
                Spacer(Modifier.height(16.dp))
            }

            // 分类选择
            item {
                Text(
                    "选择分类",
                    fontSize = iOSFontSizes.subhead,
                    fontWeight = FontWeight.SemiBold,
                    color = iOSColors.labelSecondary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            // 分类网格
            item {
                val chunked = filteredCategories.chunked(4)
                for (row in chunked) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        for (cat in row) {
                            val isSelected = cat.name == selectedCategory
                            Column(
                                modifier = Modifier
                                    .clickable { selectedCategory = cat.name }
                                    .padding(4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(48.dp)
                                        .clip(RoundedCornerShape(24.dp))
                                        .background(
                                            if (isSelected) getCategoryColorStatic(cat.name) else iOSColors.gray5
                                        ),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = cat.name.take(1),
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 16.sp
                                    )
                                }
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    text = cat.name,
                                    fontSize = 11.sp,
                                    color = if (isSelected) iOSColors.blue else iOSColors.labelSecondary,
                                    maxLines = 1
                                )
                            }
                        }
                        // 填充空白
                        val remaining = 4 - row.size
                        for (i in 0 until remaining) {
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            // 账户选择
            item {
                Spacer(Modifier.height(16.dp))
                iOSGroupedList {
                    for ((index, account) in accounts.withIndex()) {
                        val isSelected = account.name == selectedAccount
                        iOSListRow(
                            label = account.name,
                            value = "¥${account.displayBalance}",
                            onClick = { selectedAccount = account.name }
                        )
                        if (index < accounts.size - 1) {
                            HorizontalDivider(color = iOSColors.gray6, thickness = 0.5.dp)
                        }
                    }
                }
                Spacer(Modifier.height(24.dp))
            }

            // 保存按钮
            item {
                iOSButton(
                    text = "保存",
                    onClick = {
                        val amount = amountText.toDoubleOrNull() ?: return@iOSButton
                        if (amount <= 0 || selectedCategory.isEmpty()) return@iOSButton
                        val now = System.currentTimeMillis()
                        onSave(
                            Transaction(
                                type = txType,
                                categoryName = selectedCategory,
                                amount = amount,
                                accountName = selectedAccount,
                                remark = remark,
                                tag = selectedTag,
                                date = dateText,
                                dateTime = "$dateText ${LocalDate.now().format(DateTimeFormatter.ofPattern("HH:mm"))}",
                                createdAt = now,
                                updatedAt = now
                            )
                        )
                    },
                    enabled = (amountText.toDoubleOrNull() ?: 0.0) > 0 && selectedCategory.isNotEmpty()
                )
                }
                item { Spacer(Modifier.height(32.dp)) }
                item { iOSSafeAreaBottomSpacer() }
            }
        }
    }

// 稳定颜色（基于分类名哈希）
private fun getCategoryColorStatic(name: String): Color {
    if (name.isBlank()) return iOSColors.gray
    val hash = name.hashCode() and 0x7FFFFFFF
    return categoryPalette[hash % categoryPalette.size]
}
