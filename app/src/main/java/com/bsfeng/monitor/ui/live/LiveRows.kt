package com.bsfeng.monitor.ui.live

// 从 ui/live/LiveCards.kt 拆出：列表行 —— TypeIconDot（类型圆点）、StackRow（多行堆叠行）、LiveEventRow（实时事件行）、HourGroupHead（按小时分组头）、DetailRow（键值行）。负责「一条一条往下排」。

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.NavigateNext
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.ui.components.AppIcon
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.keyboardFocusRing
import com.bsfeng.monitor.ui.components.rowPressFeedback
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.eventColor
import com.bsfeng.monitor.ui.theme.monitorMonoMeta

// 尺寸常量（原型字面尺寸，非 token 槽位）：时间列宽度 46dp，原型 .tl-item 的 grid-template-columns:46px（00-shell.html:379）。
private val TL_TIME_COL_W = 46.dp

/** 类型图标底座（替代原型 .row-icon）。 */
@Composable
fun TypeIconDot(type: RecordType, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val c = t.eventColor(type)
    Box(
        modifier = modifier
            .size(t.space8)
            .clip(RoundedCornerShape(t.radiusSm))
            .background(c.copy(alpha = 0.14f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            recordTypeIcon(type),
            contentDescription = recordTypeLabel(type),
            tint = c,
            modifier = Modifier.size(t.iconSize / 2),
        )
    }
}

/** 堆叠卡片里的一行：首行圆上角、末行圆下角，避免逐行卡片割裂。 */
@Composable
fun StackRow(
    first: Boolean,
    last: Boolean,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(
        topStart = if (first) t.radiusLg else 0.dp,
        topEnd = if (first) t.radiusLg else 0.dp,
        bottomStart = if (last) t.radiusLg else 0.dp,
        bottomEnd = if (last) t.radiusLg else 0.dp,
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, shape)
            .then(if (onClick != null) Modifier.testTag("stack.row").clickable { onClick() } else Modifier),
    ) {
        content()
    }
}

/**
 * 事件行（s01 最近事件 / s02 时间线共用，全应用唯一的行实现）。
 *
 * 图标按原型 s02 的规律分流：**行标题里应用名在前 = 应用事件 → 显示应用自身的图标**
 * （「微信 · 前台 12 分钟」用 .app-icon，s01:2437）；系统事件（屏幕点亮 / Wi-Fi 连接 /
 * 充电中 …）保持类型图标（.row-icon，s01:2443/2449）。旧记录缺 package_name 时
 * 由 [AppIcon] 内部回落「品牌色底 + 应用首字」。
 */
@Composable
fun LiveEventRow(
    record: Record,
    modifier: Modifier = Modifier,
    showSeconds: Boolean = true,
    onClick: () -> Unit,
) {
    val t = LocalDesignTokens.current
    // 契约 s01-recent-1..3 / s02 行：按下整行 0.99 + alpha .9（--dur-press），
    // interaction 与 clickable 共用同一实例，否则收不到 Press 事件。
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusLg)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = t.rowMinH)
            .rowPressFeedback(interaction)
            // 键盘 / 遥控器聚焦时出 2dp 焦点环（触屏点按不出，见 FocusRing.kt）：此前只有 ripple 状态层
            .keyboardFocusRing(shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onClick() }
            .padding(horizontal = t.space4, vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // 46dp：原型 .tl-item 时间列（00-shell.html:379 grid-template-columns:46px）；
        // .tl-time = --font-mono --text-sm → monitorMonoMeta。宽度不足时不得换行
        // （48dp + 14sp 下 "20:16:12" 会折成两行，见 14-after/s02-live.png）。
        Text(
            text = if (showSeconds) formatClockSec(record.timestamp) else formatClock(record.timestamp),
            style = monitorMonoMeta,
            color = t.muted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
            modifier = Modifier.width(TL_TIME_COL_W).testTag("row.event"),
        )
        Spacer(Modifier.width(t.space1))
        // 凡是 detail 里带 package_name 的记录都显示应用图标（APP_USAGE / PAGE 页面轨迹 /
        // SCREEN.AD_SKIPPED 跳过广告 / PACKAGE 安装卸载）；系统事件（屏幕 / WiFi / 电量 / 充电 /
        // 音频）的 detail 没有包名，继续走类型图标，这是数据本身的边界而不是漏做。
        val pkg = record.detail?.get("package_name")?.takeIf { it.isNotBlank() }
        val iconAppName = record.detail?.get("app_name")?.takeIf { it.isNotBlank() } ?: record.label
        if (pkg != null) {
            AppIcon(packageName = pkg, appName = iconAppName)
        } else {
            TypeIconDot(record.type)
        }
        Spacer(Modifier.width(t.space3))
        Column(Modifier.weight(1f)) {
            Text(
                text = record.label.ifBlank { PLACEHOLDER },
                style = MaterialTheme.typography.bodyMedium,
                color = t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = recordSubtitle(record),
                style = MaterialTheme.typography.labelSmall,
                color = t.muted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(t.space2))
        Icon(
            Icons.Outlined.NavigateNext,
            contentDescription = null,
            tint = t.meta,
            modifier = Modifier.size(t.space5),
        )
    }
}

/** 小时分组头（s02 吸顶）。 */
@Composable
fun HourGroupHead(
    label: String,
    count: Int,
    modifier: Modifier = Modifier,
    showCount: Boolean = true,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(topStart = t.radiusLg, topEnd = t.radiusLg)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, shape)
            .padding(horizontal = t.space4, vertical = t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = t.fg,
            modifier = Modifier.weight(1f),
        )
        if (showCount) Badge(count.toString() + " 条事件")
    }
}

/** 详情抽屉里的字段行。 */
@Composable
fun DetailRow(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    first: Boolean = false,
) {
    val t = LocalDesignTokens.current
    Column(modifier.fillMaxWidth()) {
        if (!first) HorizontalDivider(color = t.borderSoft)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = t.space3),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = t.fg2,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(t.space3))
            Text(
                text = value,
                style = MaterialTheme.typography.labelMedium,
                color = t.fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}
