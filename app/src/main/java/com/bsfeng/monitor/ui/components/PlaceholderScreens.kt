package com.bsfeng.monitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.chartRamp
import com.bsfeng.monitor.ui.theme.heatHeight

/**
 * s01 今日概览的占位实现（骨架已可用，正式内容由 W1 接手）。
 *
 * s12 组件与五态展厅 / s13 外观已由 W1 交付为正式屏（ui/ComponentGalleryScreen.kt、ui/AppearanceScreen.kt），
 * 本文件末尾保留两个同名转发函数，只为了让 MainActivity 现有的 ui.components.* import 继续成立
 * （MainActivity 属导航任务的写 scope，W1 不改它）。导航任务接线后可直接删掉这两个转发。
 */

@Composable
private fun ScreenShell(
    title: String,
    subtitle: String,
    onBack: (() -> Unit)?,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val t = LocalDesignTokens.current
    Column(
        modifier
            .fillMaxSize()
            .background(t.bg)
            .padding(horizontal = t.space4),
    ) {
        Row(
            Modifier.fillMaxWidth().height(t.appbarH),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (onBack != null) {
                TextButton(onClick = onBack) {
                    Text("返回", style = MaterialTheme.typography.labelLarge, color = t.accent)
                }
                Spacer(Modifier.width(t.space1))
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleLarge, color = t.fg)
                Text(subtitle, style = MaterialTheme.typography.labelSmall, color = t.meta)
            }
        }
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(t.space3),
        ) {
            content()
            Spacer(Modifier.height(t.space6))
        }
    }
}

private val SPARK_A = listOf(96f, 142f, 88f, 210f, 176f, 240f, 334f)
private val SPARK_B = listOf(334f, 240f, 176f, 210f, 88f, 142f, 96f)
private val HOURS_24 = listOf(
    4f, 2f, 1f, 1f, 2f, 6f, 14f, 26f, 34f, 30f, 22f, 28f,
    36f, 30f, 24f, 32f, 44f, 52f, 46f, 38f, 30f, 22f, 14f, 7f,
)

/** s01 今日概览（占位，W1 接手正式内容）。 */
@Composable
fun OverviewScreen(
    onOpenGallery: () -> Unit,
    onOpenAppearance: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val t = LocalDesignTokens.current
    ScreenShell(
        title = "今日概览",
        subtitle = "占位屏 · s01 由 W1 实现",
        onBack = null,
        modifier = modifier,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
            KpiCard(
                label = "今日事件",
                value = "1,284",
                unit = "条",
                delta = "+12%",
                deltaUp = true,
                spark = SPARK_A,
                modifier = Modifier.weight(1f),
            )
            KpiCard(
                label = "屏幕时长",
                value = "6.4",
                unit = "h",
                delta = "-3%",
                deltaUp = false,
                spark = SPARK_B,
                modifier = Modifier.weight(1f),
            )
        }
        AppCard {
            Text("24 小时活跃度", style = MaterialTheme.typography.titleSmall, color = t.fg)
            Spacer(Modifier.height(t.space3))
            AreaChart(values = HOURS_24, modifier = Modifier.fillMaxWidth().height(t.chartH))
            ChartAxisLabels(listOf("00", "06", "12", "18", "23"))
        }
        AppCard {
            Text("事件类型分布", style = MaterialTheme.typography.titleSmall, color = t.fg)
            Spacer(Modifier.height(t.space3))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RingChart(
                    segments = listOf(38f, 24f, 16f, 12f, 10f),
                    modifier = Modifier.fillMaxWidth(),
                    centerLabel = "1,284",
                )
                Spacer(Modifier.width(t.space4))
                Legend(
                    items = listOf("屏幕", "应用", "充电", "网络", "其他").mapIndexed { i, label ->
                        LegendItem(label, listOf("38%", "24%", "16%", "12%", "10%")[i], t.chartRamp[i])
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        }
        AppCard {
            Text("每小时活跃强度", style = MaterialTheme.typography.titleSmall, color = t.fg)
            Spacer(Modifier.height(t.space3))
            HeatBar(values = HOURS_24, modifier = Modifier.fillMaxWidth().height(t.heatHeight))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(t.space3)) {
            Chip("组件展厅 s12", selected = false, onClick = onOpenGallery, modifier = Modifier.weight(1f))
            Chip("外观 s13", selected = false, onClick = onOpenAppearance, modifier = Modifier.weight(1f))
        }
        Text(
            "说明：s01 正式内容（真实数据接入、快捷入口）由 W1 补齐；本轮只交付设计地基与外壳。",
            style = MaterialTheme.typography.labelSmall,
            color = t.meta,
        )
    }
}

// ── s12 / s13 转发（真实屏在 ui/ 包；导航任务接线后可直接删除本段）──────────────

/** s12 组件与五态展示厅（转发到 com.bsfeng.monitor.ui.ComponentGalleryScreen）。 */
@Composable
fun ComponentGalleryScreen(onBack: () -> Unit, modifier: Modifier = Modifier) =
    com.bsfeng.monitor.ui.ComponentGalleryScreen(onBack = onBack, modifier = modifier)

/** s13 外观（转发到 com.bsfeng.monitor.ui.AppearanceScreen）。 */
@Composable
fun AppearanceScreen(onBack: () -> Unit, modifier: Modifier = Modifier) =
    com.bsfeng.monitor.ui.AppearanceScreen(onBack = onBack, modifier = modifier)
