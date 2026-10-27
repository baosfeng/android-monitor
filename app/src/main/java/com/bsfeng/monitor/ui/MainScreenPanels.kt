package com.bsfeng.monitor.ui

// 从 ui/MainScreen.kt 拆出：实时事件流的「今日活跃强度」热力区块。
// 只接收值与回调，状态仍由 MainScreen 持有。锚点：s02-heatwrap。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.components.HeatBar
import com.bsfeng.monitor.ui.live.MetaText
import com.bsfeng.monitor.ui.live.peakOf
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.heatHeight
import java.util.Locale

private val EMPTY_TRACK_HEIGHT = 2.dp
private val EMPTY_TRACK_RADIUS = 2.dp
private const val EMPTY_HEAT_TEXT = "暂无数据"

/**
 * 今日活跃强度：标题行（含峰值文案）+ 24 格热力条。热力不可点选、无 tooltip、不可拖选。
 *
 * 无数据时改画 2dp 浅色空轨：HeatBar 在 values 全 0 时 max 兜底为 1f → level=0 → 会画出
 * chart-1 最深的满高柱（设备实证的 P0 假象）。
 */
@Composable
internal fun LiveIntensityPanel(hourlyIntensity: List<Float>, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    val hasIntensity = hourlyIntensity.any { it > 0f }
    val peak = peakOf(hourlyIntensity)
    val peakText = if (hasIntensity) {
        "峰值 " + String.format(Locale.US, "%02d:00", peak.first) + " · " + peak.second.toInt() + " 条"
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
