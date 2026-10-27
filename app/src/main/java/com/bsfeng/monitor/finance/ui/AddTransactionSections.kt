package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/AddTransactionScreen.kt 的三张卡；职责：类型+金额卡、字段卡（分类/账户/时间/说明）、单据卡。
// 契约锚点（s14a）：s14a-amount / s14a-kind（含三段 s14a-kind-*）/ s14a-fields / s14a-cat-row /
// s14a-acct-row / s14a-time-row / s14a-note-row / s14a-receipt / s14a-receipt-row 都落在本文件。

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.segmentNavTarget
import com.bsfeng.monitor.ui.components.tokenShadow
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/**
 * 类型分段的逐段锚点（契约 s14a-kind-expense / -income / -transfer，顺序同 [RecordForm.TYPE_OPTIONS]）。
 *
 * 写成 when 而不是 List：每个锚点都要以 testTag 的字面形态被 grep 逐字查到（验收口径）。
 */
private fun kindAnchor(index: Int): Modifier = when (index) {
    0 -> Modifier.testTag("s14a-kind-expense")
    1 -> Modifier.testTag("s14a-kind-income")
    else -> Modifier.testTag("s14a-kind-transfer")
}

/** 分段容器内边距（与共享 SegmentedControl 同口径：.segmented padding 4px） */
private val KIND_SEG_PAD = 4.dp

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
    GlassCard(padding = 0.dp, modifier = Modifier.testTag("s14a-amount")) {
        Column(
            Modifier.padding(
                start = t.space4,
                end = t.space4,
                top = t.space3,
                bottom = t.space3,
            ),
        ) {
            KindSegment(
                txType = txType,
                onTypeChange = onTypeChange,
                modifier = Modifier.testTag("s14a-kind"),
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
}

/**
 * 收支类型分段「支出／收入／转账」（契约 s14a-kind + 三段逐段锚点）。
 *
 * 为什么本页不复用共享 [com.bsfeng.monitor.ui.components.SegmentedControl]：契约要求**逐段**锚点
 * （s14a-kind-expense / -income / -transfer），而共享件不暴露 per-item modifier。此处按同一 token
 * 口径重构，键盘行为复用共享的 [segmentNavTarget] + [keyboardFocusRing]（roving tabindex）。
 * 待共享件补 itemModifiers 形参后删掉本副本。
 */
@Composable
private fun KindSegment(
    txType: Int,
    onTypeChange: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val options = RecordForm.TYPE_OPTIONS
    val shape = RoundedCornerShape(t.radiusPill)
    // 每段一个 requester：方向键移动后把焦点送到新选中段（否则焦点留在旧段、环画错位置）
    val requesters = remember(options.size) { List(options.size) { FocusRequester() } }
    // 键盘切换后的焦点请求不能在按键栈里零帧发：此刻新段 canFocus 还是 false
    // （下面 focusProperties 的 canFocus 要等重组才按新的 txType 重算），请求当场落空；
    // 重组后旧段 canFocus=false、请求也落空 → 焦点被清空，第一次切换后方向键再也到不了分段。
    // 口径照 ui/components/Chips.kt:179-185 与 ui/MainScreen.kt:358-364：先记「下次该聚焦谁」，
    // 等重组把 canFocus 算完，再在 keyed effect 里请求（LaunchedEffect 在组件树应用之后才启动）。
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingFocusIndex) {
        pendingFocusIndex?.let { index ->
            runCatching { requesters.getOrNull(index)?.requestFocus() }
            pendingFocusIndex = null
        }
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(t.tapMin + KIND_SEG_PAD * 2)
            .clip(shape)
            .background(t.fgFade(5))
            .border(1.dp, t.borderSoft, shape)
            .padding(KIND_SEG_PAD)
            .focusGroup()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val target = segmentNavTarget(event.key, txType, options.size)
                    ?: return@onPreviewKeyEvent false
                if (target != txType) {
                    haptic(HapticTone.LIGHT)
                    onTypeChange(target)
                }
                // 不在按键栈里零帧 requestFocus：先切状态、记下目标段，请求交给重组后的 effect
                pendingFocusIndex = target
                // 导航键一律吞掉：到边界不回绕，也不让父层滚动跟着跑
                true
            },
        horizontalArrangement = Arrangement.spacedBy(KIND_SEG_PAD),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        options.forEachIndexed { index, label ->
            val selected = index == txType
            val bg by animateColorAsState(
                targetValue = if (selected) t.surface else Color.Transparent,
                animationSpec = tween(t.durState),
                label = "kindBg",
            )
            val fg by animateColorAsState(
                targetValue = if (selected) t.fg else t.muted,
                animationSpec = tween(t.durState),
                label = "kindFg",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(t.tapMin)
                    .focusRequester(requesters[index])
                    // roving tabindex：只有选中段可聚焦 → Tab 进组一次，方向键由组统一处理
                    .focusProperties { canFocus = selected }
                    .keyboardFocusRing(shape)
                    .tokenShadow(if (selected) 1 else 0, shape)
                    .clip(shape)
                    .background(bg)
                    .then(kindAnchor(index))
                    .clickable {
                        // 切换属轻触级；重复点当前段不再震（契约「已在支出态时再点无变化、不重复触觉」）
                        if (!selected) {
                            haptic(HapticTone.LIGHT)
                            onTypeChange(index)
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = fg,
                )
            }
        }
    }
}

/**
 * 字段卡：分类 / 账户（转账为转出+转入）/ 时间 / 说明 —— 四行同构，与原型一致。
 *
 * 每一行都通过回调上抛字段标识，由屏幕层决定打开哪个抽屉；
 * [catFocus] / [acctFocus] / [timeFocus] 是抽屉关闭后焦点回行的落点（契约「移除/关闭后」列）。
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
    catFocus: FocusRequester? = null,
    acctFocus: FocusRequester? = null,
    timeFocus: FocusRequester? = null,
) {
    val t = LocalDesignTokens.current
    AppCard(padding = 0.dp, modifier = Modifier.testTag("s14a-fields")) {
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
                    modifier = Modifier.testTag("s14a-cat-row"),
                    focusRequester = catFocus,
                )
                FieldDivider()
            }
            FieldRow(
                label = if (isTransferType(txType)) "转出" else "账户",
                value = accountName,
                placeholder = "选择账户",
                onClick = { onOpenSheet(RecordSheet.ACCOUNT) },
                modifier = Modifier.testTag("s14a-acct-row"),
                focusRequester = acctFocus,
            )
            if (isTransferType(txType)) {
                FieldDivider()
                // 转账的「转入」行沿用「账户」行语义，但不另贴锚点：一个节点只能有一个 testTag，
                // 契约 s14a-acct-row 的「转账态本行标签变「转出」并另出一行「转入」」由本行承接交互
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
                modifier = Modifier.testTag("s14a-time-row"),
                focusRequester = timeFocus,
            )
            FieldDivider()
            NoteRow(
                value = remark,
                onValueChange = { onRemarkChange(it.take(REMARK_MAX_LEN)) },
                modifier = Modifier.testTag("s14a-note-row"),
            )
        }
    }
}

/** 单据卡：只有自动记账记下的交易才带单据，手动录入不摆空行。 */
@Composable
internal fun AddTransactionReceiptCard(
    editing: Transaction?,
    onOpenSheet: (RecordSheet) -> Unit,
    receiptFocus: FocusRequester? = null,
) {
    if (!RecordForm.hasReceipt(editing)) return
    val t = LocalDesignTokens.current
    Spacer(Modifier.height(t.space3))
    AppCard(padding = 0.dp, modifier = Modifier.testTag("s14a-receipt")) {
        Column(
            Modifier.padding(horizontal = t.space4, vertical = t.space3),
        ) {
            FieldRow(
                label = "单据",
                value = RecordForm.receiptSummary(editing),
                onClick = { onOpenSheet(RecordSheet.RECEIPT) },
                modifier = Modifier.testTag("s14a-receipt-row"),
                focusRequester = receiptFocus,
            )
        }
    }
}
