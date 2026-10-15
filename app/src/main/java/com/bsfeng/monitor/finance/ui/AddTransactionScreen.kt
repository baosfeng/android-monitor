package com.bsfeng.monitor.finance.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.BottomSheet
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * s05 新增 / 编辑交易。
 *
 * 大号金额输入 + 支出/收入分段 + 3 列分类网格 + 日期抽屉 + 账户单选 + 五态（含 error）。
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
    /**
     * 浏览月（MainActivity.kt 的 FinanceRoute.AddTransaction(fromMonth)）。
     * 非空且 ≠ 当前系统月时，表单日期默认取该月最后一天，否则取今天——
     * 否则在历史月里新增的账会落到本月，用户看着像"没保存"。
     */
    fromMonth: String? = null,
    /** 空态 CTA「去导入历史账目」的目标；未传时退化为关闭本页。 */
    onImportClick: (() -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    val editingId = editing?.id
    val isEditing = editing != null
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
    val selectedTag = remember(editingId) { editing?.tag ?: "" }
    var dateText by remember(editingId) {
        mutableStateOf(editing?.date?.takeIf { it.isNotBlank() } ?: defaultDateFor(fromMonth))
    }

    var showDeleteConfirm by remember { mutableStateOf(false) }
    var showDateSheet by remember { mutableStateOf(false) }

    val vm = rememberFinanceViewModel()
    val loading = vm?.loading?.collectAsState()?.value == true
    val control = rememberFinanceStateControl()

    val filteredCategories = remember(categories, txType) { categories.filter { it.type == txType } }

    LaunchedEffect(filteredCategories, accounts) {
        // 切换支出/收入后，原选中项可能不属于当前类型（例如"餐饮"不属于收入）：
        // 必须校正为当前类型的选中项，否则会出现"芯片全未选中却仍能保存支出分类的收入记录"
        if (filteredCategories.isNotEmpty() && filteredCategories.none { it.name == selectedCategory }) {
            selectedCategory = filteredCategories.first().name
        }
        if (selectedAccount.isEmpty() && accounts.isNotEmpty()) {
            selectedAccount = accounts.first().name
        }
    }

    val amount = amountText.toDoubleOrNull() ?: 0.0
    val canSave = amount > 0 && filteredCategories.any { it.name == selectedCategory }

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

    val derivedState = when {
        loading && filteredCategories.isEmpty() && accounts.isEmpty() -> FiveState.LOADING
        filteredCategories.isEmpty() && accounts.isEmpty() -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }
    val state = control.value ?: derivedState
    // EMPTY 只作为演示态：内容仍渲染，空态卡画在列表首位（原型 s05-empty 带「去导入历史账目」）
    val containerState = if (state == FiveState.EMPTY) FiveState.POPULATED else state

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(t.backdropA, t.backdropB)))) {
        Column(Modifier.fillMaxSize()) {
            // 导航栏：关闭 / 标题 / 保存
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = t.space2, vertical = t.space2),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Filled.Close, contentDescription = "取消", tint = t.accent)
                }
                Text(
                    if (isEditing) "编辑交易" else "新增交易",
                    style = MaterialTheme.typography.titleLarge,
                    color = t.fg,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { control.toggleBar() },
                )
                IconButton(
                    onClick = { if (canSave) onSave(buildTransaction()) },
                    enabled = canSave,
                ) {
                    Icon(
                        Icons.Filled.Check,
                        contentDescription = "保存",
                        tint = if (canSave) t.accent else t.muted,
                    )
                }
            }

            StateContainer(
                state = containerState,
                modifier = Modifier.weight(1f),
                emptyText = "分类数据为空，先从熊猫记账导入历史账目",
                errorText = "无法读取本地分类表，暂时不能保存新的交易",
                edgeText = "边界态：超长分类名 / 超长备注 / 缺失分类字段",
                onRetry = { vm?.loadCurrentMonth() },
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    // 底栏（W1 的子页隐藏底栏）之外再加一份留白，保证最后一行不被底部导航裁切
                    contentPadding = PaddingValues(bottom = t.tabH + t.space8),
                ) {
                    if (state == FiveState.EMPTY) {
                        item(key = "empty") {
                            Column(Modifier.padding(horizontal = t.space4, vertical = t.space2)) {
                                FinanceEmptyState(
                                    title = "还没有可用的分类",
                                    desc = "分类数据为空，先从熊猫记账导入历史账目",
                                    icon = Icons.Filled.Description,
                                    actionText = "去导入历史账目",
                                    onAction = { (onImportClick ?: onDismiss)() },
                                )
                            }
                        }
                    }

                    // 金额
                    item(key = "amount") {
                        Column(Modifier.padding(horizontal = t.space4)) {
                            Spacer(Modifier.height(t.space2))
                            GlassCard {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        "¥",
                                        style = MaterialTheme.typography.headlineMedium,
                                        color = t.muted,
                                    )
                                    Spacer(Modifier.width(t.space2))
                                    // B4：自绘 field（不用 M3 OutlinedTextField）
                                    BasicTextField(
                                        value = amountText,
                                        onValueChange = { input ->
                                            amountText = input.filter { c -> c.isDigit() || c == '.' }
                                        },
                                        textStyle = MaterialTheme.typography.headlineLarge.copy(
                                            fontWeight = FontWeight.Bold,
                                            color = if (txType == TYPE_EXPENSE) t.expenseText else t.incomeText,
                                        ),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                        singleLine = true,
                                        cursorBrush = SolidColor(t.accent),
                                        modifier = Modifier.weight(1f),
                                        decorationBox = { inner ->
                                            Box {
                                                if (amountText.isEmpty()) {
                                                    Text(
                                                        "0.00",
                                                        style = MaterialTheme.typography.headlineLarge,
                                                        color = t.muted,
                                                    )
                                                }
                                                inner()
                                            }
                                        },
                                    )
                                }
                            }
                            if (isRefundEntry) {
                                Spacer(Modifier.height(t.space2))
                                Text(
                                    "退款交易：保存后按负金额存储，用于抵扣当月支出",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = t.muted,
                                    textAlign = TextAlign.Center,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            Spacer(Modifier.height(t.space3))
                            SegmentedControl(
                                options = listOf("支出", "收入"),
                                selectedIndex = txType,
                                onSelect = { txType = it },
                            )
                        }
                    }

                    // 分类
                    item(key = "cats") {
                        Column(Modifier.padding(horizontal = t.space4)) {
                            Spacer(Modifier.height(t.space4))
                            SectionTitle("分类", if (txType == TYPE_EXPENSE) "支出" else "收入")
                            Spacer(Modifier.height(t.space3))
                            // 原型 s05:14-17 .s05-cats / .s05-cat：3 列等宽网格、格 min-height 72、
                            // 内容上下居中（色点在上、名称在下）、间距 --space-2；选中态 = 底 fg 7% + 描边 fg 26%。
                            Column(verticalArrangement = Arrangement.spacedBy(t.space2)) {
                                filteredCategories.chunked(3).forEach { row ->
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(t.space2),
                                    ) {
                                        row.forEach { category ->
                                            CategoryCell(
                                                name = category.name,
                                                selected = category.name == selectedCategory,
                                                color = categoryColorOf(category.name),
                                                modifier = Modifier.weight(1f),
                                                onClick = { selectedCategory = category.name },
                                            )
                                        }
                                        repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                                    }
                                }
                            }
                        }
                    }

                    // 日期 + 备注
                    item(key = "date_remark") {
                        Column(Modifier.padding(horizontal = t.space4)) {
                            Spacer(Modifier.height(t.space4))
                            AppCard(padding = t.space1) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(t.radiusMd))
                                        .clickable { showDateSheet = true }
                                        .padding(horizontal = t.space3, vertical = t.space3),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.CalendarMonth,
                                        contentDescription = null,
                                        tint = t.muted,
                                        modifier = Modifier.size(t.iconSize),
                                    )
                                    Spacer(Modifier.width(t.space3))
                                    Text(
                                        "日期",
                                        style = MaterialTheme.typography.titleSmall,
                                        color = t.fg,
                                        modifier = Modifier.weight(1f),
                                    )
                                    Text(
                                        dateText,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = t.accent,
                                    )
                                    Icon(
                                        Icons.AutoMirrored.Filled.KeyboardArrowRight,
                                        contentDescription = null,
                                        tint = t.muted,
                                        modifier = Modifier.size(t.iconSize),
                                    )
                                }
                                HorizontalDivider(
                                    thickness = DividerDefaults.Thickness,
                                    color = t.borderSoft,
                                    modifier = Modifier.padding(start = t.space8),
                                )
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = t.space3, vertical = t.space3),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Icon(
                                        Icons.Filled.Description,
                                        contentDescription = null,
                                        tint = t.muted,
                                        modifier = Modifier.size(t.iconSize),
                                    )
                                    Spacer(Modifier.width(t.space3))
                                    Box(Modifier.weight(1f)) {
                                        BasicTextField(
                                            value = remark,
                                            onValueChange = { remark = it.take(REMARK_MAX_LEN) },
                                            singleLine = true,
                                            textStyle = MaterialTheme.typography.titleSmall.copy(color = t.fg),
                                            cursorBrush = SolidColor(t.accent),
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        if (remark.isEmpty()) {
                                            Text(
                                                "添加备注",
                                                style = MaterialTheme.typography.titleSmall,
                                                color = t.muted,
                                            )
                                        }
                                    }
                                    if (remark.isNotEmpty()) {
                                        Spacer(Modifier.width(t.space2))
                                        Text(
                                            remark.length.toString() + "/" + REMARK_MAX_LEN,
                                            style = MaterialTheme.typography.labelSmall,
                                            color = t.meta,
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 账户
                    item(key = "accounts") {
                        Column(Modifier.padding(horizontal = t.space4)) {
                            Spacer(Modifier.height(t.space4))
                            SectionTitle("账户", "默认「" + (accounts.firstOrNull()?.name ?: "现金") + "」")
                            Spacer(Modifier.height(t.space3))
                            AppCard(padding = t.space1) {
                                accounts.forEachIndexed { index, account ->
                                    AccountRow(
                                        account = account,
                                        selected = account.name == selectedAccount,
                                        onClick = { selectedAccount = account.name },
                                    )
                                    if (index != accounts.lastIndex) {
                                        HorizontalDivider(
                                            thickness = DividerDefaults.Thickness,
                                            color = t.borderSoft,
                                            modifier = Modifier.padding(start = t.space8),
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // 保存 / 删除
                    item(key = "actions") {
                        Column(Modifier.padding(horizontal = t.space4)) {
                            Spacer(Modifier.height(t.space4))
                            PrimaryButton(
                                text = if (isEditing) "保存修改" else "保存",
                                enabled = canSave,
                                onClick = { if (canSave) onSave(buildTransaction()) },
                            )
                            if (isEditing && onDelete != null) {
                                Spacer(Modifier.height(t.space3))
                                GhostButton(
                                    text = "删除这笔交易",
                                    textColor = t.expenseText,
                                    background = t.danger.copy(alpha = 0.12f),
                                    onClick = { showDeleteConfirm = true },
                                )
                            }
                            Spacer(Modifier.height(t.space8))
                        }
                    }

                    item(key = "bottom") { Spacer(Modifier.height(t.space8)) }
                }
            }
        }

        // 日期抽屉
        if (showDateSheet) {
            FinanceScrim(onDismiss = { showDateSheet = false })
            BottomSheet(
                visible = true,
                onDismiss = { showDateSheet = false },
                modifier = Modifier.align(Alignment.BottomCenter),
            ) {
                Text("选择日期", style = MaterialTheme.typography.titleMedium, color = t.fg)
                Spacer(Modifier.height(t.space3))
                dateOptions().forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(t.radiusMd))
                            .clickable {
                                dateText = option.second
                                showDateSheet = false
                            }
                            .padding(horizontal = t.space3, vertical = t.space3),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(option.first, style = MaterialTheme.typography.titleSmall, color = t.fg)
                            Text(
                                option.second + " · " + option.third,
                                style = MaterialTheme.typography.labelSmall,
                                color = t.muted,
                            )
                        }
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowRight,
                            contentDescription = null,
                            tint = t.muted,
                            modifier = Modifier.size(t.iconSize),
                        )
                    }
                }
                Spacer(Modifier.height(t.space3))
                GhostButton(
                    text = "取消",
                    textColor = t.fg2,
                    background = t.surfaceWarm,
                    onClick = { showDateSheet = false },
                )
            }
        }

        FinanceStateBar(
            control = control,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(t.space4),
        )
    }

    if (showDeleteConfirm && editing != null && onDelete != null) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirm = false },
            shape = RoundedCornerShape(t.radiusLg),
            title = { Text("删除这笔交易？", style = MaterialTheme.typography.titleMedium) },
            text = {
                Text(
                    "删除后无法恢复，预算与统计会同步更新。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteConfirm = false
                    onDelete(editing)
                }) {
                    Text("删除", color = t.expenseText, fontWeight = FontWeight.SemiBold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirm = false }) {
                    Text("取消", color = t.muted)
                }
            },
        )
    }
}

@Composable
private fun SectionTitle(title: String, meta: String) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall, color = t.fg)
        Text(meta, style = MaterialTheme.typography.labelSmall, color = t.meta)
    }
}

/**
 * 分类格（原型 .s05-cat，s05-transaction-edit.html:15-17）：
 * 3 列网格中的一格 —— min-height 72、上下居中、色点 + 名称；选中 = 底 fg 7% + 描边 fg 26% + 名称 600。
 * 色值全部取 token（fg/border-soft/surface/radius），不新增字面色。
 */
@Composable
private fun CategoryCell(
    name: String,
    selected: Boolean,
    color: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    Column(
        modifier = modifier
            .fillMaxHeight()
            .heightIn(min = CATEGORY_CELL_MIN_H)
            .clip(shape)
            .background(if (selected) t.fgFade(7) else t.surface)
            .border(1.dp, if (selected) t.fgFade(26) else t.borderSoft, shape)
            .clickable { onClick() }
            .padding(t.space2),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        LegendDot(color, size = t.dotSize)
        Spacer(Modifier.height(t.space1))
        Text(
            name,
            style = MaterialTheme.typography.labelMedium,
            color = if (selected) t.fg else t.fg2,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
        )
    }
}

/** 原型 .s05-cat 的 min-height:72px（s05-transaction-edit.html:15）。 */
private val CATEGORY_CELL_MIN_H = 72.dp

@Composable
private fun AccountRow(account: Account, selected: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.radiusMd))
            .clickable { onClick() }
            .padding(horizontal = t.space3, vertical = t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                account.name,
                style = MaterialTheme.typography.titleSmall,
                color = if (selected) t.accent else t.fg,
            )
            Text(
                "余额 ¥" + account.displayBalance,
                style = MaterialTheme.typography.labelSmall,
                color = t.meta,
            )
        }
        if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = "已选择",
                tint = t.accent,
                modifier = Modifier.size(t.iconSize),
            )
        }
    }
}

@Composable
private fun PrimaryButton(text: String, enabled: Boolean, onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.97f else 1f,
        animationSpec = t.springOf(MotionToken.Press),
        label = "primaryScale",
    )
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(RoundedCornerShape(t.radiusLg))
            .background(if (enabled) t.accent else t.accent.copy(alpha = 0.45f))
            .clickable(interactionSource = interaction, indication = null) { if (enabled) onClick() }
            .padding(vertical = t.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.titleSmall,
            color = t.accentOn,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun GhostButton(
    text: String,
    textColor: Color,
    background: Color,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(t.radiusLg))
            .background(background)
            .clickable { onClick() }
            .padding(vertical = t.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = textColor)
    }
}

/** 今天 / 昨天 / 前天（三列：标签、yyyy-MM-dd、星期）。 */
private fun dateOptions(): List<Triple<String, String, String>> {
    val today = LocalDate.now()
    val week = listOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")
    val labels = listOf("今天", "昨天", "前天")
    return (0..2).map { offset ->
        val day = today.minusDays(offset.toLong())
        Triple(
            labels[offset],
            day.format(DateTimeFormatter.ofPattern("yyyy-MM-dd")),
            week[day.dayOfWeek.value - 1],
        )
    }
}

/** 今日日期 yyyy-MM-dd */
private fun todayText(): String = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))

/**
 * 表单默认日期：浏览月非空且 ≠ 当前系统月时取该月最后一天，否则今天。
 * 非法月份字符串一律退化为今天（异常路径不留白屏 / 崩溃）。
 */
private fun defaultDateFor(fromMonth: String?): String {
    val month = fromMonth?.takeIf { it.isNotBlank() && it != FinanceViewModel.currentMonthStr() }
    if (month != null) {
        runCatching {
            return YearMonth.parse(month).atEndOfMonth()
                .format(DateTimeFormatter.ofPattern("yyyy-MM-dd"))
        }
    }
    return todayText()
}

/** 原型 s05-edge：备注 200 字上限 */
private const val REMARK_MAX_LEN = 200

/** 交易类型：支出 */
private const val TYPE_EXPENSE = 0
