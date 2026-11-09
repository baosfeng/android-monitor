package com.bsfeng.monitor.ui

// s01 概览「记账图表」卡（原型 s01-今日概览 #s01-finance 区块）：本月三值 + 分类支出扇形 + 近 7 天支出走势 + 图例 + 进记账页入口。
// 数据由 OverviewViewModel 提供（[FinanceCardState]），可见性由 FinanceUiPrefs 的「首页记账卡」开关决定（见 OverviewPanels.todayPanelS01）。
//
// 图表口径（设计规范 §7）：占比用 PieChart（实心扇形、分类色 chart1..16），趋势用 Sparkline（序列色 chartSeq），
// 同图同色 —— 扇形与图例同源取 categoryDot(index)，不与序列色混用。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.Legend
import com.bsfeng.monitor.ui.components.LegendItem
import com.bsfeng.monitor.ui.components.PieChart
import com.bsfeng.monitor.ui.components.ChartFrame
import com.bsfeng.monitor.ui.components.ChartFrameKind
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.live.CardHead
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.categoryDot
import com.bsfeng.monitor.ui.theme.sparkHeight
import com.bsfeng.monitor.ui.theme.monitorMonoMeta

/** 一个一级分类的支出金额（扇形与图例共用同一份切片，颜色按下标同源取）。 */
data class FinanceCategorySlice(val label: String, val amount: Double)

/**
 * 概览记账卡的一次快照。
 *
 * 三值口径与记账页月度 KPI 同源（SQL SUM，退款负额已在支出里抵扣）；
 * [dailyExpense7] 是「近 7 天」（含今天）逐日支出，与月档分桶不同（可能跨月）。
 */
data class FinanceCardState(
    /** 卡头右上「2026 年 11 月」；空串表示还没取到数据。 */
    val monthLabel: String = "",
    val monthExpense: Double = 0.0,
    val monthIncome: Double = 0.0,
    val monthBalance: Double = 0.0,
    val categorySlices: List<FinanceCategorySlice> = emptyList(),
    val dailyExpense7: List<Float> = List(7) { 0f },
    /** 近 7 天逐日支出的 X 轴文案（MM-dd，首 = 最早那天）；与概览「近 7 天」面板同源。 */
    val dailyLabels7: List<String> = emptyList(),
) {
    /** 有任何金额或分类即算有数据；全空时卡内写一句空态，不画 0 值假图表。 */
    val hasData: Boolean
        get() = monthExpense > 0.0 || monthIncome > 0.0 || categorySlices.isNotEmpty()
}

/**
 * 趋势图绘图区高度：76dp。下限由「Y 轴刻度读得全」倒推——面积图顶部留白 24dp 之后
 * 剩 52dp 要放 3 个刻度值（每个约 15dp）；再压到 56dp 时最高一档刻度会被裁掉。
 */
private val TREND_CHART_H: Dp = 76.dp

/** 趋势图 Y 轴刻度行数：2 行 = 3 个刻度值（0 / 半量 / 上限），在 76dp 内不重叠。 */
private const val TREND_Y_TICKS = 2

/** 图例最多直接列几行（原型 s01-finance-legend 的 6 行在 390 宽卡片里会把卡撑高，取前 3 + 「其他 N 类」）。 */
private const val LEGEND_ROWS = 3

/**
 * 记账图表卡：卡头（本月） + 左扇形右三值/走势 + 图例 + 进记账页入口。
 *
 * 整卡不整体可点（内部有独立入口按钮）：卡片式可点容器会让「点空白处」也跳页，
 * 与 s01 其余卡片「只有明确入口可点」的口径不一致。
 */
@Composable
internal fun OverviewFinanceCard(
    state: FinanceCardState,
    onOpenFinance: () -> Unit,
    onHaptic: (HapticTone) -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    AppCard(modifier.testTag("s01-finance")) {
        CardHead("记账图表", state.monthLabel)
        Spacer(Modifier.height(t.space3))
        if (!state.hasData) {
            MetaText("本月还没有记账，去记账页记一笔就能看到分类占比")
        } else {
            val colors = state.categorySlices.indices.map { t.categoryDot(it) }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(t.ringSize)
                        .testTag("s01-finance-pie"),
                ) {
                    PieChart(
                        segments = state.categorySlices.map { it.amount.toFloat() },
                        modifier = Modifier.fillMaxWidth(),
                        colors = colors,
                        size = t.ringSize,
                    )
                }
                Spacer(Modifier.width(t.space4))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(t.space1)) {
                    FinanceAmountRow("收入", state.monthIncome, t.successText)
                    FinanceAmountRow("支出", state.monthExpense, t.dangerText)
                    FinanceAmountRow("结余", state.monthBalance, t.fg)
                }
            }
            // 趋势图带坐标轴（用户反馈「图表光秃秃、没有横纵坐标和文字」的原诉求）：
            // 2026-10 那轮只画了无轴 sparkline，读的人看不出纵轴量级、也认不出这 7 根是哪几天。
            // 走 ui/components/ChartFrame 的 Area 分支：Y 轴 5 档刻度 + X 轴日期标签 + 峰值标注，
            // 尺寸压到两行高（图表卡里不抢环图的位置）。
            if (state.dailyExpense7.any { it > 0f }) {
                ChartFrame(
                    values = state.dailyExpense7,
                    modifier = Modifier.fillMaxWidth().testTag("s01-finance-spark"),
                    xLabels = state.dailyLabels7,
                    kind = ChartFrameKind.Area,
                    title = "近 7 天支出",
                    unit = "单位：元",
                    showValueLabels = true,
                    yTicks = TREND_Y_TICKS,
                    height = TREND_CHART_H,
                )
            }
            Spacer(Modifier.height(t.space3))
            Legend(
                items = legendItems(state.categorySlices, colors, t.muted),
                modifier = Modifier.testTag("s01-finance-legend"),
            )
        }
        Spacer(Modifier.height(t.space3))
        GhostTextButton(
            text = "去记账 · 查看全部交易",
            onClick = { onHaptic(HapticTone.LIGHT); onOpenFinance() },
            modifier = Modifier.testTag("s01-finance-open"),
        )
    }
}

/** 扇形右侧的一行「标签 —— 金额」：金额用等宽数字（同列不跳动）。 */
@Composable
private fun FinanceAmountRow(label: String, amount: Double, color: Color) {
    val t = LocalDesignTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = t.meta,
            modifier = Modifier.weight(1f),
        )
        Text("¥" + money(amount), style = monitorMonoMeta, color = color)
    }
}

/**
 * 图例：前 [LEGEND_ROWS] 类逐行列出，其余并成一行「其他 K 类」。
 *
 * 扇形分片可以是 6 片，图例只列 3 行 —— 两者不逐行对应，所以末行写清「其他 K 类 ¥X」而不是静默截断。
 */
private fun legendItems(
    slices: List<FinanceCategorySlice>,
    colors: List<Color>,
    restColor: Color,
): List<LegendItem> {
    val head = slices.take(LEGEND_ROWS).mapIndexed { index, slice ->
        LegendItem(slice.label, "¥" + money(slice.amount), colors[index])
    }
    val rest = slices.drop(LEGEND_ROWS)
    if (rest.isEmpty()) return head
    return head + LegendItem(
        "其他 " + rest.size + " 类",
        "¥" + money(rest.sumOf { it.amount }),
        restColor,
    )
}
