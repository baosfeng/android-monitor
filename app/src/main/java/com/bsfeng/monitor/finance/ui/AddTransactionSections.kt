package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/AddTransactionScreen.kt 的三张卡；职责：类型+金额卡、字段卡（分类/账户/时间/说明）、单据卡。

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.SegmentedControl
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 类型 + 金额卡（原型 s14a-amount）。 */
@Composable
internal fun AddTransactionAmountCard(
    txType: Int,
    amountText: String,
    onTypeChange: (Int) -> Unit,
    onAmountChange: (String) -> Unit,
    isRefundEntry: Boolean,
) {
    val t = LocalDesignTokens.current
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
                onSelect = onTypeChange,
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
                        onAmountChange(input.filter { c -> c.isDigit() || c == '.' })
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
                        androidx.compose.foundation.layout.Box {
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
}

/**
 * 字段卡：分类 / 账户（转账为转出+转入）/ 时间 / 说明 —— 四行同构，与原型一致。
 *
 * 每一行都通过回调上抛字段标识，由屏幕层决定打开哪个抽屉。
 */
@Composable
internal fun AddTransactionFieldCard(
    txType: Int,
    selectedCategory: String,
    accountName: String,
    toAccountName: String,
    timeText: String,
    remark: String,
    onOpenSheet: (RecordSheet) -> Unit,
    onRemarkChange: (String) -> Unit,
) {
    val t = LocalDesignTokens.current
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
                    onClick = { onOpenSheet(RecordSheet.CATEGORY) },
                )
                FieldDivider()
            }
            FieldRow(
                label = if (isTransferType(txType)) "转出" else "账户",
                value = accountName,
                placeholder = "选择账户",
                onClick = { onOpenSheet(RecordSheet.ACCOUNT) },
            )
            if (isTransferType(txType)) {
                FieldDivider()
                FieldRow(
                    label = "转入",
                    value = toAccountName,
                    placeholder = "选择转入账户",
                    onClick = { onOpenSheet(RecordSheet.TO_ACCOUNT) },
                )
            }
            FieldDivider()
            FieldRow(
                label = "时间",
                value = timeText,
                onClick = { onOpenSheet(RecordSheet.TIME) },
            )
            FieldDivider()
            NoteRow(
                value = remark,
                onValueChange = { onRemarkChange(it.take(REMARK_MAX_LEN)) },
            )
        }
    }
}

/** 单据卡：只有自动记账记下的交易才带单据，手动录入不摆空行。 */
@Composable
internal fun AddTransactionReceiptCard(
    editing: Transaction?,
    onOpenSheet: (RecordSheet) -> Unit,
) {
    if (!RecordForm.hasReceipt(editing)) return
    val t = LocalDesignTokens.current
    Spacer(Modifier.height(t.space3))
    AppCard(padding = 0.dp) {
        Column(
            Modifier.padding(horizontal = t.space4, vertical = t.space3),
        ) {
            FieldRow(
                label = "单据",
                value = RecordForm.receiptSummary(editing),
                onClick = { onOpenSheet(RecordSheet.RECEIPT) },
            )
        }
    }
}
