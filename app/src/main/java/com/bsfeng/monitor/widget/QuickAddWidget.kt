package com.bsfeng.monitor.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.LocalContext
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.layout.Spacer
import androidx.glance.layout.height
import androidx.glance.text.Text
import com.bsfeng.monitor.MainActivity

/**
 * 快捷记一笔组件（1×1 纯入口）：整块主色圆角块 + 白色「+」+「记一笔」，点哪都进新增页。
 *
 * 这里刻意不调用 WidgetDataProvider 读数据：纯入口组件不显示任何统计，
 * 为了一行固定文案去开一次 SQLite 只会拖慢首帧。
 * 点击走 MainActivity.addTransactionIntent：直达记账 Tab 的「记一笔」表单，而不是只停在首页。
 */
class QuickAddWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { GlanceTheme { QuickAddTile() } }
    }
}

class QuickAddWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuickAddWidget()
}

@Composable
private fun QuickAddTile() {
    val context = LocalContext.current
    // 主色块在深浅色下同色：白字对 #007AFF 的对比度已经够，不跟随底色改动。
    SolidTile(action = actionStartActivity(MainActivity.addTransactionIntent(context))) {
        Text(text = "+", style = numberStyle(WIDGET_ON_HUE, 26.sp), maxLines = 1)
        Spacer(GlanceModifier.height(2.dp))
        Text(text = "记一笔", style = labelStyle(WIDGET_ON_HUE), maxLines = 1)
    }
}
