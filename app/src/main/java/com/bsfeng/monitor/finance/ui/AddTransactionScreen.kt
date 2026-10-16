package com.bsfeng.monitor.finance.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
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
import androidx.compose.ui.unit.em
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.ConfirmDialog
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.util.Locale

/** 一次只开一个抽屉（原型 s14a 的四个抽屉互斥） */
private enum class RecordSheet { NONE, CATEGORY, ACCOUNT, TO_ACCOUNT, TIME, RECEIPT }

/**
 * s14a「记一笔」——一屏放得下的表单 + 全抽屉（定版原型 `.open-design/26-a-quan-chouti.html`）。
 *
 * 与旧 s05 的差别就是用户当初提的三条：**一屏不放滚**（分类从 6 行网格改成抽屉里的 4 列网格）、
 * **账户不再是裸露的 pill**（走同一套抽屉）、**时间能选到「昨天 10 点」**（日期快捷 + 时/分滚轮）。
 * 四个字段行（分类 / 账户 / 时间 / 备注）同构：标签 + 值 + ▸，抽屉开合不影响上方表单高度。
 *
 * @param editing 非空表示编辑已有交易：表单预填该交易，保存时保留 id/createdAt/source，
 *                并允许通过 [onDelete] 删除；为空表示新增。
 * @param fromMonth 浏览月（MainActivity 的 FinanceRoute.AddTransaction(fromMonth)）。
 *                  非空且 ≠ 当前系统月时，表单日期默认取该月最后一天，否则取今天——
 *                  否则在历史月里新增的账会落到本月，用户看着像「没保存」。
 * @param onImportClick 空态 CTA「去导入历史账目」的目标；未传时退化为关闭本页。
 */
@Composable
fun AddTransactionScreen(
    categories: List<Category>,
    accounts: List<Account>,
    onSave: (Transaction) -> Unit,
    onDismiss: () -> Unit,
    editing: Transaction? = null,
    onDelete: ((Transaction) -> Unit)? = null,
    fromMonth: String? = null,
    onImportClick: (() -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    val editingId = editing?.id
    val isEditing = editing != null
    val isRefundEntry = editing?.isRefundEntry == true
    val isTransfer = txTypeOfValue(editing) == RecordForm.TYPE_TRANSFER

    var txType by remember(editingId) { mutableStateOf(editing?.type ?: RecordForm.TYPE_EXPENSE) }
    var amountText by remember(editingId) {
        mutableStateOf(
            editing?.let { String.format(Locale.CHINA, "%.2f", kotlin.math.abs(it.amount)) } ?: ""
        )
    }
    var selectedCategory by remember(editingId) { mutableStateOf(editing?.categoryName ?: "") }
    var accountName by remember(editingId) { mutableStateOf(editing?.accountName ?: "") }
    var toAccountName by remember(editingId) { mutableStateOf(editing?.toAccountName ?: "") }
    var remark by remember(editingId) { mutableStateOf(editing?.remark ?: "") }
    val tag = remember(editingId) { editing?.tag ?: "" }
    var parts by remember(editingId) { mutableStateOf(initialTimeParts(editing, fromMonth)) }

    var openSheet by remember { mutableStateOf(RecordSheet.NONE) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val vm = rememberFinanceViewModel()
    val loading = vm?.loading?.collectAsState()?.value == true
    val control = rememberFinanceStateControl()

    val typeCategories = remember(categories, txType) { categories.filter { it.type == txType } }
    val today = LocalDate.now()

    LaunchedEffect(typeCategories, accounts, txType) {
        // 切换支出/收入后原选中项可能不属于当前类型（例如「餐饮」不属于收入）：必须校正，
        // 否则会出现「芯片全未选中却仍能保存支出分类的收入记录」
        if (txType != RecordForm.TYPE_TRANSFER) {
            selectedCategory = RecordForm.correctCategory(selectedCategory, typeCategories.map { it.name })
        }
        if (accountName.isBlank()) accountName = accounts.firstOrNull()?.name ?: ""
        if (txType == RecordForm.TYPE_TRANSFER && toAccountName.isBlank()) {
            toAccountName = accounts.firstOrNull { it.name != accountName }?.name ?: ""
        }
    }

    val canSave = RecordForm.canSave(txType, amountText, selectedCategory, accountName, toAccountName)

    fun buildTransaction(): Transaction {
        val magnitude = RecordForm.amountOf(amountText)
        val signedAmount = if (isRefundEntry) -magnitude else magnitude
        val now = System.currentTimeMillis()
        // 交易时间 = 用户在表单里选定的日期与时刻（精确到秒），不是「点保存的时刻」——
        // 对账看的就是页面上的支付时间（2026-10 真机踩坑：记 18:32 的那笔被写成了 20:51）
        val timestamp = RecordForm.timestampOf(parts)
        val category = if (txType == RecordForm.TYPE_TRANSFER) "" else selectedCategory
        val toAccount = if (txType == RecordForm.TYPE_TRANSFER) toAccountName else ""
        val base = editing
        return if (base != null) {
            base.copy(
                type = txType,
                categoryName = category,
                amount = signedAmount,
                accountName = accountName,
                toAccountName = toAccount,
                remark = remark,
                tag = tag,
                date = parts.date,
                dateTime = timestamp,
                updatedAt = now,
            )
        } else {
            Transaction(
                type = txType,
                categoryName = category,
                amount = signedAmount,
                accountName = accountName,
                toAccountName = toAccount,
                remark = remark,
                tag = tag,
                date = parts.date,
                dateTime = timestamp,
                createdAt = now,
                updatedAt = now,
            )
        }
    }

    val derivedState = when {
        loading && categories.isEmpty() && accounts.isEmpty() -> FiveState.LOADING
        categories.isEmpty() && accounts.isEmpty() -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }
    val state = control.value ?: derivedState
    // EMPTY 只作为演示态：内容仍渲染，空态卡画在表单首位（原型 s05-empty 带「去导入历史账目」）
    val containerState = if (state == FiveState.EMPTY) FiveState.POPULATED else state

    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(t.backdropA, t.backdropB)))) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：关闭 / 标题。保存不再放顶栏 —— 它吸在底部，永远可点（原型 .s14a-actions）
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
                    if (isEditing) "编辑交易" else "记一笔",
                    style = MaterialTheme.typography.titleLarge,
                    color = t.fg,
                    modifier = Modifier.clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                    ) { control.toggleBar() },
                )
                // 占位，让标题保持居中（与左侧关闭按钮等宽）
                Spacer(Modifier.size(t.tapMin))
            }

            StateContainer(
                state = containerState,
                modifier = Modifier.weight(1f),
                emptyText = "分类数据为空，先从熊猫记账导入历史账目",
                errorText = "无法读取本地分类表，暂时不能保存新的交易",
                edgeText = "边界态：超长分类名 / 超长备注 / 缺失分类字段",
                onRetry = { vm?.loadCurrentMonth() },
            ) {
                Box(Modifier.fillMaxSize().imePadding()) {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = t.space4),
                    ) {
                        if (state == FiveState.EMPTY) {
                            FinanceEmptyState(
                                title = "还没有可用的分类",
                                desc = "分类数据为空，先从熊猫记账导入历史账目",
                                icon = Icons.Filled.Description,
                                actionText = "去导入历史账目",
                                onAction = { (onImportClick ?: onDismiss)() },
                            )
                            Spacer(Modifier.height(t.space3))
                        } else {
                            Spacer(Modifier.height(t.space2))
                        }

                        // ① 类型 + 金额（原型 s14a-amount）
                        // 原型 .s14-card{padding:12px 16px} —— GlassCard 默认四边 16，这里按原型给 12/16
                        GlassCard(padding = 0.dp) {
                            Column(
                                Modifier.padding(
                                    start = t.space4,
                                    end = t.space4,
                                    top = t.space3,
                                    bottom = t.space3,
                                ),
                            ) {
                            SegmentedControl(
                                options = RecordForm.TYPE_OPTIONS,
                                selectedIndex = txType,
                                onSelect = { txType = it },
                            )
                            Spacer(Modifier.height(t.space2))
                            // 原型 .s14a-amt 是 baseline 对齐 + 上下 padding 4px：
                            // ¥ 用 --text-xl(28px)/600，金额用 --text-3xl(56px)/700
                            Row(
                                modifier = Modifier.padding(vertical = t.space1),
                                verticalAlignment = Alignment.Bottom,
                            ) {
                                Text(
                                    "¥",
                                    style = MaterialTheme.typography.headlineMedium.copy(fontSize = t.textXl),
                                    color = t.muted,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Spacer(Modifier.width(t.space2))
                                BasicTextField(
                                    value = amountText,
                                    onValueChange = { input ->
                                        amountText = input.filter { c -> c.isDigit() || c == '.' }
                                    },
                                    textStyle = MaterialTheme.typography.headlineLarge.copy(
                                        fontSize = t.text3xl,
                                        fontWeight = FontWeight.Bold,
                                        letterSpacing = AMOUNT_LETTER_SPACING,
                                        // 原型 .s14a-input 用 --fg：金额本身不染色，
                                        // 方向由上面那排类型段（支出/收入/转账）表达
                                        color = t.fg,
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
                                                    style = MaterialTheme.typography.headlineLarge.copy(
                                                        fontSize = t.text3xl,
                                                        fontWeight = FontWeight.Bold,
                                                        letterSpacing = AMOUNT_LETTER_SPACING,
                                                    ),
                                                    color = t.muted,
                                                )
                                            }
                                            inner()
                                        }
                                    },
                                )
                            }
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

                        // ② 字段卡：分类 / 账户（转账为转出+转入）/ 时间 / 备注 —— 四行同构，与原型一致
                        Spacer(Modifier.height(t.space3))
                        AppCard(padding = 0.dp) {
                            // 原型 .s14-card 的 padding 是 12/16；行分隔线（.s14-toggle 的 border-top）
                            // 就在这层内边距之内 —— 内边距必须给在**行外面**，不能塞进行里
                            Column(
                                Modifier.padding(horizontal = t.space4, vertical = t.space3),
                            ) {
                                if (!isTransferType(txType)) {
                                    FieldRow(
                                        label = "分类",
                                        value = selectedCategory,
                                        placeholder = "选择分类",
                                        onClick = { openSheet = RecordSheet.CATEGORY },
                                    )
                                    FieldDivider()
                                }
                                FieldRow(
                                    label = if (isTransferType(txType)) "转出" else "账户",
                                    value = accountName,
                                    placeholder = "选择账户",
                                    onClick = { openSheet = RecordSheet.ACCOUNT },
                                )
                                if (isTransferType(txType)) {
                                    FieldDivider()
                                    FieldRow(
                                        label = "转入",
                                        value = toAccountName,
                                        placeholder = "选择转入账户",
                                        onClick = { openSheet = RecordSheet.TO_ACCOUNT },
                                    )
                                }
                                FieldDivider()
                                FieldRow(
                                    label = "时间",
                                    value = RecordForm.timeRowText(parts, today),
                                    onClick = { openSheet = RecordSheet.TIME },
                                )
                                FieldDivider()
                                NoteRow(
                                    value = remark,
                                    onValueChange = { remark = it.take(REMARK_MAX_LEN) },
                                )
                            }
                        }

                        // ③ 单据卡：只有自动记账记下的交易才带单据，手动录入不摆空行
                        if (RecordForm.hasReceipt(editing)) {
                            Spacer(Modifier.height(t.space3))
                            AppCard(padding = 0.dp) {
                                Column(
                                    Modifier.padding(horizontal = t.space4, vertical = t.space3),
                                ) {
                                    FieldRow(
                                        label = "单据",
                                        value = RecordForm.receiptSummary(editing),
                                        onClick = { openSheet = RecordSheet.RECEIPT },
                                    )
                                }
                            }
                        }

                        // 给吸底保存条让位（原型 .s14a-actions 是 sticky，这里用等高的留白）
                        Spacer(Modifier.height(t.space12))
                    }

                    // ④ 保存吸底（原型 .s14a-actions）：表单怎么滚，它都在
                    Column(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            // 原型 .s14a-actions：linear-gradient(to top, --surface 62%, transparent)
                            .background(
                                Brush.verticalGradient(0f to Color.Transparent, 0.38f to t.surface),
                            )
                            // 原型 .s14a-actions：padding 12px 16px 20px
                            .padding(
                                start = t.space4,
                                end = t.space4,
                                top = t.space3,
                                bottom = t.space5,
                            ),
                    ) {
                        PrimaryButton(
                            text = if (isEditing) "保存修改" else "保存",
                            enabled = canSave,
                            onClick = { if (canSave) onSave(buildTransaction()) },
                        )
                        if (isEditing && onDelete != null) {
                            Spacer(Modifier.height(t.space2))
                            GhostButton(
                                text = "删除这笔交易",
                                textColor = t.expenseText,
                                background = t.danger.copy(alpha = 0.12f),
                                onClick = { showDeleteConfirm = true },
                            )
                        }
                    }
                }
            }
        }

        // 四个抽屉（互斥；分类 / 账户选中即关，时间由「确定」关，单据由「关闭」关）
        when (openSheet) {
            RecordSheet.CATEGORY -> CategorySheet(
                visible = true,
                categories = typeCategories,
                selectedName = selectedCategory,
                onPick = {
                    selectedCategory = it
                    openSheet = RecordSheet.NONE
                },
                onDismiss = { openSheet = RecordSheet.NONE },
            )

            RecordSheet.ACCOUNT -> AccountSheet(
                visible = true,
                title = if (isTransferType(txType)) "选择转出账户" else "选择账户",
                accounts = accounts,
                selectedName = accountName,
                onPick = {
                    accountName = it
                    // 转出与转入撞车时自动改掉转入，避免「同一账户进出」这种无意义的转账
                    if (toAccountName == it) {
                        toAccountName = accounts.firstOrNull { acc -> acc.name != it }?.name ?: ""
                    }
                    openSheet = RecordSheet.NONE
                },
                onDismiss = { openSheet = RecordSheet.NONE },
            )

            RecordSheet.TO_ACCOUNT -> AccountSheet(
                visible = true,
                title = "选择转入账户",
                accounts = accounts,
                selectedName = toAccountName,
                onPick = {
                    toAccountName = it
                    openSheet = RecordSheet.NONE
                },
                onDismiss = { openSheet = RecordSheet.NONE },
            )

            RecordSheet.TIME -> TimeSheet(
                visible = true,
                parts = parts,
                onDateChange = { parts = parts.copy(date = it) },
                onHourMinuteChange = { hour, minute ->
                    parts = parts.copy(hour = hour, minute = minute)
                },
                onDismiss = { openSheet = RecordSheet.NONE },
            )

            RecordSheet.RECEIPT -> ReceiptSheet(
                visible = true,
                transaction = editing,
                onDismiss = { openSheet = RecordSheet.NONE },
            )

            RecordSheet.NONE -> Unit
        }

        FinanceStateBar(
            control = control,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(t.space4),
        )

        // 删除确认（原型 s05 #s05-del-dialog）。自绘浮层需显式接管返回键，
        // 关闭语义与点遮罩 / 取消一致（同项目既有写法：AdScreen / MainScreen）
        if (showDeleteConfirm && editing != null && onDelete != null) {
            BackHandler { showDeleteConfirm = false }
            ConfirmDialog(
                visible = true,
                title = "删除这笔交易？",
                message = "删除后无法恢复，预算与统计会同步更新。",
                confirmLabel = "删除",
                dismissLabel = "取消",
                onConfirm = {
                    showDeleteConfirm = false
                    onDelete(editing)
                },
                onDismiss = { showDeleteConfirm = false },
            )
        }
    }
}

// ─────────────────────────── 表单行（同构：标签 + 值 + ▸） ───────────────────────────

/** 字段行最小高度（原型 .s14-toggle min-height:44px = --tap-min） */
private val FIELD_ROW_MIN_H = 44.dp

/** 标签列宽（原型 .screen.s14a .s14-label：flex 0 0 44px = --tap-min；两字中文放得下） */
private val FIELD_LABEL_W = 44.dp

/** 一手可点：标签 + 值 + ▸（原型 .s14-toggle） */
@Composable
private fun FieldRow(
    label: String,
    value: String,
    onClick: () -> Unit,
    placeholder: String = "",
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = FIELD_ROW_MIN_H)
            .clip(RoundedCornerShape(t.radiusMd))
            .clickable { onClick() },
        // 原型 .s14-toggle{padding:0} + min-height:44px：行自身零内边距。
        // 注意 modifier 链的顺序 —— heightIn 在 padding 之前时，44 约束的是**内容区**，
        // 总高会变成 44+padding（真机实测多出 8dp，四行累计就很明显了）
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.space3),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
            modifier = Modifier.width(FIELD_LABEL_W),
        )
        Text(
            text = value.ifBlank { placeholder },
            style = MaterialTheme.typography.bodySmall,
            color = if (value.isBlank()) t.muted else t.fg,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Icon(
            Icons.AutoMirrored.Filled.KeyboardArrowRight,
            contentDescription = null,
            tint = t.muted,
            modifier = Modifier.size(t.iconSize),
        )
    }
}

/** 备注行：与上面三行同构，但值是直接输入的（无 ▸，因为不需要再开一层） */
@Composable
private fun NoteRow(value: String, onValueChange: (String) -> Unit) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = FIELD_ROW_MIN_H),
        // 同上：备注行也是 .s14-toggle，零内边距 + min-height 44
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(t.space3),
    ) {
        Text(
            "备注",
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
            modifier = Modifier.width(FIELD_LABEL_W),
        )
        Box(Modifier.weight(1f)) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodySmall.copy(
                    color = t.fg,
                    fontWeight = FontWeight.Medium,
                ),
                cursorBrush = SolidColor(t.accent),
                modifier = Modifier.fillMaxWidth(),
            )
            if (value.isEmpty()) {
                Text(
                    "添加备注",
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                )
            }
        }
        if (value.isNotEmpty()) {
            Text(
                value.length.toString() + "/" + REMARK_MAX_LEN,
                style = MaterialTheme.typography.labelSmall,
                color = t.meta,
            )
        }
    }
}

/** 行分隔线：原型是 .s14-toggle:not(:first-child){border-top} —— **整行宽**，不从标签右侧起 */
@Composable
private fun FieldDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(LocalDesignTokens.current.borderSoft),
    )
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
            // 原型 .btn{border-radius:var(--radius-md)} = 12dp（不是 radius-lg 的 18dp）
            .clip(RoundedCornerShape(t.radiusMd))
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
            .clip(RoundedCornerShape(t.radiusMd))
            .background(background)
            .clickable { onClick() }
            .padding(vertical = t.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.titleSmall, color = textColor)
    }
}

// ─────────────────────────── 取值规则 ───────────────────────────

private fun isTransferType(type: Int): Boolean = type == RecordForm.TYPE_TRANSFER

/** 编辑已有交易时的类型（供 [isTransfer] 之类的初值判断） */
private fun txTypeOfValue(editing: Transaction?): Int = editing?.type ?: RecordForm.TYPE_EXPENSE

/**
 * 表单的初始时间。
 *
 * - 编辑：从交易的 date / dateTime 还原（dateTime 精确到秒）
 * - 新增：当前时刻；浏览月非当前月时日期取该月最后一天（见 [AddTransactionScreen] 的 fromMonth）
 */
private fun initialTimeParts(editing: Transaction?, fromMonth: String?): RecordForm.TimeParts {
    val base = RecordForm.timePartsOf(editing?.date ?: "", editing?.dateTime ?: "", LocalDateTime.now())
    if (editing != null) return base
    val month = fromMonth?.takeIf { it.isNotBlank() && it != FinanceViewModel.currentMonthStr() }
    if (month == null) return base
    val last = runCatching { YearMonth.parse(month).atEndOfMonth().toString() }.getOrNull() ?: return base
    return base.copy(date = last)
}

/** 金额字距（原型 .s14a-input 的 letter-spacing:-.02em，56px 下约 -1.12px） */
private val AMOUNT_LETTER_SPACING = (-0.02).em

/** 原型 s05-edge：备注 200 字上限 */
private const val REMARK_MAX_LEN = 200
