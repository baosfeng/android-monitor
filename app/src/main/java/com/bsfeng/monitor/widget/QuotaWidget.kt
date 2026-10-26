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
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.text.Text
import androidx.glance.unit.ColorProvider
import com.bsfeng.monitor.MainActivity
import com.bsfeng.monitor.R

// 额度见底：三档内容 = 一行（Tiny 兜底）/ 最紧急一家 + 百分比（Compact）/ 前三家 + 进度条（Wide）。
// 视觉走 C 版「柔和品牌色」：紫 tint 卡底 + 紫色图标块（见 WidgetStyle.kt）。

private const val LABEL_TITLE = "额度剩余"
private const val EMPTY_TITLE = "未配置额度"
private const val EMPTY_HINT = "点开设置添加 provider"
private const val NO_ROWS = "暂无额度数据"
private const val NEVER_FETCHED = "—"

/**
 * Wide 最多放前三家（4×2 的 ~170dp 高度正好铺满），Compact / Tiny 只留最紧急的一家。
 *
 * 行数还要按高度收：宽度定档后，4×1 这类被压扁的形态也算 Wide，但净高只够一行 ——
 * 三行的内容塞进 78dp 会被裁掉最后一行，所以按 [wideRowCount] 递减。
 */
private const val WIDE_ROW_COUNT = 3

/** 3 行需 ~132dp 内容高（含行距与轨道），净高 = 组件高 - 32dp 内边距。 */
private const val THREE_ROWS_MIN_HEIGHT_DP = 164f

/** 2 行需 ~98dp 内容高。 */
private const val TWO_ROWS_MIN_HEIGHT_DP = 130f

/** 按可用高度决定 Wide 显示几行：能放三家就三家，放不下就减少行数而不是裁字。 */
internal fun wideRowCount(heightDp: Float): Int = when {
    heightDp >= THREE_ROWS_MIN_HEIGHT_DP -> WIDE_ROW_COUNT
    heightDp >= TWO_ROWS_MIN_HEIGHT_DP -> 2
    else -> 1
}

/** 剩余百分比文案：18%。 */
internal fun quotaPercentText(row: QuotaRow): String = row.remainingPercent.toString() + "%"

/**
 * 剩余百分比 → 语义色：≤20% 红（该充值了）、≤50% 橙（本月别乱花）、其余蓝（正常）。
 *
 * 只按数值分级、不读用户设置：额度组件是「看一眼就知道谁见底」的工具，
 * 颜色必须跨组件一致，跟着主题走反而会让同一个 18% 在不同手机上读成不同紧急度。
 */
internal fun quotaPercentColor(remainingPercent: Int): ColorProvider = when {
    remainingPercent <= 20 -> WIDGET_EXPENSE
    remainingPercent <= 50 -> WIDGET_WARN
    else -> WIDGET_ACCENT
}

/**
 * 额度见底（谁最先见底）。
 *
 * 数据在 [provideGlance] 里先读、再进 composition。组件内不联网，只读 [WidgetDataProvider.quota]
 * 的落盘快照，拉取仍由 App 内的 QuotaScheduler 负责。
 */
class QuotaWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetDataProvider.quota(context)
        val box = widgetBox(context, id)
        provideContent { QuotaCard(data, box) }
    }
}

class QuotaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuotaWidget()
}

/**
 * 按 [tierOf] 分三档渲染（判档看宽度，为什么这么判见 WidgetLayoutPolicy）。
 *
 * - rows 已按剩余百分比升序，取前 N 行就是把最紧的几家排在最上面，composable 里不再排序；
 * - Wide（≥280dp）才给进度条：轨道要占一整行，2 格宽下名称与百分比会被挤到换行；
 * - Compact（140–280dp）只放最紧急的一家 + 百分比；
 * - Tiny（<140dp）不是设计承诺的形态（info 限定 2×1 / 4×2），用户拖到 1×1 时退化成一行；
 * - 空态优先于档位判断：没配置 / 没有快照时只该说「去配置」，不该显示 0%。
 */
@Composable
private fun QuotaCard(data: QuotaWidgetData, box: WidgetBox) {
    val context = LocalContext.current
    val tier = if (box.isUsable) tierOf(box.widthDp, box.heightDp) else WidgetTier.COMPACT
    val empty = !data.configured || data.rows.isEmpty()
    WidgetCard(
        face = FACE_QUOTA,
        action = startQuota(context),
        verticalAlignment = if (empty) Alignment.Vertical.CenterVertically else tierAlignment(tier),
        padding = if (tier == WidgetTier.TINY) 12.dp else 16.dp,
    ) {
        when {
            !data.configured -> QuotaEmpty(title = EMPTY_TITLE, hint = EMPTY_HINT)
            data.rows.isEmpty() -> QuotaEmpty(title = NO_ROWS, hint = data.fetchedAtText.orEmpty())
            tier == WidgetTier.WIDE -> QuotaWide(data, wideRowCount(box.heightDp))
            tier == WidgetTier.COMPACT -> QuotaCompact(data)
            else -> QuotaTiny(data)
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

@Composable
private fun ColumnScope.QuotaWide(data: QuotaWidgetData, rowCount: Int) {
    CardHead(
        face = FACE_QUOTA,
        symbol = "%",
        label = LABEL_TITLE,
        trailing = data.fetchedAtText,
    )
    Spacer(GlanceModifier.height(8.dp))
    data.rows.take(rowCount).forEach { row ->
        val color = quotaPercentColor(row.remainingPercent)
        // 每行包成一个内层 Column：Glance 的 Column 最多 10 个直接子元素，
        // 超出部分会被静默截断（LayoutSelectionKt: "Column container cannot have more than 10 elements"）。
        // 三行直铺是「顶行 + 间距 + 3×(行 + 轨道 + 2 个间距)」= 14 个，第三家的轨道会被丢掉；
        // 包一层后外层只剩 5 个，内层每行 3 个，都在限内。
        Column(modifier = GlanceModifier.fillMaxWidth()) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
                Text(text = row.label, style = valueStyle(WIDGET_TEXT, 14.sp), maxLines = 1)
                Spacer(GlanceModifier.defaultWeight())
                Text(text = quotaPercentText(row), style = valueStyle(color, 16.sp), maxLines = 1)
            }
            Spacer(GlanceModifier.height(4.dp))
            Bar(
                progress = row.remainingPercent / 100f,
                face = FACE_QUOTA,
                color = color,
                thickness = 6.dp,
            )
        }
        Spacer(GlanceModifier.height(7.dp))
    }
}

@Composable
private fun QuotaCompact(data: QuotaWidgetData) {
    CardHead(
        face = FACE_QUOTA,
        symbol = "%",
        label = LABEL_TITLE,
        trailing = data.fetchedAtText,
        badge = 22.dp,
    )
    Spacer(GlanceModifier.height(8.dp))
    val row = data.rows.first()
    val color = quotaPercentColor(row.remainingPercent)
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Text(text = row.label, style = valueStyle(WIDGET_TEXT, 14.sp), maxLines = 1)
        Spacer(GlanceModifier.defaultWeight())
        Text(text = quotaPercentText(row), style = valueStyle(color, 22.sp), maxLines = 1)
    }
    Spacer(GlanceModifier.height(6.dp))
    Bar(progress = row.remainingPercent / 100f, face = FACE_QUOTA, color = color, thickness = 6.dp)
}

/** 1×1 兜底：连「额度剩余」标题都省掉，只留最紧急一家的名称与百分比。 */
@Composable
private fun QuotaTiny(data: QuotaWidgetData) {
    val row = data.rows.first()
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Text(text = row.label, style = labelStyle(WIDGET_TEXT), maxLines = 1)
        Spacer(GlanceModifier.defaultWeight())
        Text(
            text = quotaPercentText(row),
            style = valueStyle(quotaPercentColor(row.remainingPercent), 17.sp),
            maxLines = 1,
        )
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
