package com.bsfeng.monitor.ui

// 从 ui/MainScreen.kt 拆出：实时页的事件详情抽屉 —— 标题 + 类型徽标 + 口径字段行 + 关闭/复制 JSON。
// 锚点：s02-drawer-page / s02-drawer-notification。遮罩由 BottomSheet 的窗口级全屏浮层承担。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.BottomSheet
import com.bsfeng.monitor.ui.components.ToastHostState
import com.bsfeng.monitor.ui.live.DetailRow
import com.bsfeng.monitor.ui.live.PLACEHOLDER
import com.bsfeng.monitor.ui.live.detailOr
import com.bsfeng.monitor.ui.live.formatClockSec
import com.bsfeng.monitor.ui.live.formatDurationMs
import com.bsfeng.monitor.ui.live.recordSubtitle
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import org.json.JSONObject

private const val TOAST_COPIED = "已复制事件 JSON"

/** 通知事件的敏感字段：原文永不展示、也不进剪贴板，只给长度。 */
private val NOTIFICATION_PRIVATE_KEYS = setOf(
    "notification_title", "notification_text", "title", "text", "content", "body", "message",
)

private fun Modifier.drawerAnchor(isNotification: Boolean): Modifier =
    if (isNotification) testTag("s02-drawer-notification") else testTag("s02-drawer-page")

/** 抽屉标题：页面轨迹 =「应用 › 页面标题」；通知 =「通知摘要」。 */
private fun drawerTitleOf(record: Record): String = when {
    record.isPageEvent() -> {
        val app = record.detail?.get("app_name")?.takeIf { it.isNotBlank() } ?: record.label
        val page = record.detail?.get("page_title")?.takeIf { it.isNotBlank() }
        if (page != null) app + " › " + page else app.ifBlank { PLACEHOLDER }
    }
    else -> record.label.ifBlank { PLACEHOLDER }
}

private fun detailOrDash(record: Record, key: String): String = record.detailOr(key)

/** 「标题·正文长度」：任一缺失整行按「—」口径；两段都有才拼「24 字 / 86 字」。 */
private fun notificationLength(record: Record): String {
    val titleLen = detailOrDash(record, "title_length")
    val textLen = detailOrDash(record, "text_length")
    return if (titleLen == PLACEHOLDER && textLen == PLACEHOLDER) PLACEHOLDER
    else titleLen + " 字 / " + textLen + " 字"
}

/** 事件详情抽屉。 */
@Composable
fun EventDetailSheet(
    record: Record,
    toast: ToastHostState,
    onDismiss: () -> Unit,
) {
    val t = LocalDesignTokens.current
    val clipboard = LocalClipboardManager.current
    val isNotification = record.isNotificationEvent()
    Box(Modifier.fillMaxSize()) {
        BottomSheet(
            visible = true,
            onDismiss = onDismiss,
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .drawerAnchor(isNotification),
            ) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = drawerTitleOf(record),
                        style = MaterialTheme.typography.titleSmall,
                        color = t.fg,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Badge(if (isNotification) "NOTIFICATION" else "PAGE")
                }
                Spacer(Modifier.height(t.space3))
                if (isNotification) {
                    DetailRow("发生时间", formatClockSec(record.timestamp), first = true)
                    DetailRow("渠道", detailOrDash(record, "channel"))
                    DetailRow("分类", detailOrDash(record, "category"))
                    DetailRow("标题·正文长度", notificationLength(record))
                    DetailRow("常驻通知", detailOrDash(record, "ongoing"))
                    DetailRow("来源", detailOrDash(record, "source").let {
                        if (it == PLACEHOLDER) recordSubtitle(record) else it
                    })
                } else {
                    DetailRow("发生时间", formatClockSec(record.timestamp), first = true)
                    DetailRow("页面标题", detailOrDash(record, "page_title"))
                    DetailRow("页面指纹", detailOrDash(record, "page_fingerprint"))
                    DetailRow("持续时长", formatDurationMs(record.duration))
                    DetailRow(
                        "页内点击·滑动",
                        detailOrDash(record, "clicks") + " / " + detailOrDash(record, "swipes"),
                    )
                    DetailRow("来源", recordSubtitle(record))
                }
                Spacer(Modifier.height(t.space4))
                Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
                    LiveActionButton(
                        label = "关闭",
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                    )
                    LiveActionButton(
                        label = "复制 JSON",
                        onClick = {
                            clipboard.setText(AnnotatedString(recordJson(record, redact = isNotification)))
                            toast.show(TOAST_COPIED)
                        },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** 事件 JSON：通知事件剥掉标题与正文原文，长文本字段复制内容保持完整。 */
private fun recordJson(record: Record, redact: Boolean): String {
    val obj = JSONObject()
    obj.put("id", record.id)
    obj.put("type", record.type.toString())
    obj.put("action", record.action)
    obj.put("timestamp", record.timestamp)
    if (record.duration != null) obj.put("durationMs", record.duration) else obj.put("durationMs", JSONObject.NULL)
    obj.put("label", record.label)
    obj.put("owner", record.owner)
    val detail = JSONObject()
    record.detail.orEmpty().toSortedMap().forEach { (key, value) ->
        if (!redact || key !in NOTIFICATION_PRIVATE_KEYS) detail.put(key, value)
    }
    obj.put("detail", detail)
    return obj.toString(2)
}
