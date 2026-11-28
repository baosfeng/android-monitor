package com.bsfeng.monitor.widget

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.ui.theme.WidgetPalette

// 额度组件的「同构内容层」——只在测试里用，给 QuotaWidgetShotTest 渲染成 PNG 并量高度 / 横条宽度。
//
// 为什么要有它：Glance 的 composition 只在真机 launcher 上出画面，单测渲染不了 RemoteViews；
// 但「4 格塞不塞得进 250×110」「横条长度是不是 = fraction」是纯排版问题，按同一份常量画一遍就能量。
//
// 同源约束（改 Glance 侧必须同步这里，反之亦然）：内边距 / 字号 / 行距 / 列距 / 行距 / 横条高与间距
// 全部来自 [QuotaWidgetScale]，卡片圆角来自 [CARD_RADIUS]，图标块圆角与字号比例分别照
// IconBadge 的 size/3 与 size*0.52，横条填充走 fillMaxWidth(fraction) —— 与 Glance 的
// LinearProgressIndicator(progress) 同义。判据是 QuotaWidgetShotTest 的溢出与比例断言。

/** 与 QuotaWidget.kt 的 QuotaCard 一一对应的镜像内容层（颜色取 WidgetPalette.quota 亮色档）。 */
@Composable
internal fun QuotaContentMirror(
    cells: List<QuotaWidgetCell>,
    columns: Int,
    showHeader: Boolean,
    headerText: String,
    scale: QuotaWidgetScale,
) {
    val palette = WidgetPalette.quota
    val ink = palette.iconDay
    val surface = palette.surfaceDay
    val track = palette.trackDay
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(surface, RoundedCornerShape(CARD_RADIUS))
            .padding(scale.padding.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        if (showHeader) {
            Row(
                modifier = Modifier.testTag("quota.shot.header"),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(
                    modifier = Modifier
                        .size(scale.headerBadge.dp)
                        // IconBadge 用 cornerRadius(size / 3)，这里同比例
                        .background(ink, RoundedCornerShape(scale.headerBadge / 3f))
                        .testTag("quota.shot.badge"),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = "%",
                        // IconBadge 的前景是 WIDGET_ON_HUE（= palette.onHue），字号 size * 0.52
                        color = WidgetPalette.onHue,
                        fontSize = (scale.headerBadge * 0.52f).sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
                Spacer(Modifier.width(scale.headerBadgeGap.dp))
                Text(
                    text = headerText,
                    modifier = Modifier.testTag("quota.shot.header-text"),
                    style = TextStyle(
                        color = ink,
                        fontSize = scale.headerSizeSp.sp,
                        fontWeight = FontWeight.Bold,
                    ),
                    maxLines = 1,
                )
            }
            Spacer(Modifier.height(scale.headerGap.dp))
        }
        val rows = cells.chunked(columns)
        rows.forEachIndexed { index, rowCells ->
            Row(modifier = Modifier.fillMaxWidth()) {
                rowCells.forEachIndexed { columnIndex, cell ->
                    if (columnIndex > 0) Spacer(Modifier.width(scale.columnGap.dp))
                    QuotaCellMirror(
                        cell = cell,
                        index = index * columns + columnIndex,
                        ink = ink,
                        track = track,
                        scale = scale,
                        modifier = Modifier.weight(1f),
                    )
                }
                repeat(columns - rowCells.size) { Spacer(Modifier.weight(1f)) }
            }
            if (index != rows.lastIndex) Spacer(Modifier.height(scale.rowGap.dp))
        }
    }
}

@Composable
private fun QuotaCellMirror(
    cell: QuotaWidgetCell,
    index: Int,
    ink: Color,
    track: Color,
    scale: QuotaWidgetScale,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.testTag("quota.shot.cell-" + index)) {
        Text(
            text = cell.valueText,
            modifier = Modifier.testTag("quota.shot.value-" + index),
            style = TextStyle(
                color = ink,
                fontSize = scale.valueSizeSp.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Monospace,
            ),
            maxLines = 1,
        )
        Spacer(Modifier.height(scale.captionGap.dp))
        Text(
            text = cell.captionText,
            modifier = Modifier.testTag("quota.shot.caption-" + index),
            style = TextStyle(
                color = WidgetPalette.mutedDay,
                fontSize = scale.captionSizeSp.sp,
                fontWeight = FontWeight.Normal,
            ),
            maxLines = 1,
        )
        val fraction = cell.fraction
        if (fraction != null) {
            Spacer(Modifier.height(scale.barGap.dp))
            Box(
                modifier = Modifier
                    .testTag("quota.shot.bar-" + index)
                    .height(scale.barHeight.dp)
                    .fillMaxWidth()
                    .background(track, RoundedCornerShape(999.dp)),
            ) {
                // 填充宽度 = fraction × 轨道宽（Glance 侧是 LinearProgressIndicator(progress = fraction)）
                Box(
                    modifier = Modifier
                        .testTag("quota.shot.bar-fill-" + index)
                        .fillMaxWidth(fraction.coerceIn(0f, 1f))
                        .fillMaxHeight()
                        .background(ink, RoundedCornerShape(999.dp)),
                )
            }
        }
    }
}