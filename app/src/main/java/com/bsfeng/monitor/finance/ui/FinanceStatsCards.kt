package com.bsfeng.monitor.finance.ui

// 来源：原 finance/ui/FinanceStatsScreen.kt 的四个图表卡；职责：分类环+图例、近 7 天收支柱、24 小时热力、KPI 行，
// 以及这四张卡的按点/按拖读数交互（锚点 s07-kpicard / s07-ringcard / s07-barscard / s07-bars / s07-heatcard）。

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.finance.model.FinanceStats
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.ChartAxisLabels
import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.RingChart
import com.bsfeng.monitor.ui.components.animatedAmount
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.tweenOf
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import com.bsfeng.monitor.ui.theme.heatRamp
import com.bsfeng.monitor.ui.theme.monitorMonoKpi
import kotlinx.coroutines.delay

/** 读数提示条（锚点 s07-barscard：抬指后顶部浮出、1.6s 消失）预留高度，避免出现/消失时图表跳动。 */
private val TIP_BAR_H: Dp = 20.dp

/** 图例行按下右移量（锚点 s07-ringcard：整行右移 2px）。 */
private val LEGEND_SHIFT: Dp = 2.dp

/** 热力格圆角（原型 .heat i radius 2px）。 */
private val HEAT_CELL_RADIUS: Dp = 2.dp

/**
 * 三格 KPI 行（锚点 s07-kpicard）。整卡一个焦点停位（三格不拆停位）；
 * 点击不做任何视觉变化、也不拦截滚动；空态时整卡不出现（由屏装配决定）。
 */
@Composable
internal fun FinanceStatsKpiRow(stats: FinanceStats, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    val spoken = "支出 " + formatCount(stats.totalExpense.toInt()) + " 元，收入 " +
        formatCount(stats.totalIncome.toInt()) + " 元，结余 " + formatCount(stats.balance.toInt()) + " 元"
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4)
            .testTag("s07-kpicard")
            .focusable()
            .keyboardFocusRing(shape)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        horizontalArrangement = Arrangement.spacedBy(t.space3),
    ) {
        KpiTile(
            label = "支出",
            value = yen(animatedAmount(stats.totalExpense.toFloat()).toDouble(), 0),
            color = t.expenseText,
            modifier = Modifier.weight(1f),
        )
        KpiTile(
            label = "收入",
            value = yen(animatedAmount(stats.totalIncome.toFloat()).toDouble(), 0),
            color = t.incomeText,
            modifier = Modifier.weight(1f),
        )
        // 锚点 s07-kpicard：结余为负时转红
        KpiTile(
            label = "结余",
            value = yen(animatedAmount(stats.balance.toFloat()).toDouble(), 0),
            color = if (stats.balance >= 0) t.fg else t.expenseText,
            modifier = Modifier.weight(1f),
        )
    }
}

/**
 * 分类支出环形图 + 图例卡（锚点 s07-ringcard）。
 *
 * 整卡一个焦点停位并整体朗读；图例行不可聚焦，但按下时整行右移 2px（120ms），抬指复位。
 * 分片色与图例**同源**（categoryColorOf），不用图表基元默认的下标取色。
 */
@Composable
internal fun FinanceStatsRingCard(
    stats: FinanceStats,
    sortedEntries: List<Map.Entry<String, Double>>,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    val spoken = "分类支出，共 " + sortedEntries.size + " 个分类，总支出 " +
        formatCount(stats.totalExpense.toInt()) + " 元"
    Column(
        modifier
            .padding(horizontal = t.space4, vertical = t.space3)
            .testTag("s07-ringcard")
            .clip(shape)
            .focusable()
            .keyboardFocusRing(shape)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        AppCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("分类支出", style = MaterialTheme.typography.headlineSmall, color = t.fg)
                Text("占比", style = MaterialTheme.typography.labelSmall, color = t.meta)
            }
            Spacer(Modifier.height(t.space3))
            val sliceColors = sortedEntries.map { categoryColorOf(it.key) }
            // 占比用环形图：原型 s07 运行时由 ring() 渲染（环心才是 .s07-ringctr 的落点）
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                RingChart(
                    segments = sortedEntries.map { it.value.toFloat() },
                    colors = sliceColors,
                    size = STATS_RING_DIAMETER,
                    strokeWidth = STATS_RING_STROKE,
                )
                // 环心：¥总额（--text-lg 21px/700）+ 总支出（--text-sm 14px muted）
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(STATS_RING_CENTER_GAP),
                ) {
                    Text(
                        yen(stats.totalExpense, 0),
                        style = MaterialTheme.typography.headlineSmall,
                        color = t.fg,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("总支出", style = MaterialTheme.typography.bodySmall, color = t.muted)
                }
            }
            Spacer(Modifier.height(t.space3))
            // 图例：图例行不可聚焦；按下右移 2px、抬指复位（120ms）
            sortedEntries.forEachIndexed { index, entry ->
                FinanceLegendRow(
                    label = entry.key,
                    value = yen(entry.value, 0),
                    color = sliceColors[index],
                )
            }
        }
    }
}

/** 图例单行：色点 + 分类名（超长省略）+ 金额；按下 2px 右移，抬指 120ms 复位。 */
@Composable
private fun FinanceLegendRow(label: String, value: String, color: androidx.compose.ui.graphics.Color) {
    val t = LocalDesignTokens.current
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val shiftPx = with(LocalDensity.current) { LEGEND_SHIFT.toPx() }
    val shift by animateFloatAsState(
        targetValue = if (pressed) shiftPx else 0f,
        animationSpec = t.tweenOf(MotionToken.Press),
        label = "legendShift",
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .graphicsLayer { translationX = shift }
            .testTag("row.legend")
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { }
            .padding(vertical = t.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(color)
        Spacer(Modifier.width(t.space2))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = t.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(t.space2))
        Text(value, style = MaterialTheme.typography.bodySmall, color = t.meta)
    }
}

/**
 * 近 7 天收支双柱卡（锚点 s07-barscard + 柱区 s07-bars）。
 *
 * 单击柱组 / 横向拖动 → 顶部浮出该日读数「10月08日 · 支出 ¥132 · 收入 ¥900」，1.6s 自动消失；
 * 柱区可键盘聚焦，← → 逐日移动读数（首末不回绕），Enter 无额外动作。
 */
@Composable
internal fun FinanceStatsWeekCard(
    week: List<Triple<Float, Float, String>>,
    weekExpense: Double,
    weekIncome: Double,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    var tipDay by remember { mutableStateOf<Int?>(null) }
    // 口径：抬指/tap 后 1.6s 自动消失
    LaunchedEffect(tipDay) {
        val day = tipDay ?: return@LaunchedEffect
        delay(1_600)
        if (tipDay == day) tipDay = null
    }
    val density = LocalDensity.current
    val padLeftPx = with(density) { WEEK_AXIS_PAD_LEFT.toPx() }
    val padRightPx = with(density) { WEEK_AXIS_PAD_RIGHT.toPx() }
    val barsShape = RoundedCornerShape(t.radiusMd)

    Column(modifier.padding(horizontal = t.space4).testTag("s07-barscard")) {
        AppCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("近 7 天收支", style = MaterialTheme.typography.headlineSmall, color = t.fg)
                Text("单位 ¥", style = MaterialTheme.typography.labelSmall, color = t.meta)
            }
            Spacer(Modifier.height(t.space2))
            // 读数提示条：固定高度，避免浮出/消失时图表跳动
            Box(Modifier.fillMaxWidth().height(TIP_BAR_H), contentAlignment = Alignment.CenterStart) {
                val day = tipDay
                if (day != null && day in week.indices) {
                    val item = week[day]
                    Text(
                        monthDayLabelOf(item.third) + " · 支出 " + yen(item.first.toDouble(), 0) +
                            " · 收入 " + yen(item.second.toDouble(), 0),
                        style = MaterialTheme.typography.labelSmall,
                        color = t.fg,
                    )
                }
            }
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(t.chartH)
                    .testTag("s07-bars")
                    .focusable()
                    .keyboardFocusRing(barsShape)
                    .onPreviewKeyEvent { event ->
                        if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (event.key) {
                            Key.DirectionLeft, Key.DirectionRight -> {
                                val step = if (event.key == Key.DirectionLeft) -1 else 1
                                if (week.isNotEmpty()) {
                                    val next = ((tipDay ?: 0) + step).coerceIn(0, week.lastIndex)
                                    if (next != tipDay) {
                                        haptic(HapticTone.LIGHT)
                                        tipDay = next
                                    }
                                }
                                true
                            }
                            Key.Enter, Key.NumPadEnter -> true
                            else -> false
                        }
                    }
                    .pointerInput(week, padLeftPx, padRightPx) {
                        fun dayIndexAt(x: Float): Int {
                            if (week.isEmpty()) return 0
                            val plot = (size.width - padLeftPx - padRightPx).coerceAtLeast(1f)
                            val group = plot / week.size
                            return ((x - padLeftPx) / group).toInt().coerceIn(0, week.lastIndex)
                        }
                        detectTapGestures { offset ->
                            tipDay = dayIndexAt(offset.x)
                            haptic(HapticTone.LIGHT)
                        }
                    }
                    .pointerInput(week, padLeftPx, padRightPx) {
                        fun dayIndexAt(x: Float): Int {
                            if (week.isEmpty()) return 0
                            val plot = (size.width - padLeftPx - padRightPx).coerceAtLeast(1f)
                            val group = plot / week.size
                            return ((x - padLeftPx) / group).toInt().coerceIn(0, week.lastIndex)
                        }
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> tipDay = dayIndexAt(offset.x) },
                            onDragCancel = { tipDay = null },
                            onDragEnd = { },
                        ) { change, _ -> tipDay = dayIndexAt(change.position.x) }
                    },
            ) {
                GroupedBarChart(
                    values = week,
                    modifier = Modifier.fillMaxSize(),
                )
                // 选中柱组加深一档（120ms）
                val day = tipDay
                if (day != null && day in week.indices) {
                    val highlight = remember(day, week.size) { day to week.size }
                    Canvas(Modifier.fillMaxSize()) {
                        val padLeft = padLeftPx
                        val padRight = padRightPx
                        val group = ((size.width - padLeft - padRight) / highlight.second).coerceAtLeast(1f)
                        drawRoundRect(
                            color = t.fgFade(7),
                            topLeft = Offset(padLeft + group * highlight.first, 0f),
                            size = Size(group, size.height),
                            cornerRadius = CornerRadius(6f, 6f),
                        )
                    }
                }
            }
            // X 轴日期与柱区左右边界对齐（跳掉左侧 Y 轴刻度列与右侧留白）
            ChartAxisLabels(
                week.map { dayLabelOf(it.third) },
                modifier = Modifier.padding(
                    start = WEEK_AXIS_PAD_LEFT,
                    end = WEEK_AXIS_PAD_RIGHT,
                ),
            )
            Spacer(Modifier.height(t.space3))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(t.space4)) {
                LegendDot(t.danger)
                Spacer(Modifier.width(t.space1))
                Text("支出 7 天 " + yen(weekExpense, 0), style = MaterialTheme.typography.labelSmall, color = t.meta)
                Spacer(Modifier.width(t.space2))
                LegendDot(t.success)
                Spacer(Modifier.width(t.space1))
                Text("收入 7 天 " + yen(weekIncome, 0), style = MaterialTheme.typography.labelSmall, color = t.meta)
            }
        }
    }
}

/**
 * 24 小时消费强度热力卡（锚点 s07-heatcard）。
 *
 * 点/横向拖某一小时格 → 该格描边加深一档，卡片底部说明行临时换成「12:00 · 9 笔」，1.6s 后回峰值文案。
 */
@Composable
internal fun FinanceStatsHeatCard(
    hourly: List<Float>,
    peak: List<Int>,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    var hour by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(hour) {
        val h = hour ?: return@LaunchedEffect
        delay(1_600)
        if (hour == h) hour = null
    }
    val ramp = t.heatRamp
    val max = hourly.maxOrNull()?.takeIf { it > 0f } ?: 1f

    Column(modifier.padding(horizontal = t.space4, vertical = t.space3).testTag("s07-heatcard")) {
        AppCard {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("24 小时消费强度", style = MaterialTheme.typography.headlineSmall, color = t.fg)
                Text("笔 / 小时", style = MaterialTheme.typography.labelSmall, color = t.meta)
            }
            Spacer(Modifier.height(t.space3))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(t.heatH)
                    .pointerInput(hourly) {
                        fun hourAt(x: Float): Int =
                            ((x / size.width) * HOURS).toInt().coerceIn(0, HOURS - 1)
                        detectTapGestures { offset ->
                            hour = hourAt(offset.x)
                            haptic(HapticTone.LIGHT)
                        }
                    }
                    .pointerInput(hourly) {
                        fun hourAt(x: Float): Int =
                            ((x / size.width) * HOURS).toInt().coerceIn(0, HOURS - 1)
                        detectHorizontalDragGestures(
                            onDragStart = { offset -> hour = hourAt(offset.x) },
                            onDragCancel = { hour = null },
                            onDragEnd = { },
                        ) { change, _ -> hour = hourAt(change.position.x) }
                    },
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                hourly.forEachIndexed { index, value ->
                    val level = ((value / max) * (ramp.size - 1)).toInt().coerceIn(0, ramp.size - 1)
                    val shape = RoundedCornerShape(HEAT_CELL_RADIUS)
                    val selected = hour == index
                    val bg by animateColorAsState(
                        targetValue = ramp[level],
                        animationSpec = t.tweenOf(MotionToken.State),
                        label = "heatCell",
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .clip(shape)
                            .background(bg)
                            .then(
                                if (selected) Modifier.border(2.dp, t.accent, shape) else Modifier,
                            ),
                    )
                }
            }
            ChartAxisLabels(listOf("00", "04", "08", "12", "16", "20"))
            Spacer(Modifier.height(t.space3))
            Text(
                heatNoteText(hourly, peak, hour),
                style = MaterialTheme.typography.labelSmall,
                color = if (hour == null) t.meta else t.fg,
            )
        }
    }
}

/**
 * 热力说明行文案（锚点 s07-heatcard）。
 *
 * 选中某小时 → 「12:00 · 9 笔」；否则峰值文案「峰值出现在 12:00（9 笔）与 19:00（8 笔）；柱高按当月最大值归一。」；
 * 当月无交易 → 「本月还没有交易记录，热力图为空。」。
 */
internal fun heatNoteText(hourly: List<Float>, peaks: List<Int>, selectedHour: Int?): String {
    if (selectedHour != null) {
        val count = hourly.getOrNull(selectedHour)?.toInt() ?: 0
        return hourText(selectedHour) + " · " + count + " 笔"
    }
    if (peaks.isEmpty() || hourly.all { it <= 0f }) return "本月还没有交易记录，热力图为空。"
    val parts = peaks.map { hourText(it) + "（" + (hourly.getOrNull(it)?.toInt() ?: 0) + " 笔）" }
    return "峰值出现在 " + parts.joinToString(" 与 ") + "；柱高按当月最大值归一。"
}

private fun hourText(hour: Int): String = (if (hour < 10) "0" else "") + hour + ":00"

/** 2026-10-08 → 10月08日（锚点 s07-barscard 的读数文案）。 */
internal fun monthDayLabelOf(isoDate: String): String {
    val parts = isoDate.split("-")
    val month = parts.getOrNull(1)?.toIntOrNull()
    val day = parts.getOrNull(2)?.toIntOrNull()
    if (month == null || day == null) return isoDate
    val dayText = if (day < 10) "0" + day else day.toString()
    return "" + month + "月" + dayText + "日"
}
