package com.bsfeng.monitor.finance.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.finance.model.Account
import io.github.alexzhirkevich.cupertino.ExperimentalCupertinoApi
import io.github.alexzhirkevich.cupertino.section.SectionStyle
import io.github.alexzhirkevich.cupertino.section.section
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * 添加交易页面 — iOS 风格表单。
 */
@OptIn(ExperimentalCupertinoApi::class)
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
                .padding(horizontal = 8.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onDismiss) {
                Icon(Icons.Default.Close, contentDescription = "取消",
                    tint = MaterialTheme.colorScheme.primary)
            }
            Text("新增交易", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
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
                enabled = canSave
            ) {
                Icon(Icons.Default.Check, contentDescription = "保存",
                    tint = if (canSave) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
            }
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            // 金额输入
            item(key = "amount") {
                Spacer(Modifier.height(16.dp))
                Box(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                    contentAlignment = Alignment.Center
                ) {
                    OutlinedTextField(
                        value = amountText,
                        onValueChange = { amountText = it.filter { c -> c.isDigit() || c == '.' } },
                        placeholder = { Text("0.00", fontSize = 40.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)) },
                        textStyle = LocalTextStyle.current.copy(
                            fontSize = 40.sp, fontWeight = FontWeight.Bold,
                            color = if (txType == 0) Color(0xFFFF3B30) else Color(0xFF34C759),
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
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
                Spacer(Modifier.height(8.dp))
            }
            section(style = SectionStyle.InsetGrouped) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("日期", fontSize = 15.sp)
                        Text(dateText, fontSize = 15.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("备注", fontSize = 15.sp)
                        Text(remark.ifBlank { "添加备注" }, fontSize = 15.sp,
                            color = if (remark.isNotEmpty()) MaterialTheme.colorScheme.onSurface
                            else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            // 分类选择
            item(key = "category_header") {
                Spacer(Modifier.height(8.dp))
                Text(
                    "选择分类", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }

            section(style = SectionStyle.InsetGrouped) {
                item {
                    val chunked = filteredCategories.chunked(4)
                    Column(modifier = Modifier.padding(8.dp)) {
                        for (row in chunked) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                for (cat in row) {
                                    val isSelected = cat.name == selectedCategory
                                    val catInteractionSource = remember(cat.name) { MutableInteractionSource() }
                                    val isCatPressed by catInteractionSource.collectIsPressedAsState()
                                    val catScale by animateFloatAsState(
                                        targetValue = if (isCatPressed) 0.85f else 1f,
                                        animationSpec = spring(dampingRatio = 0.4f, stiffness = 600f)
                                    )
                                    Column(
                                        modifier = Modifier
                                            .clickable(
                                                interactionSource = catInteractionSource,
                                                indication = null
                                            ) { selectedCategory = cat.name }
                                            .padding(4.dp)
                                            .graphicsLayer {
                                                scaleX = catScale
                                                scaleY = catScale
                                            },
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .clip(RoundedCornerShape(24.dp))
                                                .background(
                                                    if (isSelected) getCategoryColorStatic(cat.name)
                                                    else Color(0xFFE5E5EA)
                                                ),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = cat.name.take(1),
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold, fontSize = 16.sp
                                            )
                                        }
                                        Spacer(Modifier.height(4.dp))
                                        Text(
                                            text = cat.name, fontSize = 11.sp,
                                            color = if (isSelected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1
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
                Spacer(Modifier.height(8.dp))
                Text(
                    "选择账户", fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
            }
            section(style = SectionStyle.InsetGrouped) {
                accounts.forEach { account ->
                    item(key = account.id) { _ ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(account.name, fontSize = 15.sp)
                            Text("¥${account.displayBalance}", fontSize = 15.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }

            // 保存按钮
            item(key = "save") {
                Spacer(Modifier.height(16.dp))
                val saveInteractionSource = remember { MutableInteractionSource() }
                val isSavePressed by saveInteractionSource.collectIsPressedAsState()
                val saveScale by animateFloatAsState(
                    targetValue = if (isSavePressed) 0.95f else 1f,
                    animationSpec = spring(dampingRatio = 0.5f, stiffness = 800f)
                )
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .graphicsLayer { scaleX = saveScale; scaleY = saveScale }
                        .background(
                            if (canSave) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.primary.copy(alpha = 0.5f)
                        ),
                    contentAlignment = Alignment.Center
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
                        interactionSource = saveInteractionSource
                    ) {
                        Text("保存", color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(32.dp))
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
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .height(32.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFFE5E5EA)),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        options.forEachIndexed { index, option ->
            val isSelected = index == selectedIndex
            val segScale by animateFloatAsState(
                targetValue = if (isSelected) 1f else 0.95f,
                animationSpec = spring(dampingRatio = 0.6f, stiffness = 500f)
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
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = option, fontSize = 13.sp,
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (isSelected) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** 稳定分类颜色 */
private fun getCategoryColorStatic(name: String): Color {
    if (name.isBlank()) return Color(0xFF8E8E93)
    val hash = name.hashCode() and 0x7FFFFFFF
    return categoryPalette[hash % categoryPalette.size]
}
