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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.width
import androidx.glance.text.Text
import com.bsfeng.monitor.MainActivity
import com.bsfeng.monitor.R

// 跳过战报：三档内容 = 今日数字（Tiny）/ 今日 + 累计 + 确认率（Compact）/ 再加近 7 天（Wide）。
// 视觉走 C 版「柔和品牌色」：橙 tint 卡底 + 橙色图标块 + 三列读数带竖分隔（见 WidgetStyle.kt）。

private const val LABEL_TITLE = "跳过战报"
private const val LABEL_TODAY = "今日"
private const val LABEL_TODAY_SKIP = "今日跳过"
private const val LABEL_WEEK = "近 7 天"
private const val LABEL_TOTAL = "累计"
private const val LABEL_CONFIRM = "确认率"
private const val EMPTY_TODAY = "今天还没跳过"
private const val NO_RATE = "—"
private const val TOTAL_PREFIX = "累计 "

/**
 * 确认率：confirmed / total 四舍五入成整数百分比；total = 0 时给「—」。
 *
 * 为什么 0/0 不说 0%：0/0 是「还没有样本」，0% 是「跳过了一次但都没确认掉」，
 * 两者在桌面上长得一样就没法用来判断要不要调规则。
 */
internal fun confirmRateText(confirmed: Int, total: Int): String =
    if (total <= 0) NO_RATE else percent(confirmed.toFloat() / total).toString() + "%"

/**
 * 跳过战报。
 *
 * 数据在 [provideGlance] 里先读、再进 composition：provideContent 的 lambda 会在任意线程被重新组合，
 * 里面出现 IO 会直接体现在首帧耗时上。
 */
class AdSkipWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetDataProvider.ads(context)
        val box = widgetBox(context, id)
        provideContent { AdSkipCard(data, box) }
    }
}

class AdSkipWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AdSkipWidget()
}

/**
 * 按 [tierOf] 分三档渲染（判档看宽度，为什么这么判见 WidgetLayoutPolicy）。
 *
 * - Tiny（<140dp，1 格宽）：图标 + 今日数字（「今天还没跳过」六个汉字在 1 格宽会被截成省略号）；
 * - Compact（2 格宽 / 2×1）：今日 / 累计 / 确认率三列，1fr 均分 + 1dp 竖线，最后一个不贴右边；
 * - Wide（≥280dp）：加近 7 天，四列；
 * - 空态（今天 0 次）只在有宽度说整句话的档位出现。
 */
@Composable
private fun AdSkipCard(data: AdWidgetData, box: WidgetBox) {
    val context = LocalContext.current
    val tier = if (box.isUsable) tierOf(box.widthDp, box.heightDp) else WidgetTier.COMPACT
    val todayEmpty = data.todayCount == 0
    WidgetCard(
        face = FACE_ADSKIP,
        action = startAds(context),
        verticalAlignment = tierAlignment(tier),
        padding = if (tier == WidgetTier.TINY) 12.dp else 16.dp,
    ) {
        when {
            tier == WidgetTier.TINY -> AdTiny(data)
            todayEmpty -> AdEmpty(data)
            tier == WidgetTier.WIDE -> AdWide(data)
            else -> AdCompact(data)
        }
    }
}

/** 整块直达广告 Tab（下标见 [MainActivity.AD_TAB]）。 */
private fun startAds(context: Context): Action = actionStartActivity(
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_START_TAB, MainActivity.AD_TAB)
    },
)

@Composable
private fun AdTiny(data: AdWidgetData) {
    IconBadge(face = FACE_ADSKIP, symbol = "»", size = 18.dp)
    Spacer(GlanceModifier.height(4.dp))
    Text(text = count(data.todayCount), style = numberStyle(size = 17.sp), maxLines = 1)
    Text(text = LABEL_TODAY_SKIP, style = labelStyle(), maxLines = 1)
}

@Composable
private fun AdCompact(data: AdWidgetData) {
    CardHead(
        face = FACE_ADSKIP,
        symbol = "»",
        label = LABEL_TITLE,
        badge = 22.dp,
    )
    Spacer(GlanceModifier.height(8.dp))
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Stat(
            label = LABEL_TODAY,
            value = count(data.todayCount),
            valueSize = 20.sp,
            modifier = GlanceModifier.defaultWeight(),
        )
        VDivider()
        Spacer(GlanceModifier.width(10.dp))
        Stat(
            label = LABEL_TOTAL,
            value = count(data.totalCount),
            valueSize = 20.sp,
            modifier = GlanceModifier.defaultWeight(),
        )
        VDivider()
        Spacer(GlanceModifier.width(10.dp))
        Stat(
            label = LABEL_CONFIRM,
            value = confirmRateText(data.confirmedCount, data.totalCount),
            valueSize = 20.sp,
            modifier = GlanceModifier.defaultWeight(),
        )
    }
}

@Composable
private fun AdWide(data: AdWidgetData) {
    CardHead(
        face = FACE_ADSKIP,
        symbol = "»",
        label = LABEL_TITLE,
    )
    Spacer(GlanceModifier.height(8.dp))
    Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
        Stat(LABEL_TODAY, count(data.todayCount), valueSize = 20.sp, modifier = GlanceModifier.defaultWeight())
        VDivider()
        Spacer(GlanceModifier.width(10.dp))
        Stat(LABEL_WEEK, count(data.weekCount), valueSize = 20.sp, modifier = GlanceModifier.defaultWeight())
        VDivider()
        Spacer(GlanceModifier.width(10.dp))
        Stat(LABEL_TOTAL, count(data.totalCount), valueSize = 20.sp, modifier = GlanceModifier.defaultWeight())
        VDivider()
        Spacer(GlanceModifier.width(10.dp))
        Stat(
            LABEL_CONFIRM,
            confirmRateText(data.confirmedCount, data.totalCount),
            valueSize = 20.sp,
            modifier = GlanceModifier.defaultWeight(),
        )
    }
}

/**
 * 空态：今天还没跳过。
 *
 * 副行给累计而不是确认率：今天 0 次时确认率没有当日样本，累计次数才是「这组件其实在干活」的证据。
 */
@Composable
private fun AdEmpty(data: AdWidgetData) {
    Text(text = EMPTY_TODAY, style = titleStyle(WIDGET_MUTED), maxLines = 1)
    Spacer(GlanceModifier.height(4.dp))
    Text(text = TOTAL_PREFIX + count(data.totalCount), style = labelStyle(), maxLines = 1)
}
