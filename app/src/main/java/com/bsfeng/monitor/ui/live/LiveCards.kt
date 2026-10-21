package com.bsfeng.monitor.ui.live

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AirplanemodeActive
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.NavigateNext
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.ui.components.AppIcon
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.HeatBar
import com.bsfeng.monitor.ui.components.Sparkline
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.eventColor
import com.bsfeng.monitor.ui.theme.heatHeight
import com.bsfeng.monitor.ui.theme.monitorMonoMeta
import com.bsfeng.monitor.ui.theme.sparkHeight

/**
 * s01 / s02 / s03 共用的行级与面级组合件（W1 私有，落在 ui/live/）。
 *
 * 只用 W0 冻结接口：token（LocalDesignTokens）、基元（AppCard / Badge / Sparkline / Chip 等）。
 * 本文件不含任何色值、字号、间距字面量——尺寸一律取自 token。
 */

// ───────────────────────── 尺寸常量（原型字面尺寸，非 token 槽位） ─────────────────────────

/** 时间列宽度 46dp：原型 .tl-item 的 `grid-template-columns:46px`（00-shell.html:379）。 */
private val TL_TIME_COL_W = 46.dp

/** 无数据热力空轨（原型空态无热力条）：2dp 浅色圆角轨，替代 24 格等高满柱。 */
private val EMPTY_HEAT_TRACK_H = 2.dp
private val EMPTY_HEAT_TRACK_RADIUS = 2.dp

// ───────────────────────── 类型图标 / 文案 ─────────────────────────

fun recordTypeIcon(type: RecordType): ImageVector = when (type) {
    RecordType.SCREEN -> Icons.Outlined.Smartphone
    RecordType.APP_USAGE -> Icons.Outlined.Apps
    RecordType.CHARGING -> Icons.Outlined.Bolt
    RecordType.BATTERY -> Icons.Outlined.BatteryStd
    RecordType.WIFI -> Icons.Outlined.Wifi
    RecordType.AUDIO -> Icons.Outlined.MusicNote
    RecordType.PACKAGE -> Icons.Outlined.Build
    RecordType.POWER_SAVE -> Icons.Outlined.BatteryStd
    RecordType.AIRPLANE -> Icons.Outlined.AirplanemodeActive
    RecordType.PHONE_STATE -> Icons.Outlined.Phone
    RecordType.LOCATION -> Icons.Outlined.LocationOn
}

fun recordTypeLabel(type: RecordType): String = when (type) {
    RecordType.SCREEN -> "屏幕"
    RecordType.APP_USAGE -> "前台使用"
    RecordType.CHARGING -> "充电"
    RecordType.BATTERY -> "电量"
    RecordType.WIFI -> "网络"
    RecordType.AUDIO -> "音频"
    RecordType.PACKAGE -> "应用"
    RecordType.POWER_SAVE -> "省电"
    RecordType.AIRPLANE -> "飞行模式"
    RecordType.PHONE_STATE -> "通话"
    RecordType.LOCATION -> "定位"
}

/** 采集来源短名（对齐原型行副标题的「· 无障碍服务 / 广播接收」）。 */
fun recordSourceLabel(type: RecordType): String = when (type) {
    RecordType.APP_USAGE -> "无障碍服务"
    RecordType.AUDIO -> "音频焦点"
    RecordType.LOCATION -> "位置服务"
    else -> "广播接收"
}

fun recordSubtitle(record: Record): String =
    record.type.let { recordTypeLabel(it) } + "事件 · " + recordSourceLabel(record.type)

// ───────────────────────── 页头 / 图标按钮 ─────────────────────────

@Composable
fun MetaText(text: String, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Text(text, modifier = modifier, style = MaterialTheme.typography.labelMedium, color = t.meta)
}

/**
 * 页面大标题：副标题 meta 在上、标题在下，右侧可选动作按钮。
 *
 * [consumeTopInset] 默认 false：页头在 Scaffold 内容区里，状态栏避让已由 Scaffold 的 inset 完成。
 * true 时页头自己加 `statusBarsPadding()`，供 Scaffold 之外的整屏页（覆盖层）使用。
 */
@Composable
fun ScreenHead(
    title: String,
    meta: String,
    modifier: Modifier = Modifier,
    actionIcon: ImageVector? = null,
    actionDesc: String = "",
    onAction: (() -> Unit)? = null,
    consumeTopInset: Boolean = false,
) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (consumeTopInset) Modifier.statusBarsPadding() else Modifier)
            .padding(start = t.space4, end = t.space4, top = t.space4, bottom = t.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            MetaText(meta)
            Spacer(Modifier.height(t.space2))
            Text(title, style = MaterialTheme.typography.headlineMedium, color = t.fg)
        }
        if (actionIcon != null && onAction != null) {
            Spacer(Modifier.width(t.space3))
            HeadIconButton(icon = actionIcon, contentDescription = actionDesc, onClick = onAction)
        }
    }
}

@Composable
fun HeadIconButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    Box(
        modifier = modifier
            .size(t.iconSize)
            .clip(CircleShape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, CircleShape)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            contentDescription = contentDescription,
            tint = t.fg2,
            modifier = Modifier.size(t.iconSize / 2),
        )
    }
}

// ───────────────────────── 行 / 卡 ─────────────────────────

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
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier),
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = t.rowMinH)
            .clickable { onClick() }
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
            modifier = Modifier.width(TL_TIME_COL_W),
        )
        Spacer(Modifier.width(t.space1))
        // 应用事件（APP_USAGE 且带 package_name）优先显示应用图标；无包名的类型行默认走 TypeIconDot，
        // 这是有意为之（不是漏做）：AUDIO 记录的 detail 只有 device（SCREEN / WIFI / 充电中 … 同理），
        // 没有包名 / 应用名，取不到应用图标。
        val pkg = if (record.type == RecordType.APP_USAGE) {
            record.detail?.get("package_name")?.takeIf { it.isNotBlank() }
        } else {
            null
        }
        if (pkg != null) {
            AppIcon(packageName = pkg, appName = record.label)
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

/**
 * 活跃强度热力条（s01 / s02 共用）：有数据出 24 格热力条，无数据出 2dp 浅色空轨。
 *
 * 0 条时 HeatBar 的 max 兜底为 1f → 24 格同为 level 0，与「有数据的浅格」无法区分；
 * 原型空态本就没有热力条（s01/s02 空态），所以这里统一兜底，避免 s01 漏兜（W3 只兜了 s02）。
 */
@Composable
fun HeatStrip(values: List<Float>, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    if (values.any { it > 0f }) {
        HeatBar(
            values = values,
            modifier = modifier
                .fillMaxWidth()
                .height(t.heatHeight),
        )
    } else {
        Box(
            modifier
                .fillMaxWidth()
                .height(EMPTY_HEAT_TRACK_H)
                .background(t.borderSoft, RoundedCornerShape(EMPTY_HEAT_TRACK_RADIUS)),
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

/** 小统计块（s03 的「平均耗时 / 规则命中 / 累计节省」）。 */
@Composable
fun StatBox(label: String, value: String, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusMd)
    Column(
        modifier = modifier
            .clip(shape)
            .background(t.surfaceWarm)
            .border(1.dp, t.borderSoft, shape)
            .padding(t.space2),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = t.fg2)
        Spacer(Modifier.height(t.space1))
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            color = t.fg,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** KPI 卡（s01）：数值位由调用方给 composable，以便挂 AnimatedNumber。 */
@Composable
fun LiveKpiCard(
    label: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    delta: String? = null,
    deltaUp: Boolean? = null,
    spark: List<Float>? = null,
    onClick: (() -> Unit)? = null,
    value: @Composable () -> Unit,
) {
    val t = LocalDesignTokens.current
    val shape = RoundedCornerShape(t.radiusLg)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(shape)
            .background(t.surface)
            .border(1.dp, t.borderSoft, shape)
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(t.space3),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = t.meta)
        Spacer(Modifier.height(t.space2))
        Row(verticalAlignment = Alignment.Bottom) {
            value()
            if (unit != null) {
                Spacer(Modifier.width(t.space1))
                Text(unit, style = MaterialTheme.typography.labelMedium, color = t.muted)
            }
        }
        Spacer(Modifier.height(t.space1))
        Row(verticalAlignment = Alignment.Bottom) {
            if (delta != null) {
                val c = when (deltaUp) {
                    true -> t.successText
                    false -> t.dangerText
                    null -> t.muted
                }
                Text(
                    text = delta,
                    style = MaterialTheme.typography.labelSmall,
                    color = c,
                    modifier = Modifier.weight(1f),
                )
            } else {
                Spacer(Modifier.weight(1f))
            }
            if (spark != null && spark.size >= 2) {
                Spacer(Modifier.width(t.space2))
                Sparkline(
                    values = spark,
                    modifier = Modifier
                        .width(t.space12 + t.space6)
                        .height(t.sparkHeight),
                )
            }
        }
    }
}

/** 卡片内的小标题行（左标题 + 右 meta）。 */
@Composable
fun CardHead(title: String, meta: String, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = t.fg)
        MetaText(meta)
    }
}
