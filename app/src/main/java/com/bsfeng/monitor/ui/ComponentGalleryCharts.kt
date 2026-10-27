package com.bsfeng.monitor.ui

// 从 ui/ComponentGalleryScreen.kt 拆出：展厅的图表区 —— 分类色板、折线/面积/柱状/扇形/环形/热力/sparkline 的陈列，以及演示用的 24 小时与 7 天数据。负责 s12 的图表陈列。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AreaChart
import com.bsfeng.monitor.ui.components.BarChart
import com.bsfeng.monitor.ui.components.ChartAxisLabels
import com.bsfeng.monitor.ui.components.HeatBar
import com.bsfeng.monitor.ui.components.Legend
import com.bsfeng.monitor.ui.components.LegendItem
import com.bsfeng.monitor.ui.components.LineChart
import com.bsfeng.monitor.ui.components.PieChart
import com.bsfeng.monitor.ui.components.RingProgress
import com.bsfeng.monitor.ui.components.Sparkline
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.chartRamp
import com.bsfeng.monitor.ui.theme.heatHeight
import com.bsfeng.monitor.ui.theme.heatRamp
import com.bsfeng.monitor.ui.theme.sparkHeight

@Composable
internal fun GalleryChartRamp() {
    val t = LocalDesignTokens.current
    // 原型 s12 色板卡（08-prototype.html:5115「图表灰阶色板 s1 – s7」）沿用其形态：色块行 + 图例
    // （色点 / 名称 / 右侧 token 名）；口径按现行 token 语义改正（docs/UI设计/设计规范.md §7.4）：
    //   ① chart-1..chart-16 是**分类色板**（彩色、跨主题固定），既不是中性灰阶，也不再是 7 槽；
    //   ② 趋势图用的序列色 chartSeq 跟随主题强调色，同一图表内所有数据点同色；
    //   ③ 热力条的强度阶 heatRamp 是序列色的 8–90% 透明阶梯，不单独占分类色板槽位。
    DemoSection("分类色板 chart-1 – chart-16", Modifier.testTag("s12-scale")) {
        AppCard {
            // 16 槽分两行 × 8：单行 16 块在 360dp 屏上会压到约 15dp 宽，失去比色意义。
            Column(verticalArrangement = Arrangement.spacedBy(t.space1)) {
                repeat(2) { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(t.space1)) {
                        repeat(8) { col ->
                            val slot = row * 8 + col
                            Box(
                                Modifier
                                    .weight(1f)
                                    .height(t.space6)
                                    .clip(RoundedCornerShape(t.radiusSm))
                                    .background(t.chartRamp[slot]),
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(t.space2))
            Text(
                "跨主题固定 · 同一分类在 8 套主题下同色",
                style = MaterialTheme.typography.labelSmall,
                color = t.meta,
            )
            Spacer(Modifier.height(t.space3))
            Legend(
                items = listOf(
                    LegendItem("分类色板 · 扇形分片", "chart-1..16", t.chartRamp.first()),
                    LegendItem("序列色 · 柱状 / 折线", "chartSeq", t.chartSeq),
                    LegendItem("强度阶 · 热力条", "heatRamp", t.heatRamp.last()),
                ),
            )
        }
    }
}

@Composable
internal fun GalleryCharts() {
    val t = LocalDesignTokens.current
    DemoSection("图表基元 Chart primitives", Modifier.testTag("s12-charts")) {
        Column(verticalArrangement = Arrangement.spacedBy(t.space3)) {
            AppCard {
                Text("面积曲线 area · 24h 事件量", style = MaterialTheme.typography.bodySmall, color = t.muted)
                Spacer(Modifier.height(t.space3))
                AreaChart(HOURS_24, Modifier.fillMaxWidth().height(t.chartH))
                ChartAxisLabels(listOf("00", "04", "08", "12", "16", "20"))
            }
            AppCard {
                Text("折线图 line · 近 7 日趋势", style = MaterialTheme.typography.bodySmall, color = t.muted)
                Spacer(Modifier.height(t.space3))
                LineChart(WEEK_7, Modifier.fillMaxWidth().height(t.chartH))
            }
            AppCard {
                Text("柱状图 bars · 近 7 日事件量", style = MaterialTheme.typography.bodySmall, color = t.muted)
                Spacer(Modifier.height(t.space3))
                BarChart(WEEK_7, Modifier.fillMaxWidth().height(t.chartH))
                ChartAxisLabels(listOf("一", "二", "三", "四", "五", "六", "日"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
                AppCard(Modifier.weight(1f)) {
                    // 原型 s12「图表基元」此卡的标签写 ring，实际渲染 data-chart="pie"（08-prototype.html:5153）：
                    // 占比一律实心扇形、数值只在外部图例里（无 centerLabel）。
                    Text("扇形图 pie · 占比", style = MaterialTheme.typography.bodySmall, color = t.muted)
                    Spacer(Modifier.height(t.space3))
                    PieChart(listOf(42f, 31f, 24f, 18f, 13f), Modifier.fillMaxWidth())
                }
                AppCard(Modifier.weight(1f)) {
                    Text("圆环进度 gauge", style = MaterialTheme.typography.bodySmall, color = t.muted)
                    Spacer(Modifier.height(t.space3))
                    RingProgress(0.68f, Modifier.fillMaxWidth(), centerLabel = "68%")
                }
            }
            AppCard {
                Text("迷你走势 spark + 热力条 heat", style = MaterialTheme.typography.bodySmall, color = t.muted)
                Spacer(Modifier.height(t.space3))
                Sparkline(WEEK_7, Modifier.fillMaxWidth().height(t.sparkHeight))
                Spacer(Modifier.height(t.space3))
                HeatBar(HOURS_24, Modifier.fillMaxWidth().height(t.heatHeight))
            }
        }
    }
}

private val HOURS_24 = listOf(
    2f, 1f, 0f, 0f, 1f, 3f, 8f, 14f, 11f, 9f, 14f, 17f,
    12f, 15f, 19f, 16f, 21f, 18f, 13f, 10f, 7f, 5f, 3f, 2f,
)

private val WEEK_7 = listOf(62f, 88f, 74f, 96f, 120f, 110f, 128f)
