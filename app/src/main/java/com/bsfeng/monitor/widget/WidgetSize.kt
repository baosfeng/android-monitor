package com.bsfeng.monitor.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager

// 组件尺寸的唯一来源：系统 AppWidgetOptions。
//
// 为什么不用 Glance 的 LocalSize：实测（Pixel 4×2，minWidth=368dp）LocalSize 返回 110×110
// —— 它取的是 launcher 上报的最小值，与真实占格对不上，判档会把 4×2 判成 Tiny。
// AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH/HEIGHT 才是当前朝向下的实际占格。

/** 桌面上一个组件实例的占格（dp）。 */
internal data class WidgetBox(val widthDp: Float, val heightDp: Float) {

    /** 尺寸缺失（首次绑定、读 options 异常）时兜底：按 2×1 画，宁可少一行也不空白。 */
    val isUsable: Boolean get() = widthDp > 0f && heightDp > 0f
}

/** 读取组件当前占格；任何异常都退化成不可用（由调用方按 Compact 兜底）。 */
internal suspend fun widgetBox(context: Context, id: GlanceId): WidgetBox = runCatching {
    val manager = GlanceAppWidgetManager(context)
    val appWidgetId = manager.getAppWidgetId(id)
    val options = AppWidgetManager.getInstance(context).getAppWidgetOptions(appWidgetId)
    WidgetBox(
        widthDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).toFloat(),
        heightDp = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_HEIGHT).toFloat(),
    )
}.getOrDefault(WidgetBox(0f, 0f))
