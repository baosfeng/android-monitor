package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceMainScreen.kt 的明细 lazy items；职责：按日分组的日期头 + 每笔交易行 + 空态/异常态。

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Download
import androidx.compose.material3.DividerDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.STAGGER_MAX_STEPS
import com.bsfeng.monitor.ui.components.StateContainer
import com.bsfeng.monitor.ui.theme.DesignTokens

/**
 * 明细列表的 lazy items。
 *
 * J1：整月明细做成**行级** lazy items —— 日期头一个 item、每笔交易一个 item，
 * 只有可见行会组合；入场动画只给首屏可见的若干行。
 * 卡容器（原型 .group）不再由单个 item 承载，改由每行自己拼出：见 [DayGroupRow]。
 */
internal fun LazyListScope.financeMainTransactionItems(
    state: FiveState,
    grouped: Map<String, List<Transaction>>,
    highlightId: Long,
    tokens: DesignTokens,
    onRowClick: (Transaction) -> Unit,
    onAddClick: () -> Unit,
    onRetry: () -> Unit,
) {
    var rowIndex = 0
    when (state) {
        FiveState.POPULATED -> grouped.forEach { (date, txs) ->
            item(key = "day-" + date) {
                // 日期解析 + LocalDate.now() 只在挂载时算一次；
                // 「今天/昨天」跨零点不会自动刷新 —— 本屏每次进入 Tab / 回前台都会重新 loadMonth 并重组，
                // 可接受；若要求跨零点实时，应换成「每天变一次」的日期状态源。
                val label = remember(date) { groupLabel(date) }
                Text(
                    label,
                    style = MaterialTheme.typography.labelMedium,
                    color = tokens.muted,
                    modifier = Modifier
                        .padding(horizontal = tokens.space4)
                        .padding(top = tokens.space2, start = tokens.space2),
                )
            }
            txs.forEachIndexed { index, tx ->
                // key 只用主键：finance_transactions.id 是 INTEGER PRIMARY KEY AUTOINCREMENT，
                // toContentValues 不写 id（导入同样走自增），同一查询内 id 必然唯一。
                // 带组内下标会让「插入/删除一笔」把该日后续行的 key 全改掉 → 整组丢状态、重播入场动画。
                item(key = "tx-" + tx.id) {
                    DayGroupRow(
                        first = index == 0,
                        last = index == txs.lastIndex,
                    ) {
                        TransactionRow(
                            transaction = tx,
                            color = categoryColorOf(tx.categoryName),
                            highlighted = tx.id == highlightId,
                            enterIndex = if (rowIndex < STAGGER_MAX_STEPS) rowIndex++ else -1,
                            onClick = { onRowClick(tx) },
                        )
                        if (index != txs.lastIndex) {
                            HorizontalDivider(
                                thickness = DividerDefaults.Thickness,
                                color = tokens.borderSoft,
                                modifier = Modifier.padding(start = tokens.space8),
                            )
                        }
                    }
                }
            }
        }

        // 原型 s04-empty（s04:161-165）：标题 + 说明 + 「新增一笔」
        FiveState.EMPTY -> item(key = "empty") {
            Column(Modifier.padding(horizontal = tokens.space4)) {
                FinanceEmptyState(
                    title = "本月还没有交易",
                    desc = "点右下角「+」记下第一笔，或从熊猫记账导入历史账目",
                    icon = Icons.Filled.Download,
                    actionText = "新增一笔",
                    onAction = onAddClick,
                )
            }
        }

        else -> item(key = "state") {
            StateContainer(
                state = state,
                modifier = Modifier.padding(horizontal = tokens.space4),
                emptyText = "点右下角「+」记下第一笔，或从熊猫记账导入历史账目",
                errorText = "本地数据库读取超时，没能取到交易记录",
                edgeText = "极限数据：单月 10,240 笔 · 单笔最大 ¥1,234,567.89",
                onRetry = onRetry,
            ) { }
        }
    }
}
