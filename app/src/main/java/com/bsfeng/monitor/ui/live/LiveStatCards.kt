package com.bsfeng.monitor.ui.live

// 从 ui/live/LiveCards.kt 拆出：数据卡 —— HeatStrip（24h 热力条）、StatBox（单值小方块）、LiveKpiCard（KPI 大卡）、CardHead（卡头标题 + 元信息）。负责「一块一块的数据展示」。

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.HeatBar
import com.bsfeng.monitor.ui.components.Sparkline
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.heatHeight
import com.bsfeng.monitor.ui.theme.sparkHeight

// 无数据热力空轨（原型空态无热力条）：2dp 浅色圆角轨，替代 24 格等高满柱。
private val EMPTY_HEAT_TRACK_H = 2.dp
private val EMPTY_HEAT_TRACK_RADIUS = 2.dp

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
            .then(if (onClick != null) Modifier.testTag("kpi.card").clickable { onClick() } else Modifier)
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
