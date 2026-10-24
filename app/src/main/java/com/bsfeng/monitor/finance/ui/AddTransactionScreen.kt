package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/AddTransactionScreen.kt；职责：s14a「记一笔」屏幕装配（表单状态 / 抽屉互斥 / 保存与删除）。
// 三张卡见 AddTransactionSections，表单行见 AddTransactionFormRows，按钮见 AddTransactionButtons，
// 顶栏与抽屉分发见 AddTransactionChrome，交易组装见 AddTransactionDraft，取值规则见 AddTransactionFormState。

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bsfeng.monitor.ui.foundation.pageBackdrop
import com.bsfeng.monitor.finance.CategoryTree
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import java.time.LocalDate
import java.util.Locale

/**
 * s14a「记一笔」——一屏放得下的表单 + 全抽屉（定版原型 `.open-design/26-a-quan-chouti.html`）。
 *
 * 与旧 s05 的差别：一屏不放滚（分类走抽屉里的两级选择器）、账户不再裸露、时间能选到「昨天 10 点」。
 * 四个字段行（分类 / 账户 / 时间 / 说明）同构：标签 + 值 + ▸，抽屉开合不影响上方表单高度。
 * 「说明」行只存用户手写内容，自动采集的商户 / 商品说明 / 付款方式留在交易的独立字段里。
 *
 * @param editing 非空表示编辑已有交易：表单预填该交易，保存时保留 id/createdAt/source，
 *                并允许通过 [onDelete] 删除；为空表示新增。
 * @param categoryTree 一级/二级分类树：分类抽屉用它做两级同屏选择。
 *                     默认由 [categories] 现构（MainActivity 已一次性提供全量分类）；
 *                     显式传入时以树为准，[categories] 只用于空态判定。
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
    categoryTree: CategoryTree? = null,
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

    // 分类树：MainActivity 传全量分类（一级 + 二级），这里构造成树供两级选择器使用。
    // 类型校正要按「该类型下所有分类名（一级 + 二级）」判断，否则编辑一笔二级分类的交易
    // （例如「电商」）会被误判成不在当前类型里而被改成第一个分类。
    val localTree = remember(categories) { CategoryTree(categories) }
    val tree = categoryTree ?: localTree
    val typeCategoryNames = remember(tree, txType) {
        tree.topLevel(txType).flatMap { top ->
            listOf(top.name) + tree.childrenOf(top.name).map { it.name }
        }
    }
    val today = LocalDate.now()

    LaunchedEffect(typeCategoryNames, accounts, txType) {
        // 切换支出/收入后原选中项可能不属于当前类型（例如「餐饮」不属于收入）：必须校正，
        // 否则会出现「芯片全未选中却仍能保存支出分类的收入记录」
        if (txType != RecordForm.TYPE_TRANSFER) {
            selectedCategory = RecordForm.correctCategory(selectedCategory, typeCategoryNames)
        }
        if (accountName.isBlank()) accountName = accounts.firstOrNull()?.name ?: ""
        if (txType == RecordForm.TYPE_TRANSFER && toAccountName.isBlank()) {
            toAccountName = accounts.firstOrNull { it.name != accountName }?.name ?: ""
        }
    }

    val canSave = RecordForm.canSave(txType, amountText, selectedCategory, accountName, toAccountName)

    fun buildTransaction(): Transaction = AddTransactionDraft(
        txType = txType,
        amountText = amountText,
        selectedCategory = selectedCategory,
        accountName = accountName,
        toAccountName = toAccountName,
        remark = remark,
        tag = tag,
        parts = parts,
        editing = editing,
        isRefundEntry = isRefundEntry,
    ).build()

    val derivedState = when {
        loading && categories.isEmpty() && accounts.isEmpty() -> FiveState.LOADING
        categories.isEmpty() && accounts.isEmpty() -> FiveState.EMPTY
        else -> FiveState.POPULATED
    }
    val state = control.value ?: derivedState
    // EMPTY 只作为演示态：内容仍渲染，空态卡画在表单首位（原型 s05-empty 带「去导入历史账目」）
    val containerState = if (state == FiveState.EMPTY) FiveState.POPULATED else state

    Box(Modifier.fillMaxSize().pageBackdrop()) {
        Column(Modifier.fillMaxSize()) {
            // 顶栏：关闭 / 标题。保存不再放顶栏 —— 它吸在底部，永远可点（原型 .s14a-actions）
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = t.space2, vertical = t.space2),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 原型顶栏按钮是 .icon-btn（44×44 圆底 + 描边），不是裸图标
                FinanceIconButton(onClick = onDismiss, contentDescription = "取消") {
                    Icon(Icons.Filled.Close, contentDescription = null, tint = t.accent)
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
                edgeText = "边界态：超长分类名 / 超长说明 / 缺失分类字段",
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
                        AddTransactionAmountCard(
                            txType = txType,
                            amountText = amountText,
                            onTypeChange = { txType = it },
                            onAmountChange = { amountText = it },
                            isRefundEntry = isRefundEntry,
                        )

                        // ② 字段卡：分类 / 账户（转账为转出+转入）/ 时间 / 说明 —— 四行同构，与原型一致
                        Spacer(Modifier.height(t.space3))
                        AddTransactionFieldCard(
                            txType = txType,
                            selectedCategory = selectedCategory,
                            accountName = accountName,
                            toAccountName = toAccountName,
                            timeText = RecordForm.timeRowText(parts, today),
                            remark = remark,
                            onOpenSheet = { openSheet = it },
                            onRemarkChange = { remark = it },
                        )

                        // ③ 单据卡：只有自动记账记下的交易才带单据，手动录入不摆空行
                        AddTransactionReceiptCard(
                            editing = editing,
                            onOpenSheet = { openSheet = it },
                        )
                        // 给吸底保存条让位（原型 .s14a-actions 是 sticky，这里用等高的留白）
                        Spacer(Modifier.height(t.space12))
                    }

                    // ④ 保存吸底（原型 .s14a-actions）：表单怎么滚，它都在
                    AddTransactionActionBar(
                        isEditing = isEditing,
                        canSave = canSave,
                        showDelete = isEditing && onDelete != null,
                        onSave = { onSave(buildTransaction()) },
                        onDeleteClick = { showDeleteConfirm = true },
                        modifier = Modifier.align(Alignment.BottomCenter),
                    )
                }
            }
        }

        // 四个抽屉（互斥；分类 / 账户选中即关，时间由「确定」关，单据由「关闭」关）
        AddTransactionSheets(
            openSheet = openSheet,
            tree = tree,
            accounts = accounts,
            txType = txType,
            editing = editing,
            selectedCategory = selectedCategory,
            accountName = accountName,
            toAccountName = toAccountName,
            parts = parts,
            onClose = { openSheet = RecordSheet.NONE },
            onPickCategory = {
                selectedCategory = it
                openSheet = RecordSheet.NONE
            },
            onPickAccount = {
                accountName = it
                // 转出与转入撞车时自动改掉转入，避免「同一账户进出」这种无意义的转账
                if (toAccountName == it) {
                    toAccountName = accounts.firstOrNull { acc -> acc.name != it }?.name ?: ""
                }
                openSheet = RecordSheet.NONE
            },
            onPickToAccount = {
                toAccountName = it
                openSheet = RecordSheet.NONE
            },
            onPickDate = { parts = parts.copy(date = it) },
            onPickHourMinute = { hour, minute -> parts = parts.copy(hour = hour, minute = minute) },
        )

        FinanceStateBar(
            control = control,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(t.space4),
        )

        // 删除确认（原型 s05 #s05-del-dialog）
        AddTransactionDeleteDialog(
            visible = showDeleteConfirm,
            editing = editing,
            onDelete = { tx ->
                showDeleteConfirm = false
                onDelete?.invoke(tx)
            },
            onDismiss = { showDeleteConfirm = false },
        )
    }
}
