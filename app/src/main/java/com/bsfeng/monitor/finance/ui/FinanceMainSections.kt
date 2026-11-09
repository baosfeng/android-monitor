package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceMainScreen.kt 的三段列表项；职责：页头导入按钮、月份条、KPI 卡、双入口卡、明细标题行。
//
// s04 口径（锚点账本）要求**逐控件锚点 + 逐控件按下反馈**，
// 共享壳件 FinanceScreenChrome / FinanceMainCards 没有可注入的 interactionSource，
// 因此本文件按共享基元（PressFeedback / Haptics / FocusRing）就地组合出本页专属部件；
// 与共享件的关系：按共享基元复用，按下尺度与基建唯一取值 0.96 的差 ≤0.03。


import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Savings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.Budget
import com.bsfeng.monitor.finance.model.FinanceStats
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.GlassCard
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.animatedAmount
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.springOf
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade

/** 月份标签弹回时的起点缩放（锚点 s04-prev/s04-next：月份文字从 0.86 弹回 1）。 */
private const val MONTH_LABEL_BUMP_FROM = 0.86f

/**
 * 页头右侧「数据导入」圆形图标按钮（锚点 s04-import）。
 *
 * 44×44（tap-min）、accent 下载箭头、按下走共享基元 [pressFeedback]（0.96 / --dur-press，精简档自动省略）
 * + LIGHT 触觉。口径写的 0.94 与基建唯一取值 0.96 的差记在交接文档。
 */
@Composable
internal fun S04ImportButton(onClick: () -> Unit) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusPill)
    Box(
        modifier = Modifier
            .testTag("s04-import")
            .size(t.tapMin)
            .pressFeedback(interaction)
            .clip(shape)
            .background(t.fgFade(5))
            .border(1.dp, t.borderSoft, shape)
            .keyboardFocusRing(shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                haptic(HapticTone.LIGHT)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Filled.Download,
            contentDescription = "数据导入",
            tint = t.accent,
            modifier = Modifier.size(t.iconSize),
        )
    }
}

/**
 * 月份条（锚点 s04-monthbar / s04-prev / s04-next）。
 *
 * 胶囊自身不接收点击（口径：点胶囊中部或描边无任何反馈），只有两侧 44dp 箭头是命中目标；
 * 胶囊高 52dp 由 44dp 箭头 + 上下各 space-1（4dp）自然撑出。
 */
@Composable
internal fun S04MonthBar(
    monthText: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val bump = remember { Animatable(1f) }
    LaunchedEffect(monthText) {
        bump.snapTo(MONTH_LABEL_BUMP_FROM)
        bump.animateTo(1f, t.springOf(MotionToken.Press))
    }
    val shape = RoundedCornerShape(t.radiusPill)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4, vertical = t.space2)
            .testTag("s04-monthbar")
            .clip(shape)
            .background(t.fgFade(4))
            .border(1.dp, t.borderSoft, shape)
            .padding(t.space1),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        S04MonthArrow(tag = "s04-prev", description = "上个月", onClick = onPrevious) { label ->
            Icon(
                Icons.Filled.ChevronLeft,
                contentDescription = label,
                tint = t.accent,
                modifier = Modifier.size(t.iconSize),
            )
        }
        Text(
            monthText,
            style = MaterialTheme.typography.titleLarge,
            color = t.fg,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.graphicsLayer {
                scaleX = bump.value
                scaleY = bump.value
            },
        )
        S04MonthArrow(tag = "s04-next", description = "下个月", onClick = onNext) { label ->
            Icon(
                Icons.Filled.ChevronRight,
                contentDescription = label,
                tint = t.accent,
                modifier = Modifier.size(t.iconSize),
            )
        }
    }
}

/**
 * 月份条单侧箭头：48dp 命中区、按下 [pressFeedback] + LIGHT。
 *
 * 可读名只有一处：[description] 交给内容 lambda 落到图标上（同 [FinanceIconButton] 的口径）。
 */
@Composable
private fun S04MonthArrow(
    description: String,
    onClick: () -> Unit,
    tag: String,
    modifier: Modifier = Modifier,
    content: @Composable (String?) -> Unit,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .size(t.tapMin)
            .testTag(tag)
            .pressFeedback(interaction)
            .clip(RoundedCornerShape(t.radiusPill))
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                haptic(HapticTone.LIGHT)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) { content(description) }
}

/**
 * KPI 卡 + 今日一行摘要（锚点 s04-kpis / s04-ring）。
 *
 * 卡片与圆环都不可点（口径：无按下反馈、点击不跳转），锚点只做定位用。
 * 金额走 [animatedAmount] 做滚动数字；预算进度用 accent（与 BudgetScreen 同一口径）。
 *
 * @param budgetEnabled 「预算与预警」开关（[com.bsfeng.monitor.ui.foundation.FinanceUiToggle.BUDGET]）：
 *   默认关。关掉时 s04-ring（预算环 + 环心「预算」字样）整个不渲染，KPI 三行独占整卡宽度；
 *   卡片底部的「预算使用 X%」也随之换成笔数口径，不留半句预算文案。
 * @param anchorTagged 是否挂 s04-kpis / s04-ring 锚点。统计汇总 pane（审计 P2-3）也渲染同一张三值卡，
 *   而锚点账本已冻结、同名 testTag 不宜在第二处复用；那份传 false，卡片内容与交互不变，
 *   只少一个定位锚点（卡上没有任何可点元素，读屏直接读文本，不违反无障碍基线）。
 */
@Composable
internal fun FinanceMainKpiSection(
    stats: FinanceStats,
    today: Pair<Double, Double>,
    currentMonth: String,
    transactionCount: Int,
    budgetEnabled: Boolean,
    anchorTagged: Boolean = true,
) {
    val t = LocalDesignTokens.current
    Column(Modifier.padding(horizontal = t.space4, vertical = t.space2)) {
        GlassCard(
            modifier = if (anchorTagged) Modifier.testTag("s04-kpis") else Modifier,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(t.space2),
                ) {
                    KpiLine(
                        label = "本月支出",
                        value = yen(animatedAmount(stats.totalExpense.toFloat()).toDouble(), 0),
                        color = t.expenseText,
                    )
                    KpiLine(
                        label = "本月收入",
                        value = yen(animatedAmount(stats.totalIncome.toFloat()).toDouble(), 0),
                        color = t.incomeText,
                    )
                    KpiLine(
                        label = "本月结余",
                        // 负结余走「¥-512」口径：yen() 的 %,.2f 本身不带括号
                        value = yen(animatedAmount(stats.balance.toFloat()).toDouble(), 0),
                        color = t.fg,
                    )
                }
                // 开关关掉时连 Spacer 一起不渲染：否则三行 KPI 右侧会留一段没有内容的空隙
                if (budgetEnabled) {
                    Spacer(Modifier.width(t.space3))
                    Box(if (anchorTagged) Modifier.testTag("s04-ring") else Modifier) {
                        FinanceRingGauge(
                            // 口径：无预算 0% / 超 100% 环描满一圈、环心仍显示真实值
                            fraction = stats.budgetProgress,
                            big = ((stats.budgetProgress * 100).toInt()).toString() + "%",
                            small = "预算",
                            // 预算进度 = accent（蓝）：原型 s04/s06 circle.chart-gauge 实测
                            // stroke rgb(0,113,227) = --accent（设计规范 §2.4），与 BudgetScreen 同一口径
                            color = t.accent,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(t.space3))
        Text(
            if (isCurrentMonth(currentMonth)) {
                "今日 支出 " + yen(today.first, 0) +
                    " · 收入 " + yen(today.second, 0) +
                    " · 结余 " + yen(today.second - today.first, 0)
            } else if (budgetEnabled) {
                "共 " + transactionCount + " 笔 · 预算使用 " +
                    (stats.budgetProgress * 100).toInt() + "%"
            } else {
                // 预算关着时这行不留「预算使用 X%」：环都没有了，再报一个百分比会让人找不到出处
                "共 " + transactionCount + " 笔"
            },
            style = MaterialTheme.typography.labelSmall,
            color = t.meta,
        )
    }
}

/**
 * 双入口卡：统计分析 / 预算设置（锚点 s04-entries / s04-entry-stats / s04-entry-budget）。
 *
 * 两卡之间 12dp 间隙（space-3）不属于任何卡，点击无反馈；整卡按下 [pressFeedback] + LIGHT。
 */
@Composable
internal fun FinanceMainEntrySection(
    overallBudget: Budget?,
    onStatsClick: () -> Unit,
    onBudgetClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("s04-entries")
            .padding(horizontal = t.space4, vertical = t.space2),
        horizontalArrangement = Arrangement.spacedBy(t.space3),
    ) {
        S04EntryCard(
            title = "统计分析",
            subtitle = "分类占比 · 近 7 天",
            modifier = Modifier
                .weight(1f)
                .testTag("s04-entry-stats"),
            onClick = onStatsClick,
            icon = {
                Icon(
                    Icons.Filled.BarChart,
                    contentDescription = null,
                    tint = t.fg2,
                    // 原型 .s04-entry-ic svg = 19px，取就近图标 token（IconSize.md = 20dp）
                    modifier = Modifier.size(20.dp),
                )
            },
        )
        S04EntryCard(
            title = "预算设置",
            subtitle = if (overallBudget != null && overallBudget.budgetAmount > 0) {
                "已用 " + yen(overallBudget.spentAmount, 0) + " / " + yen(overallBudget.budgetAmount, 0)
            } else {
                "尚未设置本月预算"
            },
            modifier = Modifier
                .weight(1f)
                .testTag("s04-entry-budget"),
            onClick = onBudgetClick,
            icon = {
                Icon(
                    Icons.Filled.Savings,
                    contentDescription = null,
                    tint = t.fg2,
                    modifier = Modifier.size(20.dp),
                )
            },
        )
    }
}

/**
 * 双入口单卡：视觉沿用 [AppCard]，点击与按下反馈由本层持有（[AppCard] 内部的 clickable
 * 用自建 interactionSource，外部无法共用 → 收不到 Press 事件）。整卡命中、按下 0.96 + LIGHT。
 */
@Composable
private fun S04EntryCard(
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: @Composable () -> Unit,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusLg)
    AppCard(
        modifier = modifier
            // 原型 .s04-entry：min-height 104px；窄屏下两卡并排不换行
            .heightIn(min = 104.dp)
            .clip(shape)
            .pressFeedback(interaction)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) {
                haptic(HapticTone.LIGHT)
                onClick()
            },
        padding = t.space4,
    ) {
        Box(
            modifier = Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(t.radiusMd))
                .background(t.fgFade(6)),
            contentAlignment = Alignment.Center,
        ) { icon() }
        Spacer(Modifier.height(t.space2))
        Text(
            title,
            style = MaterialTheme.typography.titleSmall,
            color = t.fg,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag("card.entry"),
        )
        Spacer(Modifier.height(t.space1))
        Text(subtitle, style = MaterialTheme.typography.bodySmall, color = t.muted)
    }
}

/**
 * 明细标题行（锚点 s04-list）：左「交易明细」+ 右「最近 3 天 · X 笔 / 共 X 笔 / 暂无记录」。
 *
 * 标题与计数都不可点（口径：无按下反馈）；[modifier] 供调用方挂焦点请求
 * （锚点 s04-retry「错态卡消失后焦点回明细区标题」）。
 */
@Composable
internal fun FinanceMainListHead(
    currentMonth: String,
    groupedIsEmpty: Boolean,
    recentCount: Int,
    transactionCount: Int,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("s04-list")
            .padding(horizontal = t.space4, vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "交易明细",
            style = MaterialTheme.typography.headlineSmall,
            color = t.fg,
            modifier = Modifier.weight(1f),
        )
        Text(
            // 原型 s04-listmeta（s04-finance-home.html:95 / :319）：当月 =「最近 3 天 · N 笔」，
            // 其它月份 =「共 N 笔」（N = 当月总笔数）。
            if (groupedIsEmpty) {
                "暂无记录"
            } else if (isCurrentMonth(currentMonth)) {
                "最近 3 天 · " + recentCount + " 笔"
            } else {
                "共 " + transactionCount + " 笔"
            },
            style = MaterialTheme.typography.labelSmall,
            color = t.meta,
        )
    }
}
