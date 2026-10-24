package com.bsfeng.monitor.ui.components

// 从 ui/components/Charts.kt 拆出：图表外壳与轴标签 —— ChartSlot（固定高度占位）、ChartAxisLabels（x 轴文案行）。负责图表与卡片排版的衔接。

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 图表基元（原 ui/components/Charts.kt 的文件级约定，随拆分保留）：
 * 全部自绘 Canvas，零第三方图表/动画库（契约 §6）；绘制时长走 --dur-chart / --dur-chart-fast，
 * 只动几何与 opacity。
 *
 * 热路径（J4）：动画值 `Animatable` 只在 **绘制期** 读（drawWithCache 的 build 块在
 * observeReads 作用域内执行，状态失效只触发重绘、不触发重组）；Path / dashPathEffect 这类
 * 每帧重建的对象放进缓存块，仅 values 或 size 变化时重建。
 */

/** 图表占位高度辅助。 */
@Composable
fun ChartSlot(modifier: Modifier = Modifier, height: Dp? = null, content: @Composable () -> Unit) {
    val t = LocalDesignTokens.current
    Box(modifier.fillMaxWidth().height(height ?: t.chartH)) { content() }
}

/** 图表 x 轴标签行（11px token 字号，避免 Canvas 内文字度量）。 */
@Composable
fun ChartAxisLabels(labels: List<String>, modifier: Modifier = Modifier) {
    val t = LocalDesignTokens.current
    Row(
        modifier = modifier.fillMaxWidth().padding(top = t.space1),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        labels.forEach {
            Text(it, style = MaterialTheme.typography.labelSmall, color = t.muted)
        }
    }
}
