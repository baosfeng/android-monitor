package com.bsfeng.monitor.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.text.Text
import com.bsfeng.monitor.MainActivity

// 额度组件：与额度页账号卡同一套语言（值 + 窗口 + 条），4×2 档 ——
// 顶栏「▣ 额度 · 更新于 4 分钟前」+ 2 列网格，每格 = 等宽数值 / 「账号 窗口 · 重置时刻」/ 4dp 细横条。
//
// 三件不变的事：数据只读落盘快照（组件内不联网，拉取仍由 App 内 QuotaScheduler 负责）；
// 整块点击直达额度 Tab；格数按占格降档，绝不裁字。

private const val EMPTY_TITLE = "未配置额度"
private const val EMPTY_HINT = "点开设置添加 provider"
private const val NO_ROWS = "暂无额度数据"

/** 尺寸读不到（首次绑定 / 读 options 异常）时的兜底占格：4×2 的声明最小值，宁可少一格也不白板。 */
private const val FALLBACK_WIDTH_DP = 250f
private const val FALLBACK_HEIGHT_DP = 110f

/**
 * 额度组件。
 *
 * 数据在 [provideGlance] 里先读、再进 composition：Glance 的 composition 跑在 RemoteViews 里，
 * 读库失败只会在桌面上留一张白板，所以读盘必须发生在 provideContent 之前。
 */
class QuotaWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val feed = WidgetDataProvider.quota(context)
        val box = widgetBox(context, id)
        provideContent { QuotaCard(feed, box) }
    }
}

class QuotaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuotaWidget()
}

/**
 * 内容编排：空态优先于档位判断（没配置 / 没有快照时只说「去配置」，不显示 0%）。
 *
 * - [quotaWidgetGrid] 定列数 / 格数 / 要不要顶栏，[QuotaWidgetMetrics.of] 定字阶与间距，都是纯函数；
 * - [quotaWidgetCells] 定「哪几个账号、每格什么文案」，同样纯函数；
 * - 金额是否隐藏读 [WidgetSettings.isAmountHidden]，与记账组件同一开关。
 */
@Composable
private fun QuotaCard(feed: QuotaWidgetFeed, box: WidgetBox) {
    val context = LocalContext.current
    val widthDp = if (box.isUsable) box.widthDp else FALLBACK_WIDTH_DP
    val heightDp = if (box.isUsable) box.heightDp else FALLBACK_HEIGHT_DP
    val grid = quotaWidgetGrid(widthDp, heightDp)
    val scale = QuotaWidgetMetrics.of(heightDp)
    val cells = quotaWidgetCells(
        accounts = feed.accounts,
        limit = grid.cells,
        nowEpochSecond = System.currentTimeMillis() / 1000L,
        hiddenAmounts = WidgetSettings.isAmountHidden(context),
        // 一格放不放得下「· 重置时刻」按每格可用宽判，不是按箱子宽（见 quotaCaptionText）
        captionWidthDp = quotaCellWidthDp(widthDp, grid.columns, scale),
    )
    val empty = !feed.configured || cells.isEmpty()
    WidgetCard(
        face = FACE_QUOTA,
        action = startQuota(context),
        verticalAlignment = if (empty) Alignment.Vertical.CenterVertically else Alignment.Vertical.Top,
        padding = scale.padding.dp,
    ) {
        when {
            !feed.configured -> QuotaEmpty(title = EMPTY_TITLE, hint = EMPTY_HINT)
            cells.isEmpty() -> QuotaEmpty(title = NO_ROWS, hint = feed.fetchedAtText.orEmpty())
            else -> {
                if (grid.showHeader) {
                    QuotaHeader(feed = feed, stale = feed.accounts.any { it.stale }, scale = scale)
                    Spacer(GlanceModifier.height(scale.headerGap.dp))
                }
                QuotaCells(cells = cells, grid = grid, scale = scale)
            }
        }
    }
}

/** 整块直达额度 Tab（下标见 [MainActivity.QUOTA_TAB]）。 */
private fun startQuota(context: Context): Action = actionStartActivity(
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_START_TAB, MainActivity.QUOTA_TAB)
    },
)

/**
 * 顶栏：实色图标块 + 「额度 · 更新于 …」。
 *
 * 图标块复用 [IconBadge]：矢量图标在 Glance 里会被渲染成空白（实测结论见 WidgetStyle 的 IconBadge 注释），
 * 字符「%」零资源依赖且跟着字号缩放。
 */
@Composable
private fun QuotaHeader(feed: QuotaWidgetFeed, stale: Boolean, scale: QuotaWidgetScale) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        IconBadge(face = FACE_QUOTA, symbol = "%", size = scale.headerBadge.dp)
        Spacer(GlanceModifier.width(scale.headerBadgeGap.dp))
        Text(
            text = quotaHeaderText(feed.fetchedAtText, stale),
            style = quotaHeaderStyle(FACE_QUOTA.icon, scale.headerSizeSp),
            maxLines = 1,
        )
    }
}

/**
 * 2 列（或 1 列）网格。
 *
 * 为什么每行包一层 Row：Glance 的 Column 超过 10 个直接子元素会被静默丢弃
 * （WidgetStyle 的 WidgetCard 注释里有完整结论），4 格直铺是「格 + 间距」×4 + 顶栏 = 9 个，
 * 分组后外层只剩 3 个。
 */
@Composable
private fun ColumnScope.QuotaCells(cells: List<QuotaWidgetCell>, grid: QuotaWidgetGrid, scale: QuotaWidgetScale) {
    val rows = cells.chunked(grid.columns)
    rows.forEachIndexed { index, rowCells ->
        Row(
            modifier = GlanceModifier.fillMaxWidth(),
            verticalAlignment = Alignment.Vertical.Top,
        ) {
            rowCells.forEachIndexed { columnIndex, cell ->
                if (columnIndex > 0) Spacer(GlanceModifier.width(scale.columnGap.dp))
                QuotaCell(cell = cell, scale = scale, modifier = GlanceModifier.defaultWeight())
            }
            // 最后一行不满时补空位：3 个账号 / 4 格时最后一格不该被拉成整宽
            repeat(grid.columns - rowCells.size) {
                Spacer(GlanceModifier.defaultWeight())
            }
        }
        if (index != rows.lastIndex) {
            Spacer(GlanceModifier.height(scale.rowGap.dp))
        }
    }
}

/**
 * 一格：数值 / 说明 / 4dp 横条。
 *
 * 横条只在有分母时画（纯余额型没有「还剩百分之多少」这回事）；此时该格比同排的矮一节，
 * 顶对齐后数值仍在同一基线上，视觉上不会错位。
 */
@Composable
private fun QuotaCell(cell: QuotaWidgetCell, scale: QuotaWidgetScale, modifier: GlanceModifier) {
    Column(modifier = modifier) {
        Text(
            text = cell.valueText,
            style = quotaValueStyle(FACE_QUOTA.icon, scale.valueSizeSp),
            maxLines = 1,
        )
        Spacer(GlanceModifier.height(scale.captionGap.dp))
        Text(
            text = cell.captionText,
            style = quotaCaptionStyle(scale.captionSizeSp),
            maxLines = 1,
        )
        val fraction = cell.fraction
        if (fraction != null) {
            Spacer(GlanceModifier.height(scale.barGap.dp))
            Bar(
                progress = fraction,
                face = FACE_QUOTA,
                // 原型 .wr .track>i 用的是 --w-ink（与数值同色）；这里取卡面墨色 icon，而不是更浅的 bar
                color = FACE_QUOTA.icon,
                thickness = scale.barHeight.dp,
            )
        }
    }
}

/** 空态：未配置额度 / 没有快照。标题用 17sp 而不是主数值字号，六个汉字在 2×1 里会被截断。 */
@Composable
private fun QuotaEmpty(title: String, hint: String) {
    Text(text = title, style = titleStyle(WIDGET_MUTED), maxLines = 1)
    if (hint.isNotEmpty()) {
        Spacer(GlanceModifier.height(4.dp))
        Text(text = hint, style = labelStyle(), maxLines = 1)
    }
}