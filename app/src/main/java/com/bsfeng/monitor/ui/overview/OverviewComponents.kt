package com.bsfeng.monitor.ui

// s01 今日概览 —— 整页实现（41 个 testTag 逐字照抄锚点账本）。
// 本文件自包含：页头 / 时间范围分段 / 五态 / 今日 · 近 7 天 · 近 30 天 三段面板，全部锚点落在本文件内。
// 底座复用：PressFeedback(0.96) · RowPress(0.99) · FocusRing(仅键盘) · Haptics(tone) · Toast(1.6s)
//          FiveState/StateSkeleton · Stagger(24ms) · AnimatedText · Bar/Line/Pie Charts · Legend。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.bsfeng.monitor.domain.trip.DayTimeline
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.AppToast
import com.bsfeng.monitor.ui.components.AreaChart
import com.bsfeng.monitor.ui.components.BarChart
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.ChartAxisLabels
import com.bsfeng.monitor.ui.components.EdgeState
import com.bsfeng.monitor.ui.components.EmptyState
import com.bsfeng.monitor.ui.components.ErrorState
import com.bsfeng.monitor.ui.components.FiveState
import com.bsfeng.monitor.ui.components.Legend
import com.bsfeng.monitor.ui.components.LegendItem
import com.bsfeng.monitor.ui.components.PieChart
import com.bsfeng.monitor.ui.components.StateSkeleton
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.rememberToastHostState
import com.bsfeng.monitor.ui.components.rowPressFeedback
import com.bsfeng.monitor.ui.components.staggerEnterAt
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.pressFeedback
import com.bsfeng.monitor.ui.foundation.rememberHaptic
import com.bsfeng.monitor.ui.components.Sparkline
import com.bsfeng.monitor.ui.live.CardHead
import com.bsfeng.monitor.ui.live.HeatStrip
import com.bsfeng.monitor.ui.live.LiveEventRow
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.PLACEHOLDER
import com.bsfeng.monitor.ui.live.formatClock
import com.bsfeng.monitor.ui.live.recordTypeLabel
import com.bsfeng.monitor.ui.live.TypeIconDot
import com.bsfeng.monitor.ui.live.formatFullDate
import com.bsfeng.monitor.ui.live.peakOf
import com.bsfeng.monitor.ui.live.rollingHourAxisLabels
import com.bsfeng.monitor.ui.live.rollingHourStartAt
import com.bsfeng.monitor.ui.live.weekdayLabel
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.categoryDot
import com.bsfeng.monitor.ui.theme.fgFade
import com.bsfeng.monitor.ui.theme.onAccent
import com.bsfeng.monitor.ui.theme.sparkHeight
import kotlinx.coroutines.launch
import com.bsfeng.monitor.ui.trip.TripBand
import com.bsfeng.monitor.ui.trip.TripBandAxis
import com.bsfeng.monitor.ui.trip.TripBandLegend
import com.bsfeng.monitor.ui.trip.basisBadge
import com.bsfeng.monitor.ui.trip.tripCoverageNote
import com.bsfeng.monitor.ui.trip.tripHeadline
import java.util.Locale


// ───────────────────────────── 页头 / 分段 ─────────────────────────────

@Composable
internal fun OverviewHead(dateText: String, onBell: () -> Unit, onOpenTotp: (() -> Unit)? = null) {
    val t = LocalDesignTokens.current
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("s01-head")
            .padding(start = t.space4, end = t.space4, top = t.space4, bottom = t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            MetaText(dateText)
            Spacer(Modifier.height(t.space2))
            Text("今日概览", style = MaterialTheme.typography.headlineMedium, color = t.fg)
        }
        Spacer(Modifier.width(t.space3))
        OverviewIconButton(
            icon = Icons.Outlined.Notifications,
            description = "今日提醒",
            modifier = Modifier.testTag("s01-bell"),
            onClick = onBell,
        )
        if (onOpenTotp != null) {
            Spacer(Modifier.width(t.space2))
            // s01-goto-totp：原型 .screen-head 右上角第二个 icon-btn（盾形 = 验证器）
            OverviewIconButton(
                icon = Icons.Outlined.Security,
                description = "验证器",
                modifier = Modifier.testTag("s01-goto-totp"),
                onClick = onOpenTotp,
            )
        }
    }
}

/** 44dp 图标按钮：0.96 按下 + LIGHT 触感 + 仅键盘可见焦点环（锚点 s01-bell）。 */
@Composable
internal fun OverviewIconButton(
    icon: ImageVector,
    description: String,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val haptic = rememberHaptic()
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = Modifier
            .then(modifier)
            .size(t.tapMin)
            .clip(CircleShape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, CircleShape)
            .pressFeedback(interaction)
            .keyboardFocusRing(CircleShape)
            .clickable(interactionSource = interaction, indication = null) {
                haptic(HapticTone.LIGHT)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = description, tint = t.fg2, modifier = Modifier.size(t.iconSize / 2).testTag("button.icon"))
    }
}

/**
 * 时间范围分段：三选一互斥、切换 120ms（--dur-state）、LIGHT 触感、44dp 段高 + 外框 4dp。
 * 键盘：左右方向键即时切换，Tab 只落在当前选中项（锚点 s01-range 的 click / 键盘 / focus 三行）。
 */
@Composable
internal fun RangeSegment(
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    onHaptic: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusPill)
    val focusRequesters = remember { List(RANGE_OPTIONS.size) { FocusRequester() } }
    // 键盘切换后的焦点请求不能在按键栈里直接发：此刻新段的 canFocus 还是 false
    // （下面 focusProperties 的 canFocus 要等重组才按新的 selected 重算），请求会当场失败；
    // 重组后旧段 canFocus=false、请求也落了空 → 焦点被清空，第一次切换后方向键再也到不了分段。
    // 口径照 MainScreen.kt:357-364 的 s02 chips：先记「下次该聚焦谁」，等重组把 canFocus 算完，
    // 再在 keyed effect 里请求（LaunchedEffect 在组件树应用之后才启动，那时 canFocus 已是新值）。
    var pendingFocusIndex by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(pendingFocusIndex) {
        pendingFocusIndex?.let { index ->
            runCatching { focusRequesters[index].requestFocus() }
            pendingFocusIndex = null
        }
    }
    val segmentPad = t.space1
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("s01-range")
            .height(t.tapMin + segmentPad * 2)
            .clip(shape)
            .background(t.fgFade(5))
            .border(1.dp, t.borderSoft, shape)
            .padding(segmentPad)
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val next = when (event.key) {
                    Key.DirectionLeft -> selectedIndex - 1
                    Key.DirectionRight -> selectedIndex + 1
                    else -> return@onPreviewKeyEvent false
                }
                if (next in RANGE_OPTIONS.indices) {
                    // 先切状态、再把焦点请求交给重组后的 effect（不在按键栈里零帧 requestFocus）。
                    onSelect(next)
                    pendingFocusIndex = next
                }
                true
            },
        horizontalArrangement = Arrangement.spacedBy(segmentPad),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RANGE_OPTIONS.forEachIndexed { index, label ->
            val selected = index == selectedIndex
            val bg by androidx.compose.animation.animateColorAsState(
                targetValue = if (selected) t.surface else Color.Transparent,
                animationSpec = androidx.compose.animation.core.tween<Color>(t.durState),
                label = "segBg",
            )
            val fg by androidx.compose.animation.animateColorAsState(
                targetValue = if (selected) t.fg else t.muted,
                animationSpec = androidx.compose.animation.core.tween<Color>(t.durState),
                label = "segFg",
            )
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(t.tapMin)
                    .focusRequester(focusRequesters[index])
                    .focusProperties { canFocus = selected }
                    .keyboardFocusRing(shape)
                    .clip(shape)
                    .background(bg)
                    .clickable { onHaptic(); onSelect(index) },
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

// ───────────────────────────── 五态块 ─────────────────────────────

@Composable
internal fun LoadingBlock() {
    val t = LocalDesignTokens.current
    // 骨架不接收点击、不可聚焦；超 10s 仍停在骨架（不自动转错态）。
    Box(
        Modifier
            .fillMaxSize()
            .testTag("s01-loading")
            .padding(horizontal = t.space4),
    ) {
        StateSkeleton(rows = 6)
    }
}

/**
 * 边界态内容（锚点 s01-edge）：万级徽标 + **真实**最长标题（单行省略）+ 真实字段行（缺失写「—」）。
 *
 * 事件量过万是真实用户可达态，不是调试演示：这里不写任何具体应用名 / 事件内容，
 * 只展示库里真实存在的那一条最长的记录，字段缺失就写「—」，不补 0 也不猜。
 */
@Composable
internal fun EdgeBody(count: Int, longest: Record?) {
    val t = LocalDesignTokens.current
    Row(verticalAlignment = Alignment.CenterVertically) {
        Badge(thousands(count) + " 条")
        Spacer(Modifier.width(t.space2))
        Text("已达单日展示上限", style = MaterialTheme.typography.labelMedium, color = t.muted)
    }
    Spacer(Modifier.height(t.space2))
    Text(
        text = longest?.label?.takeIf { it.isNotBlank() } ?: PLACEHOLDER,
        style = MaterialTheme.typography.bodyMedium,
        color = t.fg,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(t.space1))
    Text(
        text = edgeMetaOf(longest),
        style = MaterialTheme.typography.labelSmall,
        color = t.meta,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** 边界态字段行：时间 · 类型 · 时长；任一字段缺失写「—」（锚点 s01-edge「字段缺失显示「—」」）。 */
internal fun edgeMetaOf(record: Record?): String {
    if (record == null) return PLACEHOLDER
    val duration = record.duration
        ?.let { (it / 60_000L).toString() + " 分钟" }
        ?: PLACEHOLDER
    return formatClock(record.timestamp) + " · " + recordTypeLabel(record.type) + " · " + duration
}

// ───────────────────────────── 今日面板 ─────────────────────────────

@Composable
internal fun DeltaText(text: String, tone: DeltaTone, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = when (tone) {
            DeltaTone.SUCCESS -> t.successText
            DeltaTone.DANGER -> t.dangerText
            DeltaTone.MUTED -> t.muted
        },
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier,
    )
}

/** 可点 KPI 卡：0.96 按下 + LIGHT 触感 + 键盘焦点环；点击只重播数字滚动（锚点 s01-kpi-* click）。 */
@Composable
internal fun OverviewKpiCard(
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    delta: String? = null,
    deltaTone: DeltaTone = DeltaTone.MUTED,
    spark: List<Float>? = null,
    onHaptic: (HapticTone) -> Unit,
    value: @Composable (Int) -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    val interaction = remember { MutableInteractionSource() }
    var replay by remember { mutableIntStateOf(0) }
    Column(
        modifier = modifier
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, shape)
            .pressFeedback(interaction)
            .keyboardFocusRing(shape)
            // 长按不注册 onLongClick：无菜单 / 无放大 / 无复制浮层，按下态保持到抬起
            .clickable(interactionSource = interaction, indication = null) {
                onHaptic(HapticTone.LIGHT)
                replay++
            }
            .padding(t.space3),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = t.meta,
            modifier = Modifier.testTag("kpi.card"),
        )
        Spacer(Modifier.height(t.space2))
        Row(verticalAlignment = Alignment.Bottom) {
            value(replay)
            if (unit != null) {
                Spacer(Modifier.width(t.space1))
                Text(unit, style = MaterialTheme.typography.labelMedium, color = t.muted)
            }
        }
        Spacer(Modifier.height(t.space1))
        Row(verticalAlignment = Alignment.Bottom) {
            if (delta != null) {
                DeltaText(delta, deltaTone, Modifier.weight(1f))
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (spark != null && spark.size >= 2) {
                Spacer(Modifier.width(t.space2))
                com.bsfeng.monitor.ui.components.Sparkline(
                    values = spark,
                    modifier = Modifier.width(t.space12 + t.space6).height(t.sparkHeight),
                )
            }
        }
    }
}

/** 近 7 / 30 天 KPI：静态数值，不重播滚动、不跳转（锚点 s01-k7-* / s01-k30-*）。 */
@Composable
internal fun StaticKpiCard(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    delta: String? = null,
    deltaTone: DeltaTone = DeltaTone.MUTED,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    Column(
        modifier = modifier
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, shape)
            .padding(t.space3),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = t.meta)
        Spacer(Modifier.height(t.space2))
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                value,
                style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace),
                color = t.fg,
            )
            if (unit != null) {
                Spacer(Modifier.width(t.space1))
                Text(unit, style = MaterialTheme.typography.labelMedium, color = t.muted)
            }
        }
        Spacer(Modifier.height(t.space1))
        if (delta != null) DeltaText(delta, deltaTone)
    }
}

/** 整数滚动 + 千分位；replay 变化即从 0 重播（600ms 取组件内 t.durChart）。 */
@Composable
internal fun RollingInt(value: Int, replay: Int, t0Fg: Color) {
    val style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace)
    key(replay) {
        val shown = com.bsfeng.monitor.ui.components.animatedCount(value)
        Text(thousands(shown), style = style, color = t0Fg)
    }
}

/** h 与 m 两段同步从 0 滚入（锚点 s01-kpi-active）。 */
@Composable
internal fun RollingActive(minutes: Long, replay: Int) {
    val t = LocalDesignTokens.current
    val style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace)
    Row(verticalAlignment = Alignment.Bottom) {
        key(replay) {
            Text(
                com.bsfeng.monitor.ui.components.animatedCount((minutes / 60).toInt()).toString(),
                style = style,
                color = t.fg,
            )
        }
        Text("h", style = MaterialTheme.typography.labelMedium, color = t.muted)
        Spacer(Modifier.width(t.space1))
        key(replay) {
            Text(
                com.bsfeng.monitor.ui.components.animatedCount((minutes % 60).toInt()).toString(),
                style = style,
                color = t.fg,
            )
        }
        Text("m", style = MaterialTheme.typography.labelMedium, color = t.muted)
    }
}

/** 带 ¥ 前缀的金额滚动（千分位，锚点 s01-kpi-spend / 「¥0」）。 */
@Composable
internal fun RollingMoney(value: Double, replay: Int) {
    val t = LocalDesignTokens.current
    val style = MaterialTheme.typography.headlineMedium.copy(fontFamily = FontFamily.Monospace)
    key(replay) {
        val shown = com.bsfeng.monitor.ui.components.animatedAmount(value.toFloat())
        Text("¥" + money(shown.toDouble()), style = style, color = t.fg)
    }
}

/** 环图 + 图例（扇形与图例色同源、图例不可点）；无数据时整块写「暂无类型数据」。 */
@Composable
internal fun RingBlock(slices: List<TypeSlice>, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    if (slices.isEmpty()) {
        MetaText("暂无类型数据", modifier)
        return
    }
    val colors = slices.indices.map { t.categoryDot(it) }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        PieChart(
            segments = slices.map { it.count.toFloat() },
            modifier = Modifier.size(t.ringSize),
            colors = colors,
            size = t.ringSize,
        )
        Spacer(Modifier.width(t.space4))
        Legend(
            items = slices.mapIndexed { index, slice ->
                LegendItem(slice.label, slice.count.toString(), colors[index])
            },
            modifier = Modifier.weight(1f),
        )
    }
}

/** 主按钮：44dp、满宽、--accent 底、0.96 按下 + 指定触感（锚点 s01-quick-live / s01-empty-cta）。 */
@Composable
internal fun PrimaryTextButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tone: HapticTone = HapticTone.LIGHT,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusPill)
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(t.tapMin)
            .clip(shape)
            .background(t.accent)
            .pressFeedback(interaction)
            .keyboardFocusRing(shape)
            .clickable(interactionSource = interaction, indication = null) {
                haptic(tone)
                onClick()
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = t.onAccent,
            modifier = Modifier.testTag("button.primary"),
        )
    }
}

/** 幽灵按钮：按下底色变浅一档（1.2 倍不透明度） + LIGHT 触感（锚点 s01-quick-finance / s01-error-retry）。 */
@Composable
internal fun GhostTextButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusPill)
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(t.tapMin)
            .clip(shape)
            .background(if (pressed) t.fgFade(10) else t.fgFade(5))
            .border(1.dp, t.borderSoft, shape)
            .keyboardFocusRing(shape)
            .clickable(interactionSource = interaction, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            color = t.fg,
            modifier = Modifier.testTag("button.ghost"),
        )
    }
}


/**
 * 今日行程卡（锚点 s01-trip / s01-trip-source / trip-band）。
 * 角标与时间带不单独拦截点击，统一穿透到整卡 → 进每日行程页（口径三行同结果）。
 */
@Composable
internal fun TripCardS01(
    timeline: DayTimeline?,
    dayStart: Long,
    dayEnd: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    val interaction = remember { MutableInteractionSource() }
    val haptic = rememberHaptic()
    val hasTrack = timeline != null && (timeline.stays.isNotEmpty() || timeline.moves.isNotEmpty())
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, shape)
            .rowPressFeedback(interaction)
            .keyboardFocusRing(shape)
            .testTag("s01-trip")
            .clickable(interactionSource = interaction, indication = null) {
                haptic(HapticTone.LIGHT)
                onClick()
            }
            .padding(t.space4),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "今日行程",
                style = MaterialTheme.typography.titleSmall,
                color = t.fg,
                modifier = Modifier.weight(1f),
            )
            // 无数据时角标换成「暂无数据」，不宣称推算基准
            Box(Modifier.testTag("s01-trip-source")) {
                Badge(if (timeline == null) "暂无数据" else basisBadge(timeline.basis))
            }
        }
        Spacer(Modifier.height(t.space3))
        if (!hasTrack) {
            Text(
                text = if (timeline == null) "今天还没有行程" else tripHeadline(timeline),
                style = MaterialTheme.typography.bodyMedium,
                color = t.fg,
            )
            Spacer(Modifier.height(t.space1))
            MetaText("停留与移动由设备活动推算；采集中断的时间标成「未记录」，不做推断。")
        } else {
            Column(Modifier.testTag("trip-band")) {
                TripBand(segments = timeline.segments, dayStart = dayStart, dayEnd = dayEnd)
                Spacer(Modifier.height(t.space1))
                TripBandAxis()
                Spacer(Modifier.height(t.space2))
                TripBandLegend(timeline)
                Spacer(Modifier.height(t.space3))
                Text(
                    text = tripHeadline(timeline),
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = t.fg,
                )
                Spacer(Modifier.height(t.space1))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    MetaText(tripCoverageNote(timeline))
                    MetaText("查看时间轴 ›")
                }
            }
        }
    }
}

// ───────────────────────────── 格式化（文案逐字） ─────────────────────────────

internal fun thousands(value: Int): String = String.format(Locale.US, "%,d", value)

/** 口径「较昨日 +18 / 较昨日 0 / 较昨日 -4」：0 不带符号。 */
internal fun signedDelta(delta: Int): String = when {
    delta > 0 -> "+" + delta
    delta < 0 -> delta.toString()
    else -> "0"
}

/**
 * 事件类环比副行（锚点 s01-k7-events「较上周 +6.2%」/ s01-k30-events「较上月 +9.8%」）。
 *
 * 基数来自数据层真实 COUNT（上一段同长度窗口）；基数为 0 时比例没有定义 → 写「—」，
 * 不用 100% 之类编出来的数字充数。
 */
internal fun eventDeltaText(prefix: String, current: Int, previous: Int): String {
    if (previous <= 0) return "$prefix —"
    val pct = (current - previous) * 100.0 / previous
    // 涨写「+6.2%」、跌写「-9.8%」、持平写「0.0%」（0 不带符号，与 s01-kpi 的「较昨日 0」同口径）
    val shown = if (pct > 0) String.format(Locale.US, "+%.1f%%", pct)
    else String.format(Locale.US, "%.1f%%", pct)
    return "$prefix $shown"
}

/** 跳过类环比副行（锚点 s01-k7-skip「较上周 +31」/ s01-k30-skip「较上月 +122」）：给计数差。 */
internal fun countDeltaText(prefix: String, current: Int, previous: Int): String =
    "$prefix " + signedDelta(current - previous)

/** 环比色调：涨走 --success、跌走 --danger；事件类基数为 0 时无可比基数 → MUTED。 */
internal fun deltaToneOf(current: Int, previous: Int, percent: Boolean): DeltaTone =
    if (percent && previous <= 0) DeltaTone.MUTED else toneOf(current - previous)

/** 「42h 05m」：时分两段等宽（锚点 s01-k7-active）。 */
internal fun hoursMinutes(minutes: Long): String =
    (minutes / 60).toString() + "h " + String.format(Locale.US, "%02d", minutes % 60) + "m"

/** 「日均 6h01m」（锚点 s01-k7-active / s01-k30-active）。 */
internal fun dailyHoursMinutes(totalMinutes: Long, days: Int): String {
    val perDay = if (days <= 0) 0L else totalMinutes / days
    return (perDay / 60).toString() + "h" + String.format(Locale.US, "%02d", perDay % 60) + "m"
}