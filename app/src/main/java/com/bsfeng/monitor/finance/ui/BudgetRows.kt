package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/BudgetScreen.kt 的行组件与行选择；职责：KPI 文本行、单行预算进度、边界分类行、展示行的取行规则与展示常量。

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.rowPressFeedback
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.components.tweenOf
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/** 交易类型：支出 */
internal const val TYPE_EXPENSE = 0

/** 原型固定 6 条分类进度条 */
internal const val ROW_TARGET = 6

/** 展示用行：有预算的分类优先，不足 6 行时用其余支出分类补齐至 6 行。 */
internal fun budgetRowsFor(
    expenseCategories: List<Category>,
    budgetByCategory: Map<String, Budget>,
): List<Category> {
    val withBudget = expenseCategories.filter { budgetByCategory.containsKey(it.name) }
    val others = expenseCategories.filterNot { budgetByCategory.containsKey(it.name) }
    return if (withBudget.size >= ROW_TARGET) withBudget else (withBudget + others).take(ROW_TARGET)
}

/** 金额格式化（两位小数，与其余记账页面一致）。 */
internal fun formatAmount(value: Double): String =
    String.format(java.util.Locale.CHINA, "%.2f", value)

/**
 * 弹窗预填文本：契约要求「预填 1200 / 400 / 1000」——不带千分位、不留 `.0` 尾巴。
 * 1200.0 → `1200`；1200.5 → `1200.5`；0.0 → ``（未设置时留空，占位 `0.00`）。
 */
internal fun budgetInputText(value: Double): String = when {
    value <= 0.0 -> ""
    value == value.toLong().toDouble() -> value.toLong().toString()
    else -> java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString()
}

/**
 * 金额输入过滤（契约 s06-dialog · 输入）：只留数字与第一个小数点，整数位最多 9 位。
 * 返回过滤后的文本；非法字符直接丢弃（不弹错，由 [BudgetEditorDialog] 的校验行兜底）。
 */
internal fun filterBudgetAmountInput(raw: String): String {
    val out = StringBuilder()
    var dot = false
    var intDigits = 0
    raw.forEach { ch ->
        when {
            ch.isDigit() -> {
                if (!dot) {
                    if (intDigits >= 9) return@forEach
                    intDigits++
                }
                out.append(ch)
            }
            (ch == '.' || ch == '。') && !dot -> {
                dot = true
                out.append('.')
            }
        }
    }
    return out.toString()
}

/** 预算 KPI 行：`onClick != null` 时整行可点（按下底变 fg 5%、LIGHT、键盘 Enter/Space、2dp 焦点环）。 */
@Composable
internal fun BudgetKpiLine(
    label: String,
    value: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(t.radiusMd)
    // 契约 s06-card：按下时整行底色变 fg 5%（120ms = --dur-press）
    val background by animateColorAsState(
        targetValue = if (onClick != null && pressed) t.fgFade(5) else Color.Transparent,
        animationSpec = t.tweenOf(MotionToken.Press),
        label = "kpiRowBg",
    )
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(background)
            .then(
                if (onClick != null) {
                    Modifier
                        .keyboardFocusRing(shape)
                        .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                            haptic(HapticTone.LIGHT)
                            onClick()
                        }
                } else {
                    Modifier
                },
            )
            .padding(horizontal = t.space1, vertical = t.space1),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = t.meta)
        Text(value, style = MaterialTheme.typography.titleSmall, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * 单行预算：名称 + 预算额 + 进度条 + 已支出/剩余。
 *
 * 交互按契约 s06-brow-0…5：按下 0.99 + 透明度 .9（[rowPressFeedback]）、底变 fg 5%、
 * 触感 LIGHT、键盘 2dp 焦点环；行内进度条 600ms 长到比例（--dur-chart-fast）。
 */
@Composable
internal fun BudgetRow(
    title: String,
    budgetAmount: Double,
    spentAmount: Double,
    enterIndex: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(t.radiusMd)
    val hasBudget = budgetAmount > 0
    val progress = if (hasBudget) (spentAmount / budgetAmount).toFloat() else 0f
    val isOverBudget = hasBudget && spentAmount > budgetAmount
    val animatedProgress by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = t.tweenOf(MotionToken.ChartFast),
        label = "budgetBar",
    )
    val pressBg by animateColorAsState(
        targetValue = if (pressed) t.fgFade(5) else Color.Transparent,
        animationSpec = t.tweenOf(MotionToken.Press),
        label = "budgetRowBg",
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .staggerEnterAt(enterIndex)
            .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
            .rowPressFeedback(interaction)
            .clip(shape)
            .background(pressBg)
            .keyboardFocusRing(shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .padding(horizontal = t.space3, vertical = t.space3),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = t.fg,
                maxLines = 1,
                // 契约 s06-list：分类名超长单行省略号，不挤压右侧金额
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(t.space2))
            Text(
                if (hasBudget) yen(budgetAmount, 0) else "未设置",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = if (hasBudget) t.fg else t.muted,
            )
        }
        Spacer(Modifier.height(t.space2))
        BudgetProgressBar(fraction = if (hasBudget) animatedProgress else 0f, isOver = isOverBudget)
        Spacer(Modifier.height(t.space2))
        Text(
            when {
                !hasBudget -> "未设置预算"
                isOverBudget -> "已支出 " + yen(spentAmount, 0) + " · 超支 " + yen(spentAmount - budgetAmount, 0)
                else -> "已支出 " + yen(spentAmount, 0) + " · 剩余 " + yen(budgetAmount - spentAmount, 0)
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (isOverBudget) t.expenseText else t.meta,
        )
    }
}

/** 行内进度条（底 fg 9%，常态 chart-3 / 超支 chart-1 —— 原型 s06:34-38）。 */
@Composable
private fun BudgetProgressBar(fraction: Float, isOver: Boolean) {
    val t = LocalDesignTokens.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(t.space2)
            .clip(RoundedCornerShape(t.radiusPill))
            .background(t.fgFade(9)),
    ) {
        if (fraction > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(t.radiusPill))
                    .background(if (isOver) t.chart1 else t.chart3),
            )
        }
    }
}

/**
 * 边界分类行（契约 s06-edge-brow）：与普通分类行同一套交互，超支副行转红、名称单行省略号。
 * 数值是边界态占位数据（不读库）。
 */
@Composable
internal fun BudgetEdgeRow(
    title: String,
    budgetAmount: Double,
    spentAmount: Double,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shape = RoundedCornerShape(t.radiusMd)
    val pressBg by animateColorAsState(
        targetValue = if (pressed) t.fgFade(5) else Color.Transparent,
        animationSpec = t.tweenOf(MotionToken.Press),
        label = "edgeRowBg",
    )
    Column(
        modifier = modifier
            .fillMaxWidth()
            .rowPressFeedback(interaction)
            .clip(shape)
            .background(pressBg)
            .keyboardFocusRing(shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .padding(horizontal = t.space3, vertical = t.space3),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(t.space2))
            Text(yen(budgetAmount, 0), style = MaterialTheme.typography.titleSmall, color = t.fg, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(t.space2))
        BudgetProgressBar(fraction = 1f, isOver = true)
        Spacer(Modifier.height(t.space2))
        Text(
            "已支出 " + yen(spentAmount, 0) + " · 超支 " + yen(spentAmount - budgetAmount, 0),
            style = MaterialTheme.typography.bodySmall,
            color = t.expenseText,
        )
    }
}
