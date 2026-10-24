package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/AddTransactionScreen.kt 的取值规则；职责：交易类型判断、表单初始时间推导、金额字距与说明上限常量。

import androidx.compose.ui.unit.em
import com.bsfeng.monitor.finance.model.Transaction
import java.time.LocalDateTime
import java.time.YearMonth

/** 金额字距（原型 .s14a-input 的 letter-spacing:-.02em，56px 下约 -1.12px） */
internal val AMOUNT_LETTER_SPACING = (-0.02).em

/** 原型 s05-edge：说明 200 字上限 */
internal const val REMARK_MAX_LEN = 200

internal fun isTransferType(type: Int): Boolean = type == RecordForm.TYPE_TRANSFER

/** 编辑已有交易时的类型（供 [isTransfer] 之类的初值判断） */
internal fun txTypeOfValue(editing: Transaction?): Int = editing?.type ?: RecordForm.TYPE_EXPENSE

/**
 * 表单的初始时间。
 *
 * - 编辑：从交易的 date / dateTime 还原（dateTime 精确到秒）
 * - 新增：当前时刻；浏览月非当前月时日期取该月最后一天（见 [AddTransactionScreen] 的 fromMonth）
 */
internal fun initialTimeParts(editing: Transaction?, fromMonth: String?): RecordForm.TimeParts {
    val base = RecordForm.timePartsOf(editing?.date ?: "", editing?.dateTime ?: "", LocalDateTime.now())
    if (editing != null) return base
    val month = fromMonth?.takeIf { it.isNotBlank() && it != FinanceViewModel.currentMonthStr() }
    if (month == null) return base
    val last = runCatching { YearMonth.parse(month).atEndOfMonth().toString() }.getOrNull() ?: return base
    return base.copy(date = last)
}
