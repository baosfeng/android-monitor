package com.bsfeng.monitor.ui

// 从 ui/MainScreen.kt 拆出：实时事件流的「今日活跃强度」热力区块与「今日事件构成」横条组。
// 只接收值与回调，状态仍由 MainScreen 持有。锚点：s02-heatwrap / s02-compose。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.BarRow
import com.bsfeng.monitor.ui.components.BarRows
import com.bsfeng.monitor.ui.components.HeatBar
import com.bsfeng.monitor.ui.live.CardHead
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.eventComposeRows
import com.bsfeng.monitor.ui.live.peakOf
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.heatHeight
import java.util.Locale

private val EMPTY_TRACK_HEIGHT = 2.dp

/** 原型 .meta 行盒（14 × 1.47）：CJK 自然行盒比它高，说明行要按它取值才不会撑高整块。 */
private val META_LINE = 20.6.dp

/**
 * 热力条实际渲染高度 46.7（不是 token --heat-h 的 44）。
 *
 * 原型 heat 图默认 w=340，而 svg 被 .chart 拉到容器宽 361，等比缩放后 44 × 361/340 = 46.7。
 * 真值按渲染后的盒子取（proto-screens.json 的 s02-heatwrap 高 75.28 = 20.58 + 8 + 46.7）。
 */
private val HEAT_BAR_H = 46.7.dp
private val EMPTY_TRACK_RADIUS = 2.dp
private const val EMPTY_HEAT_TEXT = "暂无数据"

/**
 * 今日活跃强度：标题行（含峰值文案）+ 24 格热力条。热力不可点选、无 tooltip、不可拖选。
 *
 * 无数据时改画 2dp 浅色空轨：HeatBar 在 values 全 0 时 max 兜底为 1f → level=0 → 会画出
 * chart-1 最深的满高柱（设备实证的 P0 假象）。
 */
@Composable
internal fun LiveIntensityPanel(hourlyIntensity: List<Float>, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val hasIntensity = hourlyIntensity.any { it > 0f }
    val peak = peakOf(hourlyIntensity)
    val peakText = if (hasIntensity) {
        "峰值 " + String.format(Locale.US, "%02d:00", peak.first) + " · " + peak.second.toInt() + " 条"
    } else {
        EMPTY_HEAT_TEXT
    }
    // 原型 s02-heatwrap 只有 padding-inline（0 16）：纵向留白由 .mt-2 / 下一块的 mt-3 提供，
    // 这里加纵向内边距会让热力块高出 16dp、把整个时间线往下推。
    Column(modifier.padding(horizontal = t.space4)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            // 原型 .meta 行盒 = 14 × leading-body 1.47 ≈ 20.6；labelMedium 的行高是 18，
            // 不钉行盒会让整块热力区矮 2.6dp，把后面整条时间线一路往上带。
            Box(Modifier.height(META_LINE)) {
                MetaText("今日活跃强度", Modifier.wrapContentHeight(Alignment.Top, unbounded = true))
            }
            Box(Modifier.height(META_LINE)) {
                MetaText(peakText, Modifier.wrapContentHeight(Alignment.Top, unbounded = true))
            }
        }
        Spacer(Modifier.height(t.space2))
        if (hasIntensity) {
            HeatBar(
                values = hourlyIntensity,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(HEAT_BAR_H),
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(EMPTY_TRACK_HEIGHT)
                    .background(t.borderSoft, RoundedCornerShape(EMPTY_TRACK_RADIUS)),
            )
        }
    }
}

/**
 * 今日事件构成（锚点 s02-compose）：卡头 + 6 行横条。
 *
 * 数据来自 MainViewModel 的 typeCounts（每轮刷新按记录类型分组计数），不在前端写死样例数；
 * 行序与条色按原型固定，条长 = 条数 / 今日总数（分母即页头与 chip 的同一个 todayEventCount）。
 */
@Composable
internal fun LiveComposeCard(
    typeCounts: Map<RecordType, Int>,
    total: Int,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val counts = remember(typeCounts, total) { eventComposeRows(typeCounts, total) }
    // 原型 s02-实时事件流.html:204-212 的条色：应用 chart-2 / 屏幕 chart-1 / 网络 chart-4 /
    // 充电 chart-3 / 音频 chart-6 / 其他 chart-8。分类色板跨主题固定，不跟强调色走。
    val colors = listOf(t.chart2, t.chart1, t.chart4, t.chart3, t.chart6, t.chart8)
    val rows = counts.mapIndexed { index, row ->
        BarRow(label = row.label, value = row.count, color = colors[index % colors.size])
    }
    // 锚点挂在 .pad 满宽外层（同 s03-stat 的处置）：卡片本身在内层吃 16 内边距。
    Box(modifier.fillMaxWidth()) {
        Box(Modifier.padding(horizontal = t.space4)) {
            AppCard {
                CardHead(
                    "今日事件构成",
                    total.toString() + " 条 · " + rows.count { it.value > 0 } + " 类",
                )
                Spacer(Modifier.height(t.space2))
                BarRows(rows = rows, unit = "条", total = total)
            }
        }
    }
}
