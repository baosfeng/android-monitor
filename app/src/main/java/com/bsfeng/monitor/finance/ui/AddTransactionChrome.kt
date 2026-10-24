package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/AddTransactionScreen.kt 的顶栏与抽屉分发；职责：关闭/标题顶栏、四个互斥抽屉的装配、删除确认弹窗。

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bsfeng.monitor.finance.CategoryTree
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.components.ConfirmDialog
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
    onPickDate: (String) -> Unit,
    onPickHourMinute: (Int, Int) -> Unit,
) {
    when (openSheet) {
        RecordSheet.CATEGORY -> CategorySheet(
            visible = true,
            tree = tree,
            type = txType,
            selectedName = selectedCategory,
            onPick = onPickCategory,
            onDismiss = onClose,
        )

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

        RecordSheet.TIME -> TimeSheet(
            visible = true,
            parts = parts,
            onDateChange = onPickDate,
            onHourMinuteChange = onPickHourMinute,
            onDismiss = onClose,
        )

        RecordSheet.RECEIPT -> ReceiptSheet(
            visible = true,
            transaction = editing,
            onDismiss = onClose,
        )

        RecordSheet.NONE -> Unit
    }
}

/**
 * 删除确认（原型 s05 #s05-del-dialog）。
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
    ConfirmDialog(
        visible = true,
        title = "删除这笔交易？",
        message = "删除后无法恢复，预算与统计会同步更新。",
        confirmLabel = "删除",
        dismissLabel = "取消",
        onConfirm = { onDelete(editing) },
        onDismiss = onDismiss,
    )
}
