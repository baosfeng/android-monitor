package com.bsfeng.monitor.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
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
import androidx.glance.unit.ColorProvider
import com.bsfeng.monitor.MainActivity
import com.bsfeng.monitor.R
import com.bsfeng.monitor.service.MonitorAccessibilityService

/**
 * 监控健康组件（服务状态 + 今日摘要）。
 *
 * Tiny 只有图标 + 状态词；Compact 是「顶行状态 + 两列读数（今日事件 / 跳过）」，
 * 两列各自 1fr 均分、中间 1dp 竖线，右半不再留空。整块可点，直达「实时」Tab。
 *
 * 数据在 [provideGlance] 里先读、再进 composition：`provideContent` 的 lambda 会在任意线程被重新组合，
 * 里面出现 IO 会直接体现在首帧耗时上。视觉 token 统一走 [FACE_HEALTH] 与 [labelStyle]。
 */
class HealthWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetDataProvider.health(context)
        val box = widgetBox(context, id)
        provideContent { HealthCard(data, box) }
    }
}

class HealthWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = HealthWidget()
}

/** 组件的三种状态：绿点在干活、橙点心跳停更、红点没在跑。 */
private enum class HealthState { RUNNING, STALE, STOPPED }

/**
 * 三态判定。
 *
 * [HealthWidgetData.serviceRunning] 已把「心跳停更」并入 false（见 [WidgetDataProvider.health]），
 * 单看它分不出「服务没开」和「进程被冻结」。这里补读一下服务静态标志：它只是同进程的一个
 * Boolean（不连服务、不拉起引擎），服务连着却不再心跳 = 停更，两条都 false 才是真的没运行。
 */
private fun healthStateOf(data: HealthWidgetData): HealthState = when {
    data.serviceRunning -> HealthState.RUNNING
    MonitorAccessibilityService.isRunning -> HealthState.STALE
    else -> HealthState.STOPPED
}

@Composable
private fun HealthCard(data: HealthWidgetData, box: WidgetBox) {
    val state = healthStateOf(data)
    val tier = if (box.isUsable) tierOf(box.widthDp, box.heightDp) else WidgetTier.COMPACT
    val context = LocalContext.current
    WidgetCard(
        face = FACE_HEALTH,
        action = openLiveAction(context),
        verticalAlignment = tierAlignment(tier),
        padding = if (tier == WidgetTier.TINY) 12.dp else 16.dp,
    ) {
        when (tier) {
            WidgetTier.TINY -> {
                // 1×1：只留图标 + 状态词（80dp 宽的卡塞不下第二行读数）
                IconBadge(face = FACE_HEALTH, symbol = "♥", size = 18.dp)
                Spacer(GlanceModifier.height(4.dp))
                Row(verticalAlignment = Alignment.Vertical.CenterVertically) {
                    StatusDot(dotColor(state))
                    Spacer(GlanceModifier.width(5.dp))
                    Text(text = titleOf(state), style = labelStyle(), maxLines = 1)
                }
            }
            WidgetTier.COMPACT -> {
                Row(
                    modifier = GlanceModifier.fillMaxWidth(),
                    verticalAlignment = Alignment.Vertical.CenterVertically,
                ) {
                    IconBadge(face = FACE_HEALTH, symbol = "♥", size = 22.dp)
                    Spacer(GlanceModifier.width(8.dp))
                    StatusDot(dotColor(state))
                    Spacer(GlanceModifier.width(5.dp))
                    Text(text = titleOf(state), style = valueStyle(), maxLines = 1)
                }
                Spacer(GlanceModifier.height(8.dp))
                Row(modifier = GlanceModifier.fillMaxWidth()) {
                    Stat(
                        label = "今日事件",
                        value = count(data.todayEvents),
                        valueSize = 22.sp,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                    VDivider()
                    Spacer(GlanceModifier.width(10.dp))
                    Stat(
                        label = "跳过",
                        value = count(data.todaySkips),
                        valueSize = 22.sp,
                        modifier = GlanceModifier.defaultWeight(),
                    )
                }
            }
            WidgetTier.WIDE -> Unit
        }
    }
}

private fun dotColor(state: HealthState): ColorProvider = when (state) {
    HealthState.RUNNING -> WIDGET_INCOME
    HealthState.STALE -> WIDGET_WARN
    HealthState.STOPPED -> WIDGET_EXPENSE
}

private fun titleOf(state: HealthState): String = when (state) {
    HealthState.RUNNING -> "监控中"
    HealthState.STALE -> "心跳停更"
    HealthState.STOPPED -> "服务未运行"
}

/** 整块可点：直达「实时」Tab（下标见 [MainActivity.LIVE_TAB]）。 */
private fun openLiveAction(context: Context) = actionStartActivity(
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_START_TAB, MainActivity.LIVE_TAB)
    },
)
