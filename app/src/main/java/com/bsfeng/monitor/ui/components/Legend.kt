package com.bsfeng.monitor.ui.components

// 从 ui/components/Controls.kt 拆出：图例与可点行 —— LegendItem 数据类、Legend（色点 + 名称 + 数值）、TapRow（整行可点）。负责「图表图例与列表行点击」。

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/** 图例数据：标签 + 右侧数值 + 色块。 */
data class LegendItem(val label: String, val value: String, val color: Color)

/** 图例列表（.legend：10px 圆角方块 + 标签 + 右侧等宽数值）。 */
@Composable
fun Legend(items: List<LegendItem>, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Column(modifier = modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(t.space2)) {
        items.forEach { item ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(RoundedCornerShape(3.dp))
                        .background(item.color)
                )
                Spacer(Modifier.width(t.space2))
                Text(
                    item.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = t.fg2,
                    modifier = Modifier.weight(1f),
                )
                Text(item.value, style = MaterialTheme.typography.labelMedium, color = t.muted)
            }
        }
    }
}

/** 触达尺寸保证的点击行封装。 */
@Composable
fun TapRow(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    minHeight: Dp? = null,
    content: @Composable () -> Unit,
) {
    val t = LocalDesignTokens.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(minHeight ?: t.rowMinH)
            .clickable { onClick() },
        contentAlignment = Alignment.CenterStart,
    ) { content() }
}
