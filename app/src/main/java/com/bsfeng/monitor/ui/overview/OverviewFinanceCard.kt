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
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.Legend
import com.bsfeng.monitor.ui.components.LegendItem
import com.bsfeng.monitor.ui.components.PieChart
import com.bsfeng.monitor.ui.components.Sparkline
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

/** 图例逐行列出几类（原型 s01:361 的 6 类）；超出部分并成「其他 N 类」，不静默截断。 */
private const val LEGEND_ROWS = 6

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
            // 原型 s01:352 的 grid-2：左 128 环图，右列三值 + 底部一条迷你走势（:358）。
            // 右列的走势是 72×24 的无轴 sparkline（不是带坐标轴的趋势图）——s04 年度图才用 ChartFrame。
            Row(
                // 原型格高 118（右列 3×20.6 + spark 24 撑出），不是环图的 128 ——
                // 环图在原型里就是溢出 10 压到图例上边距，这里用 requiredSize 保真
                Modifier.fillMaxWidth().height(118.dp),
                horizontalArrangement = Arrangement.spacedBy(t.space2),
                // 原型 .grid-2 默认 stretch：右列三值从格子上沿开始排，不居中
                verticalAlignment = Alignment.Top,
            ) {
                // 左列与右列等宽（原型 .grid-2 的 1fr 1fr + gap 8）：右列起点 x=200.5 由这个比例决定
                Box(Modifier.weight(1f)) {
                    Box(
                        Modifier
                            .requiredSize(t.ringSize)
                            .testTag("s01-finance-pie"),
                    ) {
                        PieChart(
                            segments = state.categorySlices.map { it.amount.toFloat() },
                            modifier = Modifier.fillMaxWidth(),
                            colors = colors,
                            size = t.ringSize,
                        )
                    }
                }
                // 原型 .s01-kcol 的三行紧贴（各 20.6），行间无 gap
                Column(Modifier.weight(1f)) {
                    FinanceAmountRow("收入", state.monthIncome, t.successText)
                    FinanceAmountRow("支出", state.monthExpense, t.dangerText)
                    FinanceAmountRow("结余", state.monthBalance, t.fg)
                    if (state.dailyExpense7.any { it > 0f }) {
                        // 原型 spark 紧跟第三行（.kpi-spark 的 mt 被列高 118 吃掉），无额外间距
                        Sparkline(
                            values = state.dailyExpense7,
                            modifier = Modifier
                                .width(t.space12 + t.space6)
                                .height(t.space6)
                                .testTag("s01-finance-spark"),
                        )
                    }
                }
            }
            // 原型格高 118、环图 128 会溢出 10，图例的 .mt-3(12) 实际只剩 2 的视觉间距
            Spacer(Modifier.height(2.dp))
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
    // 原型 .s01-krow 高 20.6（三行 + spark 24 = 118 的格子高）
    Row(
        Modifier.fillMaxWidth().height(20.6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
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
