package com.bsfeng.monitor.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.text.Text
import com.bsfeng.monitor.MainActivity
import com.bsfeng.monitor.R

// 记账月度：三档内容 = 只支出（Tiny）/ 支出 + 预算进度（Compact）/ 支出 + 收入 + 预算剩余 + 进度（Wide）。
// 视觉走 C 版「柔和品牌色」：蓝 tint 卡底 + 蓝色图标块 + 底栏两块内嵌分区（见 WidgetStyle.kt）。

private const val LABEL_EXPENSE = "本月支出"
private const val LABEL_INCOME = "收入"
private const val LABEL_BUDGET_LEFT = "预算剩余"
private const val NO_BUDGET = "未设预算"
private const val OVER_BUDGET = "已超支"
private const val NO_VALUE = "—"

/** 2 格宽（2×1）在净高 ≥100dp 时才塞得下「标题 + 26sp 金额 + 进度条」三行。 */
private const val COMPACT_BAR_MIN_HEIGHT_DP = 100f

/** 4 格宽要到 ≥130dp 才同时放得下进度条与底部两块分区（4×1 的 ~78dp 净高会溢出）。 */
private const val WIDE_BAR_MIN_HEIGHT_DP = 110f

/**
 * 进度条右侧文案：「已用 71%」；满额后改说「已超支」。
 *
 * 为什么不在 100% 以上继续报百分比：[percent] 会把 1.4 夹成 100，
 * 于是「已用 100%」既可能是刚好花完、也已经是超支，桌面上一眼分不出来。
 */
internal fun usedText(progress: Float): String =
    if (progress >= 1f) OVER_BUDGET else "已用 " + percent(progress) + "%"

/**
 * Wide 底栏「预算剩余」列的值。
 *
 * 规则与 [budgetText] 同源（未设预算 → 已超支 → 金额 → 百分比），只差一处：
 * 金额分支不带「剩」前缀 —— 分区标题已经写了「预算剩余」，再加「剩」就成了「剩 剩 ¥1,713.50」。
 * [hidden] 时连金额分支都不走（隐私：桌面是唯一可见面，隐藏金额不能靠百分比反推出金额）。
 */
internal fun remainingText(
    progress: Float?,
    remaining: Double?,
    hasBudget: Boolean,
    hidden: Boolean,
): String {
    if (!hasBudget) return NO_BUDGET
    if (progress != null && progress >= 1f) return OVER_BUDGET
    if (!hidden && remaining != null) return money(remaining, hidden = false)
    return progress?.let { percent(1f - it).toString() + "%" } ?: NO_VALUE
}

/**
 * 记账月度概览。
 *
 * 数据在 [provideGlance] 里先读、再进 composition：`provideContent` 的 lambda 会在任意线程被重新组合，
 * 里面出现 IO 会直接体现在首帧耗时上（「点了组件没反应」多半是这么来的）。
 * 「隐藏金额」开关同理 —— SharedPreferences 的一次同步读留给 IO 线程，不进 composable。
 */
class FinanceWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetDataProvider.finance(context)
        val hidden = WidgetSettings.isAmountHidden(context)
        val box = widgetBox(context, id)
        provideContent { FinanceCard(data, hidden, box) }
    }
}

class FinanceWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FinanceWidget()
}

/**
 * 按 [tierOf] 分三档渲染（判档看宽度，为什么这么判见 WidgetLayoutPolicy）。
 *
 * 内容为什么这样切：
 * - Tiny（<140dp，1 格宽）：图标 + 「本月支出」+ 整数金额（小数在 80dp 宽里会被截成省略号）；
 * - Compact（140–280dp，2 格宽 / 2×1）：支出 + 预算进度条，右侧跟「已用 71%」；
 * - Wide（≥280dp，4 格宽 / 4×2）：主数值放大到 40sp，底栏两块内嵌分区（收入 / 预算剩余）压到底，
 *   让 350×200dp 的卡面不留大片空白。
 */
@Composable
private fun FinanceCard(data: FinanceWidgetData, hidden: Boolean, box: WidgetBox) {
    val context = LocalContext.current
    val tier = if (box.isUsable) tierOf(box.widthDp, box.heightDp) else WidgetTier.COMPACT
    val showCompactBar = !box.isUsable || box.heightDp >= COMPACT_BAR_MIN_HEIGHT_DP
    val showWideBar = !box.isUsable || box.heightDp >= WIDE_BAR_MIN_HEIGHT_DP
    WidgetCard(
        face = FACE_FINANCE,
        action = startFinance(context),
        verticalAlignment = tierAlignment(tier),
        padding = if (tier == WidgetTier.TINY) 12.dp else 16.dp,
    ) {
        when (tier) {
            WidgetTier.TINY -> FinanceTiny(data, hidden)
            WidgetTier.COMPACT -> FinanceCompact(data, hidden, showBar = showCompactBar)
            WidgetTier.WIDE -> FinanceWide(data, hidden, showBar = showWideBar)
        }
    }
}

/** 整块直达记账 Tab：flags 带 NEW_TASK（组件不是 Activity 上下文）+ CLEAR_TOP（App 已在前台就落 Tab 不叠栈）。 */
private fun startFinance(context: Context): Action = actionStartActivity(
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_START_TAB, MainActivity.FINANCE_TAB)
    },
)

@Composable
private fun FinanceTiny(data: FinanceWidgetData, hidden: Boolean) {
    IconBadge(face = FACE_FINANCE, symbol = "¥", size = 18.dp)
    Spacer(GlanceModifier.height(4.dp))
    Text(text = LABEL_EXPENSE, style = labelStyle(), maxLines = 1)
    Text(text = tinyMoney(data.expense, hidden), style = numberStyle(size = 17.sp), maxLines = 1)
}

@Composable
private fun FinanceCompact(data: FinanceWidgetData, hidden: Boolean, showBar: Boolean) {
    CardHead(
        face = FACE_FINANCE,
        symbol = "¥",
        label = LABEL_EXPENSE,
        badge = 22.dp,
    )
    Spacer(GlanceModifier.height(4.dp))
    Text(text = money(data.expense, hidden), style = numberStyle(size = 26.sp), maxLines = 1)
    Spacer(GlanceModifier.height(6.dp))
    val progress = data.budgetProgress
    if (data.hasBudget && progress != null && showBar) {
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.CenterVertically,
        ) {
            Bar(progress = progress, face = FACE_FINANCE, thickness = 6.dp, modifier = GlanceModifier.defaultWeight())
            Spacer(GlanceModifier.width(8.dp))
            Text(text = usedText(progress), style = labelStyle(), maxLines = 1)
        }
    } else {
        // 没有总预算 / 高度不够：不留一条空进度条（会读成「预算 0」），换成文字行
        Text(text = unseenBudgetText(data, hidden), style = labelStyle(), maxLines = 1)
    }
}

@Composable
private fun ColumnScope.FinanceWide(data: FinanceWidgetData, hidden: Boolean, showBar: Boolean) {
    val progress = data.budgetProgress
    CardHead(
        face = FACE_FINANCE,
        symbol = "¥",
        label = LABEL_EXPENSE + " · " + data.monthLabel,
        trailing = if (data.hasBudget && progress != null) usedText(progress) else null,
    )
    Spacer(GlanceModifier.height(6.dp))
    Text(text = money(data.expense, hidden), style = numberStyle(size = 40.sp), maxLines = 1)
    if (data.hasBudget && progress != null && showBar) {
        Spacer(GlanceModifier.height(10.dp))
        Bar(progress = progress, face = FACE_FINANCE, thickness = 8.dp)
    }
    // 底栏两块分区压到底：Wide 的高度富余（>170dp），不压底会在中间飘一行
    Spacer(GlanceModifier.defaultWeight())
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.Bottom,
    ) {
        Section(face = FACE_FINANCE, modifier = GlanceModifier.defaultWeight()) {
            Stat(label = LABEL_INCOME, value = money(data.income, hidden))
        }
        Spacer(GlanceModifier.width(8.dp))
        Section(face = FACE_FINANCE, modifier = GlanceModifier.defaultWeight()) {
            Stat(
                label = LABEL_BUDGET_LEFT,
                value = remainingText(progress, data.budgetRemaining, data.hasBudget, hidden),
                valueColor = if (progress != null && progress >= 1f) WIDGET_WARN else WIDGET_TEXT,
            )
        }
    }
}

/** Compact 的预算空态文案：隐藏金额时把 remaining 置空，[budgetText] 自然退回百分比分支。 */
private fun unseenBudgetText(data: FinanceWidgetData, hidden: Boolean): String = budgetText(
    progress = data.budgetProgress,
    remaining = if (hidden) null else data.budgetRemaining,
    hasBudget = data.hasBudget,
)
