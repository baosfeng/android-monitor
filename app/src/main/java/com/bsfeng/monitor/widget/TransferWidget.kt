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
import com.bsfeng.monitor.MainActivity
import com.bsfeng.monitor.R

/**
 * 传送状态组件（接收状态 + 在线设备数）。
 *
 * Compact 是「顶行状态 + 底行大数字（在线 N 台）」，数字右对齐压到底，
 * 不再让两个信息对角分布、中间留一片空。整块可点，直达「传送」Tab。
 * 数据是纯内存态（WidgetDataProvider.transfer），拿不到引擎就按「未运行」画灰色文案 ——
 * 组件不为了显示状态去启动链路。
 */
class TransferWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = WidgetDataProvider.transfer(context)
        val box = widgetBox(context, id)
        provideContent { TransferCard(data, box) }
    }
}

class TransferWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = TransferWidget()
}

@Composable
private fun TransferCard(data: TransferWidgetData, box: WidgetBox) {
    val tier = if (box.isUsable) tierOf(box.widthDp, box.heightDp) else WidgetTier.COMPACT
    val context = LocalContext.current
    val received = data.running && data.receiving
    WidgetCard(
        face = FACE_TRANSFER,
        action = openTransferAction(context),
        verticalAlignment = tierAlignment(tier),
        padding = if (tier == WidgetTier.TINY) 12.dp else 16.dp,
    ) {
        Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.CenterVertically) {
            IconBadge(face = FACE_TRANSFER, symbol = "↕", size = 22.dp)
            Spacer(GlanceModifier.width(8.dp))
            StatusDot(if (received) WIDGET_INCOME else WIDGET_MUTED)
            Spacer(GlanceModifier.width(5.dp))
            Text(text = titleOf(data), style = valueStyle(), maxLines = 1)
        }
        if (tier != WidgetTier.TINY) {
            // 顶行状态与底行台数之间用 weight 撑开：2×1 的高度富余 ~30dp，不撑开会在中间飘一行
            Spacer(GlanceModifier.defaultWeight())
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.Vertical.Bottom) {
                Text(text = "在线", style = labelStyle(), maxLines = 1)
                Spacer(GlanceModifier.defaultWeight())
                Text(text = count(data.onlineDevices), style = numberStyle(size = 22.sp), maxLines = 1)
                Spacer(GlanceModifier.width(4.dp))
                Text(text = "台", style = labelStyle(), maxLines = 1)
            }
        }
    }
}

/**
 * 「接收中」只在端口常驻且可被发现时成立：只 running 不对端也搜不到本机，
 * 这时文案用「已停止接收」而不是「未运行」，别把半开状态说成关机。
 */
private fun titleOf(data: TransferWidgetData): String = when {
    !data.running -> "未运行"
    data.receiving -> "接收中"
    else -> "已停止接收"
}

/** 整块可点：直达「传送」Tab（下标见 [MainActivity.TRANSFER_TAB]）。 */
private fun openTransferAction(context: Context) = actionStartActivity(
    Intent(context, MainActivity::class.java).apply {
        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        putExtra(MainActivity.EXTRA_START_TAB, MainActivity.TRANSFER_TAB)
    },
)
