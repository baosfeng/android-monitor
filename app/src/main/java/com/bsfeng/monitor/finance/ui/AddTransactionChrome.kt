package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/AddTransactionScreen.kt 的顶栏与抽屉分发；职责：关闭/标题顶栏、四个互斥抽屉的装配、删除确认弹窗。
// 契约锚点（s14a）：s14a-cat-sheet / s14a-cat-grid / s14a-receipt-sheet / s14a-receipt-close 在此装配；
// 账户与时间抽屉的锚点在 RecordSheetAccountSheet / RecordSheetTimeSheet。（s05）s05-del-dialog / -cancel / -confirm。

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.CategoryTree
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 顶栏：关闭 / 标题。保存不在顶栏 —— 它吸在底部，永远可点（原型 .s14a-actions）。 */
@Composable
internal fun AddTransactionTopBar(
    isEditing: Boolean,
    onDismiss: () -> Unit,
    onToggleStateBar: () -> Unit,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = t.space2, vertical = t.space2),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 原型顶栏按钮是 .icon-btn（44×44 圆底 + 描边），不是裸图标
        FinanceIconButton(onClick = onDismiss, contentDescription = "取消") { label ->
            Icon(Icons.Filled.Close, contentDescription = label, tint = t.accent)
        }
        Text(
            if (isEditing) "编辑交易" else "记一笔",
            style = MaterialTheme.typography.titleLarge,
            color = t.fg,
            modifier = Modifier
                .testTag("title.toggle")
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ) { onToggleStateBar() },
        )
        // 占位，让标题保持居中（与左侧关闭按钮等宽）
        Spacer(Modifier.size(t.tapMin))
    }
}

/**
 * 四个抽屉（互斥；分类 / 账户选中即关，时间由「确定」关，单据由「关闭」关）。
 *
 * 放在屏幕层是为了让每个抽屉的「选中即关」语义与表单状态变更一次写清。
 * 分类与单据抽屉在本层装配：契约锚点 s14a-cat-sheet / s14a-cat-grid / s14a-receipt-sheet /
 * s14a-receipt-close 需要挂在面板内容上，而共用外壳 [SheetShell] 由共享层维护、不透出 modifier。
 */
@Composable
internal fun BoxScope.AddTransactionSheets(
    openSheet: RecordSheet,
    tree: CategoryTree,
    accounts: List<Account>,
    txType: Int,
    editing: Transaction?,
    selectedCategory: String,
    accountName: String,
    toAccountName: String,
    parts: RecordForm.TimeParts,
    onClose: () -> Unit,
    onPickCategory: (String) -> Unit,
    onPickAccount: (String) -> Unit,
    onPickToAccount: (String) -> Unit,
    onConfirmTime: (RecordForm.TimeParts) -> Unit,
) {
    when (openSheet) {
        RecordSheet.CATEGORY -> SheetShell(title = "选择分类", onDismiss = onClose) {
            Box(Modifier.fillMaxWidth().testTag("s14a-cat-sheet")) {
                CategoryPicker(
                    tree = tree,
                    type = txType,
                    selectedName = selectedCategory,
                    onPick = onPickCategory,
                    // 锚点 s14a-cat-grid：一级 6 列网格 + 二级网格同屏都在这个节点下
                    modifier = Modifier.testTag("s14a-cat-grid"),
                    // 一级 18 个用 6 列网格完整展示（3 行）；二级最多 2 行，留出固定高度可滚动
                    gridMaxHeight = 140.dp,
                    iconSize = 32.dp,
                    topStyle = CategoryPickerTopStyle.GRID,
                )
            }
        }

        RecordSheet.ACCOUNT -> AccountSheet(
            visible = true,
            title = if (isTransferType(txType)) "选择转出账户" else "选择账户",
            accounts = accounts,
            selectedName = accountName,
            onPick = onPickAccount,
            onDismiss = onClose,
        )

        RecordSheet.TO_ACCOUNT -> AccountSheet(
            visible = true,
            title = "选择转入账户",
            accounts = accounts,
            selectedName = toAccountName,
            onPick = onPickToAccount,
            onDismiss = onClose,
        )

        // 时间抽屉自带草稿：只有「确定」通过 onConfirm 回写，遮罩 / 返回键走 onDismiss 丢弃
        RecordSheet.TIME -> TimeSheet(
            visible = true,
            parts = parts,
            onConfirm = onConfirmTime,
            onDismiss = onClose,
        )

        RecordSheet.RECEIPT -> SheetShell(title = "单据明细", onDismiss = onClose) {
            ReceiptBody(editing = editing, onClose = onClose)
        }

        RecordSheet.NONE -> Unit
    }
}

/** 单据标签列宽（与 RecordForm 的单据摘要行口径一致：与表单标签列同宽的两行结构） */
private val RECEIPT_LABEL_W = 64.dp

/**
 * 单据明细内容（契约 s14a-receipt-sheet / s14a-receipt-close）。
 *
 * 六行键值：商户 / 商品说明 / 付款方式 / 交易时间 / 订单号 / 平台分类；缺字段的行不显示，
 * 全部缺时给「这笔交易没有单据信息（手动录入的交易不带单据）」。
 */
@Composable
private fun ReceiptBody(editing: Transaction?, onClose: () -> Unit) {
    val t = LocalDesignTokens.current
    val items = RecordForm.receiptItems(editing)
    Column(Modifier.fillMaxWidth().testTag("s14a-receipt-sheet")) {
        items.forEach { (label, value) ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = t.space1),
                horizontalArrangement = Arrangement.spacedBy(t.space3),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.muted,
                    modifier = Modifier.width(RECEIPT_LABEL_W),
                )
                Text(
                    value,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.fg,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        if (items.isEmpty()) {
            Text(
                "这笔交易没有单据信息（手动录入的交易不带单据）",
                style = MaterialTheme.typography.bodySmall,
                color = t.muted,
            )
        }
        Spacer(Modifier.height(t.space3))
        // 次级按钮：关闭不是「确认」性质的动作，视觉层级要低于时间抽屉的「确定」（原型 .btn-ghost）
        Box(Modifier.testTag("s14a-receipt-close")) {
            SheetGhostButton(text = "关闭", onClick = onClose)
        }
    }
}

/**
 * 删除确认（s05-del-dialog / s05-del-cancel / s05-del-confirm）。
 *
 * 自绘浮层需显式接管返回键，关闭语义与点遮罩 / 取消一致（同项目既有写法：AdScreen / MainScreen）。
 */
@Composable
internal fun AddTransactionDeleteDialog(
    visible: Boolean,
    editing: Transaction?,
    onDelete: ((Transaction) -> Unit)?,
    onDismiss: () -> Unit,
) {
    if (!visible || editing == null || onDelete == null) return
    BackHandler { onDismiss() }
    FinanceConfirmDialog(
        title = "删除这笔交易？",
        message = "删除后无法恢复，预算与统计会同步更新。",
        confirmLabel = "删除",
        dismissLabel = "取消",
        onConfirm = { onDelete(editing) },
        onDismiss = onDismiss,
    )
}
