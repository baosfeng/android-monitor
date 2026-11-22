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
import androidx.compose.foundation.layout.aspectRatio
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
import com.bsfeng.monitor.ui.theme.monitorMonoBadge
import com.bsfeng.monitor.ui.theme.monitorMonoKpi
import kotlinx.coroutines.delay

/** 图例行按下右移量（锚点 s07-ringcard：整行右移 2px）。 */
private val LEGEND_SHIFT: Dp = 2.dp

/** 热力格圆角（原型 .heat i radius 2px）。 */
private val HEAT_CELL_RADIUS: Dp = 2.dp

/** 热力轴标签上间距（原型 .s07-heatlabels margin-top:6px）。 */
private val HEAT_LABEL_GAP: Dp = 6.dp

/** 近 7 天柱区宽高比（原型 svg viewBox 340×176）。 */
private const val WEEK_CHART_ASPECT: Float = 340f / 176f

/** 24 小时热力宽高比（原型 svg viewBox 340×44）。 */
private const val HEAT_ASPECT: Float = 340f / 44f

/** 图例行间距（原型 .legend gap = --space-2 = 8px）。 */
private val LEGEND_ROW_GAP: Dp = 8.dp

/**
 * 图例行盒：li 高 = 14px 正文行盒 20.6（原型没有额外 padding）。
 *
 * 内层 Text 的 CJK 自然行盒是 25dp，6 行会多出 26dp；钉成 20.6 后整卡的
 * 「标题 + 环 150 + 图例」才与原型 369.5 对得上。
 */
private val LEGEND_ROW_H: Dp = 20.6.dp

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
    // 原型锚点 s07-kpicard 打在 .pad.mt-2 这个**容器**上（宽 393、含左右 16 内边距）。
    // Compose 的语义盒会吃掉同一节点上的 padding，所以锚点必须挂在独立的外层 Box 上，
    // 16dp 内边距放到内层 Row —— 否则锚点只能量到内容行（361，差 16/32）。
    Box(modifier.fillMaxWidth().testTag("s07-kpicard")) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4)
            .focusable()
            .keyboardFocusRing(shape)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
        // 原型 .grid-3 gap = 8px（--space-2），不是卡间距的 12
        horizontalArrangement = Arrangement.spacedBy(t.space2),
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
    // 同 s07-kpicard：锚点 = 原型 .pad 容器（393），16dp 内边距在内层
    Box(modifier.fillMaxWidth().testTag("s07-ringcard")) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = t.space4)
            .clip(shape)
            .focusable()
            .keyboardFocusRing(shape)
            .semantics(mergeDescendants = true) { contentDescription = spoken },
    ) {
        AppCard(padding = t.space4 + 1.dp) {
            StatsCardTitleRow("分类支出", "占比")
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
            // 图例：图例行不可聚焦；按下右移 2px、抬指复位（120ms）。
            // 行距 8（原型 .legend gap），行内不加上下内边距：原型 li 高 20.6 = 14px 正文行盒本身。
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(LEGEND_ROW_GAP)) {
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
            .height(LEGEND_ROW_H)
            .graphicsLayer { translationX = shift }
            .testTag("row.legend")
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { },
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

    Column(modifier.fillMaxWidth().testTag("s07-barscard").padding(horizontal = t.space4)) {
        AppCard(padding = t.space4 + 1.dp) {
            StatsCardTitleRow("近 7 天收支", "单位 ¥")
            Spacer(Modifier.height(t.space2))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // 原型 svg viewBox 340×176，显示宽 327 时高 169.3（不是 token chart-h 148）
                    .aspectRatio(WEEK_CHART_ASPECT)
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
                    // 日期画进 canvas（原型同一个 svg），避免外面再起一行 19dp 的轴
                    xLabels = week.map { dayLabelOf(it.third) },
                )
                // 读数浮层：原型没有「预留高度」的读数条，读数直接盖在绘图区顶部，
                // 再预留 20dp 会把整卡推高（已实测 +28）。浮层不吃点击：pointerInput 在父层。
                val tip = tipDay
                if (tip != null && tip in week.indices) {
                    val item = week[tip]
                    Text(
                        monthDayLabelOf(item.third) + " · 支出 " + yen(item.first.toDouble(), 0) +
                            " · 收入 " + yen(item.second.toDouble(), 0),
                        style = MaterialTheme.typography.labelSmall,
                        color = t.fg,
                        modifier = Modifier.align(Alignment.TopStart),
                    )
                }
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
            // 图例紧贴柱区：原型 .legend{margin:0} 覆盖了 mt-3（实测 legend 顶 = 柱区底）
            // 图例两行、金额右推（原型 s07:164-167 + 20-base.html:368-369 的 .legend-num）：
            // 压成一行时两个金额跟在系列名后面，读者要停下来分辨「哪个数属于哪条」。
            WeekLegendRow(color = t.danger, label = "支出", total = weekExpense)
            WeekLegendRow(color = t.success, label = "收入", total = weekIncome)
        }
    }
}

/** 近 7 天图例一行：色点 + 系列名（占满剩余宽）+ 右推的「7 天 ¥金额」（原型 .legend-num）。 */
@Composable
private fun WeekLegendRow(color: androidx.compose.ui.graphics.Color, label: String, total: Double) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = t.space1),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        LegendDot(color)
        Spacer(Modifier.width(t.space2))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = t.fg,
            modifier = Modifier.weight(1f),
        )
        Text(
            "7 天 " + yen(total, 0),
            style = MaterialTheme.typography.bodySmall,
            color = t.meta,
        )
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

    Column(modifier.fillMaxWidth().testTag("s07-heatcard").padding(horizontal = t.space4)) {
        AppCard(padding = t.space4 + 1.dp) {
            StatsCardTitleRow("24 小时消费强度", "笔 / 小时")
            Spacer(Modifier.height(t.space3))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    // 原型 svg viewBox 340×44，显示宽 327 时高 42.3（不是 token heat-h 44）
                    .aspectRatio(HEAT_ASPECT)
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
            // 原型 .s07-heatlabels：11px 等宽 + 上间距 6（不是通用轴标签的 12sp / 4dp）
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = HEAT_LABEL_GAP),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                listOf("00", "04", "08", "12", "16", "20").forEach {
                    Text(it, style = monitorMonoBadge, color = t.muted)
                }
            }
            Spacer(Modifier.height(t.space3))
            Text(
                heatNoteText(hourly, peak, hour),
                // 原型说明行是 .meta（--text-sm 14px），不是 12sp 的 .fine
                style = MaterialTheme.typography.bodySmall,
                color = if (hour == null) t.meta else t.fg,
            )
        }
    }
}

/** 夜间时段（00..05 时）：整段无消费时补一句「夜间无消费」（原型 s07:182）。 */
private const val NIGHT_QUIET_HOURS = 6

/**
 * 热力说明行文案（锚点 s07-heatcard）。
 *
 * 选中某小时 → 「12:00 · 9 笔」；否则「峰值出现在 12:00（9 笔）与 19:00（8 笔），夜间无消费。」；
 * 当月无交易 → 「本月还没有交易记录，热力图为空。」。
 *
 * 热力是**色阶**不是柱，所以不写「柱高按本档最大值归一」——那是柱状图的说法，
 * 原型这一行只讲峰值与夜间（s07:182）。
 */
internal fun heatNoteText(hourly: List<Float>, peaks: List<Int>, selectedHour: Int?): String {
    if (selectedHour != null) {
        val count = hourly.getOrNull(selectedHour)?.toInt() ?: 0
        return hourText(selectedHour) + " · " + count + " 笔"
    }
    if (peaks.isEmpty() || hourly.all { it <= 0f }) return "本月还没有交易记录，热力图为空。"
    val parts = peaks.map { hourText(it) + "（" + (hourly.getOrNull(it)?.toInt() ?: 0) + " 笔）" }
    val nightQuiet = (0 until NIGHT_QUIET_HOURS).all { (hourly.getOrNull(it) ?: 0f) <= 0f }
    // 原型 s07:182 是「（9 笔）与 19:00」：括号后直接接「与」，不留空格
    // 原型 s07:182 逐字：「12:00（9 笔）与 19:00（8 笔）」——「与」后有一个空格
    return "峰值出现在 " + parts.joinToString("与 ") + if (nightQuiet) "，夜间无消费。" else "。"
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