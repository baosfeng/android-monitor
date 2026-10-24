package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/RecordSheets.kt 的 ReceiptSheet；职责：单据明细抽屉（自动记账落库的结构化字段）。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 单据标签列宽（与表单标签列同宽，保证两处对齐） */
private val SHEET_LABEL_W: Dp = 64.dp

/** 单据明细（自动记账落库的结构化字段，原型 s14a-receipt-sheet 的 6 行） */
@Composable
internal fun BoxScope.ReceiptSheet(
    visible: Boolean,
    transaction: Transaction?,
    onDismiss: () -> Unit,
) {
    if (!visible) return
    val t = LocalDesignTokens.current
    val items = RecordForm.receiptItems(transaction)
    SheetShell(title = "单据明细", onDismiss = onDismiss) {
        Column(Modifier.fillMaxWidth()) {
            items.forEach { (label, value) ->
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = t.space1),
                    horizontalArrangement = Arrangement.spacedBy(t.space3),
                ) {
                    Text(
                        label,
                        style = MaterialTheme.typography.bodySmall,
                        color = t.muted,
                        modifier = Modifier.width(SHEET_LABEL_W),
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
            // 次级按钮：关闭不是「确认」性质的动作，视觉层级要低于时间抽屉的「确定」
            // （原型 s14a-receipt-close 用的就是 .btn-ghost）
            SheetGhostButton(text = "关闭", onClick = onDismiss)
        }
    }
}
