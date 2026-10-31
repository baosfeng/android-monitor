package com.bsfeng.monitor.ui

// 功能细则页「位置采集」的采集覆盖卡（P0-3）：把最近 24 小时的采集成色块，断档看得见。
//
// 为什么单独成卡而不是塞进权限引导卡：引导卡回答「权限对不对」，这张卡回答「过去一天到底采到了多少」。
// 掉线原来只有进程内的瞬时状态，重启归零、事后不可查；没有这条时间线，前台服务改造做完也无法验收。
//
// 视觉与文案对齐原型 .open-design/41-location-health.html「位置采集 · 已就绪」屏的采集覆盖卡：
// 覆盖条 34dp / 段间 1px / 分档占比 / 刻度标窗口首·中·末的真实时刻 / 图例四项 / 断档列表最多 5 条。
//
// 分档口径（真机校正过，见 record/CoverageTimeline.kt）：有采样 / **省电待机** / 断档 / 深断档。
// 功耗门禁 SLEEPING 时静止不采点、只按 30 分钟兜底，那些间隔是设计内静默 —— 画成中性灰。
// 覆盖率把待机计入分子，卡头「有覆盖时长」= 有采样 + 待机，与徽标百分比同口径。
//
// 契约：docs/交互设计/s09-设置-交互契约.md 的 s09-loc-cov / -sub / -badge / -bar / -gap-list。
// 卡片内没有任何可点元素（断档行也不开详情）—— 它只回答「是什么样」，动作都在引导卡里。

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BarChart
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.record.CoverageFailureLog
import com.bsfeng.monitor.record.CoverageKind
import com.bsfeng.monitor.record.CoverageLevel
import com.bsfeng.monitor.record.CoverageReader
import com.bsfeng.monitor.record.CoverageSnapshot
import com.bsfeng.monitor.record.coverageDurationText
import com.bsfeng.monitor.record.coverageSubtitle
import com.bsfeng.monitor.record.CoverageWindow
import com.bsfeng.monitor.record.LocationCollector
import com.bsfeng.monitor.record.LocationFailureReason
import com.bsfeng.monitor.record.SampleMode
import com.bsfeng.monitor.record.StoreCoverageReader
import com.bsfeng.monitor.ui.components.AppCard
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BadgeTone
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.formatClock
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.fgFade
import com.bsfeng.monitor.ui.theme.monitorMonoMeta
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** 覆盖条高度：原型 .cov-bar 的 34px。 */
private val BAR_HEIGHT = 34.dp

/** 断档列表最多几条：与 CoverageTimeline.MAX_GAPS 同口径（第 6 条起不渲染）。 */
private const val GAP_ROWS = 5

/** 刷新间隔：一分钟（覆盖条按分钟粒度缓存，更快的刷新读到的还是同一份）。 */
private const val REFRESH_MS = 60_000L

/** 空态文案（契约 s09-loc-cov 边界逐字）。 */
private const val EMPTY_TEXT = "过去 24 小时没有采样记录"

/**
 * 采集覆盖卡。
 *
 * @param reader 数据源；默认走 StoreCoverageReader + 进程内失败日志（生产路径）
 */
@Composable
internal fun SettingsLocationCoverageCard(
    modifier: Modifier = Modifier,
    reader: CoverageReader? = rememberDefaultCoverageReader(),
) {
    val t = LocalDesignTokens.current
    val snapshot by produceState<CoverageSnapshot?>(initialValue = null, reader) {
        val source = reader ?: return@produceState
        while (true) {
            value = withContext(Dispatchers.IO) { source.read() }
            delay(REFRESH_MS)
        }
    }
    CoverageCardBody(t = t, snapshot = snapshot, modifier = modifier)
}

/**
 * 默认数据源：从 DI 容器取记录库，接上全局失败日志与采集器的当前状态。
 *
 * 容器尚未就绪（极早期组合 / 预览宿主）时返回 null，卡片退化为空态，不崩。
 */
@Composable
private fun rememberDefaultCoverageReader(): CoverageReader? {
    val context = LocalContext.current
    return remember(context) {
        val store = runCatching { MonitorApp.instance.container.recordStore }.getOrNull() ?: return@remember null
        StoreCoverageReader(
            store = store,
            failures = CoverageFailureLog.shared,
            modeOf = { runCatching { LocationCollector.get(context).mode }.getOrDefault(SampleMode.STILL) },
            statusOf = { runCatching { LocationCollector.get(context).health() }.getOrNull() },
        )
    }
}

/** 卡片正文（与数据源解耦：预览 / 测试可直接喂一份快照）。 */
@Composable
private fun CoverageCardBody(t: DesignTokens, snapshot: CoverageSnapshot?, modifier: Modifier = Modifier) {
    AppCard(
        modifier = modifier
            .padding(horizontal = t.space4)
            .testTag("s09-loc-cov"),
        padding = 0.dp,
    ) {
        SettingsRow(
            title = "采集覆盖",
            subtitle = coverageSubtitleOf(snapshot),
            subtitleModifier = Modifier.testTag("s09-loc-cov-sub"),
            icon = Icons.Outlined.BarChart,
            trailing = snapshot?.takeIf { !it.isEmpty }?.let { ready ->
                {
                    // 徽标只在有采样时出现（契约 s09-loc-cov-badge 前置）：空窗口写百分比没有意义
                    Badge(
                        text = ready.window.coveragePct.toString() + "%",
                        modifier = Modifier.widthIn(min = 64.dp).testTag("s09-loc-cov-badge"),
                        tone = coverageTone(ready.window.coveragePct),
                    )
                }
            },
        )
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = t.space4, end = t.space4, bottom = t.space4),
        ) {
            val window = snapshot?.window
            val failure = snapshot?.error
            when {
                // 读库失败：不画空条，直接说明（空条会被读成「一整天都没采到」）
                failure != null -> CoverageEmptyText(t, "读取采集覆盖失败：" + failure)
                window == null || window.isEmpty -> CoverageEmptyText(t, EMPTY_TEXT)
                else -> {
                    Spacer(Modifier.height(t.space2))
                    CoverageHead(t, window)
                    Spacer(Modifier.height(t.space2))
                    CoverageBar(
                        window = window,
                        height = BAR_HEIGHT,
                        modifier = Modifier.testTag("s09-loc-cov-bar"),
                    )
                    CoverageAxis(t, window)
                    Spacer(Modifier.height(t.space3))
                    CoverageLegend(t)
                    // 断档列表：record 层已按时间**倒序**（最新在前）取最新 5 条，这里只做上限兜底、不重排 ——
                    // 再 reversed / sortedBy 一次就会把最值得看的「刚刚那段」翻到屏幕外
                    val gaps = snapshot.gaps.take(GAP_ROWS)
                    if (gaps.isNotEmpty()) {
                        Spacer(Modifier.height(t.space3))
                        Column(Modifier.fillMaxWidth().testTag("s09-loc-gap-list")) {
                            gaps.forEachIndexed { index, gap ->
                                if (index > 0) {
                                    Box(Modifier.fillMaxWidth().height(1.dp).background(t.borderSoft))
                                }
                                GapRow(t, gap.startMs, gap.durationMs, gap.kind, gap.reason)
                            }
                        }
                    }
                    Spacer(Modifier.height(t.space2))
                    MetaText("断档由采集器自动记录，不做补点、不猜位置。")
                }
            }
        }
    }
}

/** 空态：一条采样都没有（或读库失败）。不画空条、不画刻度、不出图例。 */
@Composable
private fun CoverageEmptyText(t: DesignTokens, text: String) {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(vertical = t.space3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = t.muted,
            textAlign = TextAlign.Center,
        )
    }
}

/**
 * 卡内第一行：有覆盖时长 + 窗口总长（原型 .cov-head）。
 *
 * 左侧取「有采样 + 省电待机」而不是只算有采样：待机半天的一天里 coveredMs 可能接近 0，
 * 只写它就会出现「0 分钟 / 共 24 小时」配 98% 徽标这种自相矛盾的一屏。
 */
@Composable
private fun CoverageHead(t: DesignTokens, window: CoverageWindow) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Text(
            text = coverageDurationText(window.coveredMs + window.dormantMs),
            style = MaterialTheme.typography.titleSmall,
            color = t.fg,
            modifier = Modifier.weight(1f),
        )
        MetaText("共 " + coverageDurationText(window.totalMs))
    }
}

/**
 * 覆盖条：按真实时长等比分配的四色块，段间留 1px 缝。
 *
 * 用 Canvas 而不是 Row 权重：段数最多几十段、缝只有 1px，权重分配在小屏上会把 1px 缝抹掉，
 * 相邻同色块糊成一片就看不出断档边界了。
 */
@Composable
private fun CoverageBar(
    window: CoverageWindow,
    height: Dp,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    val segments = window.segments
    val total = window.totalMs.coerceAtLeast(1L)
    Canvas(
        modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(t.radiusSm))
            .background(t.fgFade(6)),
    ) {
        if (segments.isEmpty()) return@Canvas
        val gapPx = 1.dp.toPx()
        var cursor = 0f
        segments.forEachIndexed { index, segment ->
            val width = size.width * (segment.durationMs.toFloat() / total)
            val left = cursor
            // 最后一段吃满到右边界（避免浮点误差留下 1px 空白）
            val right = if (index == segments.lastIndex) size.width else left + width - gapPx
            if (right > left) {
                drawRect(
                    color = segmentColor(t, segment.kind),
                    topLeft = Offset(left, 0f),
                    size = Size(right - left, size.height),
                )
            }
            cursor = left + width
        }
    }
}

/** 段色：与原型 .cov-seg 的 color-mix 一致；待机沿用中性灰，不借警告色。 */
private fun segmentColor(t: DesignTokens, kind: CoverageKind): Color = when (kind) {
    CoverageKind.COVERED -> t.success.copy(alpha = 0.62f)
    // 省电待机：中性灰（原 OFF 那一档）—— 它是设计内静默，染成 warn 会让静止的一天看起来像在报警
    CoverageKind.DORMANT -> t.fg.copy(alpha = 0.14f)
    CoverageKind.GAP -> t.warn.copy(alpha = 0.58f)
    CoverageKind.DEEP -> t.danger.copy(alpha = 0.55f)
    CoverageKind.OFF -> t.fg.copy(alpha = 0.14f)
}

/**
 * 时间轴刻度：窗口起点 / 中点 / 终点，**标真实时刻**。
 *
 * 为什么不用原型那组固定的 0:00 / 12:00 / 24:00：这张卡的窗口是「最近 24 小时」的滚动窗口
 * （起点是此刻 - 24h，不是当天 0 点），把刻度写死成 0:00 → 24:00 会指向错误时刻 ——
 * 例如 14:20 打开页面时，最左边那一格其实是昨天 14:20。刻度是给人对时间的，
 * 对错了比不标更糟。行程页的 trip-cov 是「当天」窗口，同样按真实时刻标（见 TripMapCard）。
 */
@Composable
private fun CoverageAxis(t: DesignTokens, window: CoverageWindow) {
    val mid = window.startMs + window.totalMs / 2
    Row(
        Modifier.fillMaxWidth().padding(top = t.space1),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        listOf(window.startMs, mid, window.endMs).forEach { at ->
            Text(formatClock(at), style = monitorMonoMeta, color = t.muted)
        }
    }
}

/** 图例四项（2×2，不换行）：色块与覆盖条同色。 */
@Composable
private fun CoverageLegend(t: DesignTokens) {
    // OFF 不占图例位：当前没有可靠依据判「功能关闭」时段，恒不出现，占位只会让四项里有一项永远对不上
    val items = listOf(
        CoverageKind.COVERED to "有采样",
        CoverageKind.DORMANT to "省电待机",
        CoverageKind.GAP to "断档",
        CoverageKind.DEEP to "深断档",
    )
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(t.space2)) {
        items.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(t.space3)) {
                row.forEach { (kind, label) ->
                    LegendChip(t, kind, label, Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun LegendChip(t: DesignTokens, kind: CoverageKind, label: String, modifier: Modifier = Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(10.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(segmentColor(t, kind)),
        )
        Spacer(Modifier.width(t.space2))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = t.muted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** 断档行：起始时刻（等宽）+ 时长 + 原因；深断档用 danger、浅断档用 warn。 */
@Composable
private fun GapRow(
    t: DesignTokens,
    startMs: Long,
    durationMs: Long,
    kind: CoverageKind,
    reason: LocationFailureReason?,
) {
    val accent = if (kind == CoverageKind.DEEP) t.dangerText else t.warnText
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = t.tapMin)
            .padding(vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = formatClock(startMs),
            style = monitorMonoMeta,
            color = t.fg2,
        )
        Spacer(Modifier.width(t.space3))
        Column(Modifier.weight(1f)) {
            Text(
                text = coverageDurationText(durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = accent,
                maxLines = 1,
            )
            Text(
                // 归因不到就写「未知」：不把一次成功或节流伪装成断档原因
                text = reason?.let { LocationHealthText.reasonText(it) } ?: "未知",
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** 徽标色调：>=90% 正常、60~89% 警告、<60% 危险（分档阈值见 CoverageLevel，契约 s09-loc-cov-badge）。 */
internal fun coverageTone(pct: Int): BadgeTone = when (CoverageLevel.of(pct)) {
    CoverageLevel.GOOD -> BadgeTone.Success
    CoverageLevel.WARN -> BadgeTone.Warn
    CoverageLevel.LOW -> BadgeTone.Danger
}

/** 卡副标题：由纯函数区算，空窗口时退化成「没有采样」（不写「断档 0 次」）。 */
internal fun coverageSubtitleOf(snapshot: CoverageSnapshot?): String =
    coverageSubtitle(snapshot?.window ?: CoverageWindow(0, 0))