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
import androidx.compose.foundation.layout.widthIn
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
import androidx.compose.ui.semantics.semantics
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

// 尺寸常量（原型字面尺寸，非 token 槽位）：时间列的**最小**宽度 46dp，
// 原型 .tl-item 的 grid-template-columns:46px（00-shell.html:379）。实际宽度由文本决定：
// 固定 46dp 在大字体（font_scale 1.3）下装不下等宽的「01:50」，末位被裁掉后用户会读到错误时间
// （同 ui/LiveEventTimeline.kt 的处理）。正常字号下文本自然宽 < 46dp，列宽仍是 46dp，与原设计一致。
private val TL_TIME_COL_W = 46.dp

// s01 概览的「最近事件」行是**单行**（原型 s01-今日概览:317 的 .s01-ev：时间 + 图标 + 一行
// 「微信 · 前台 12 分钟」+ 箭头，行高 52、左右无内边距）。时间列原型自然宽 42px，箭头 18px。
private val S01_TIME_COL_W = 42.dp
private val S01_CHEV_SIZE = 18.dp

// 类型图标底座：原型 .row-icon 是 36×36（20-base.html:230），不是 space-8 档位。
private val TYPE_DOT_SIZE = 36.dp

/** 类型图标底座（替代原型 .row-icon）。 */
@Composable
fun TypeIconDot(type: RecordType, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val c = t.eventColor(type)
    Box(
        modifier = modifier
            .size(TYPE_DOT_SIZE)
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
    // 锚点 s01-recent-1..3 / s02 行：按下整行 0.99 + alpha .9（--dur-press），
    // interaction 与 clickable 共用同一实例，否则收不到 Press 事件。
    val interaction = remember { MutableInteractionSource() }
    val shape = RoundedCornerShape(t.radiusLg)
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = t.rowMinH)
            // 整行是一个按钮：把时间、图标、标题合并成一条可读语义（原型 s01:317 就是 <button>）
            .semantics(mergeDescendants = true) {}
            .rowPressFeedback(interaction)
            // 键盘 / 遥控器聚焦时出 2dp 焦点环（触屏点按不出，见 FocusRing.kt）：此前只有 ripple 状态层
            .keyboardFocusRing(shape)
            .clickable(interactionSource = interaction, indication = LocalIndication.current) { onClick() }
            .padding(vertical = t.space2),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // s01 行没有横向内边距（原型 .s01-ev 的 padding 是 8px 0）：缩进由外层卡片的 --space-4 提供。
        // 时间列原型自然宽 42px；宽度不足时不换行，兜底省略号，避免把截断的时间当真实值读。
        Text(
            text = if (showSeconds) formatClockSec(record.timestamp) else formatClock(record.timestamp),
            style = monitorMonoMeta,
            color = t.muted,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(min = S01_TIME_COL_W).testTag("row.event"),
        )
        Spacer(Modifier.width(t.space3))
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
        // 单行标题（原型 s01:320「微信 · 前台 12 分钟」）；副行信息只在 s02 的完整行里出现
        Text(
            text = recordTitle(record),
            style = MaterialTheme.typography.bodyMedium,
            color = t.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(t.space3))
        Icon(
            Icons.Outlined.NavigateNext,
            contentDescription = null,
            tint = t.meta,
            modifier = Modifier.size(S01_CHEV_SIZE),
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
