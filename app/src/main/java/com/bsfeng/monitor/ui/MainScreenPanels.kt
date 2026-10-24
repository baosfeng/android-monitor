package com.bsfeng.monitor.ui

// 从 ui/MainScreen.kt 拆出：实时事件流的「今日活跃强度」热力条区块与单条事件详情面板内容。
// 只接收值与回调，状态仍由 MainScreen 持有。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.ui.components.Badge
import com.bsfeng.monitor.ui.components.HeatBar
import com.bsfeng.monitor.ui.live.DetailRow
import com.bsfeng.monitor.ui.live.detailOr
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.PLACEHOLDER
import com.bsfeng.monitor.ui.live.formatClockSec
import com.bsfeng.monitor.ui.live.peakOf
import com.bsfeng.monitor.ui.live.formatDurationMs
import com.bsfeng.monitor.ui.live.recordSubtitle
import com.bsfeng.monitor.ui.live.recordTypeLabel
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.heatHeight

private val EMPTY_TRACK_HEIGHT = 2.dp
private val EMPTY_TRACK_RADIUS = 2.dp
private const val EMPTY_HEAT_TEXT = "暂无数据"

/**
 * 今日活跃强度：标题行（含峰值文案）+ 24 格热力条。
 *
 * 无数据时改画 2dp 浅色空轨：HeatBar 在 values 全 0 时 max 兜底为 1f → level=0 → 会画出
 * chart-1 最深的满高柱（设备实证的 P0 假象）。
 */
@Composable
internal fun LiveIntensityPanel(hourlyIntensity: List<Float>, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    // 热力条与峰值文案同源：0 条记录时既不出满高深色柱，也不写「峰值 00:00 · 0 条」
    val hasIntensity = hourlyIntensity.any { it > 0f }
    val peak = peakOf(hourlyIntensity)
    val peakText = if (hasIntensity) {
        "峰值 " + String.format(java.util.Locale.US, "%02d:00", peak.first) + " · " + peak.second.toInt() + " 条"
    } else {
        EMPTY_HEAT_TEXT
    }
    Column(modifier.padding(horizontal = t.space4, vertical = t.space2)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            MetaText("今日活跃强度")
            MetaText(peakText)
        }
        Spacer(Modifier.height(t.space2))
        if (hasIntensity) {
            HeatBar(
                values = hourlyIntensity,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(t.heatHeight),
            )
        } else {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(EMPTY_TRACK_HEIGHT)
                    .background(t.borderSoft, RoundedCornerShape(EMPTY_TRACK_RADIUS)),
            )
        }
    }
}

/** 单条事件详情（放在 BottomSheet 里，遮罩由 BottomSheet 承担）。 */
@Composable
internal fun LiveEventDetailContent(
    record: Record,
    onClose: () -> Unit,
    onCopyJson: () -> Unit,
) {
    val t = LocalDesignTokens.current
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = record.label.ifBlank { PLACEHOLDER },
            style = MaterialTheme.typography.titleSmall,
            color = t.fg,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Badge(recordTypeLabel(record.type))
    }
    Spacer(Modifier.height(t.space3))
    DetailRow("发生时间", formatClockSec(record.timestamp), first = true)
    DetailRow("事件类型", recordTypeLabel(record.type) + " · " + record.action)
    DetailRow("持续时长", formatDurationMs(record.duration))
    DetailRow("包名", record.detailOr("package_name"))
    DetailRow("来源", recordSubtitle(record))
    Spacer(Modifier.height(t.space4))
    Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
        OutlinedButton(
            onClick = onClose,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(t.radiusPill),
        ) { Text("关闭", style = MaterialTheme.typography.labelLarge) }
        Button(
            onClick = onCopyJson,
            modifier = Modifier.weight(1f),
            shape = RoundedCornerShape(t.radiusPill),
        ) { Text("复制 JSON", style = MaterialTheme.typography.labelLarge) }
    }
}
