package com.bsfeng.monitor.widget

// 组件预览图（appwidget-provider 的 android:previewImage）的渲染皮 —— 只在测试源集里用。
//
// 为什么要在 Compose 里再画一遍：Glance 的 composition 只在真机 launcher 上出画面，单测渲染不了
// RemoteViews；而预览图必须是「一张不含真实数据的静态图」。所以按 WidgetStyle.kt 的同一套常量复刻。
//
// 同源约束：改 WidgetStyle.kt 的圆角 / 内边距 / 字号 / 图标块比例时，必须同步改这里 ——
// 预览图和桌面上真实组件长得不一样，比没有预览图更糟（用户按图去找、找不到）。

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bsfeng.monitor.ui.theme.WidgetPalette

/** 卡圆角：与 WidgetStyle.CARD_RADIUS 同值（直接取，不复制数字）。 */
internal val PREVIEW_CARD_RADIUS = CARD_RADIUS

/** 一张卡的面色 —— 预览图固定用浅色档，深色档的样张在安卓小部件列表里会被当成坏图。 */
internal class PreviewFace(
    val surface: Color,
    val section: Color,
    val icon: Color,
    val bar: Color,
    val track: Color,
)

private fun previewFace(tokens: WidgetPalette.CardTokens) = PreviewFace(
    surface = tokens.surfaceDay,
    section = tokens.sectionDay,
    icon = tokens.iconDay,
    bar = tokens.barDay,
    track = tokens.trackDay,
)

internal val PREVIEW_FINANCE = previewFace(WidgetPalette.finance)
internal val PREVIEW_QUOTA = previewFace(WidgetPalette.quota)
internal val PREVIEW_HEALTH = previewFace(WidgetPalette.health)
internal val PREVIEW_ADSKIP = previewFace(WidgetPalette.adskip)
internal val PREVIEW_TRANSFER = previewFace(WidgetPalette.transfer)

internal val PREVIEW_ON_HUE = WidgetPalette.onHue
internal val PREVIEW_TEXT = WidgetPalette.textDay
internal val PREVIEW_MUTED = WidgetPalette.mutedDay
internal val PREVIEW_BORDER = WidgetPalette.borderDay
internal val PREVIEW_TAG = WidgetPalette.tagDay
internal val PREVIEW_INCOME = WidgetPalette.okDay
internal val PREVIEW_EXPENSE = WidgetPalette.badDay
internal val PREVIEW_WARN = WidgetPalette.warnDay
internal val PREVIEW_QUICK_ADD = WidgetPalette.quickAdd

/** 卡片外壳：tint 卡底 + 24dp 圆角（对应 WidgetCard）。 */
@Composable
internal fun PreviewCard(
    face: PreviewFace,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(face.surface, RoundedCornerShape(PREVIEW_CARD_RADIUS))
            .padding(padding),
        verticalArrangement = Arrangement.Top,
        content = content,
    )
}

/** 实色主色块（对应 SolidTile，快捷记一笔专用）。 */
@Composable
internal fun PreviewSolidTile(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(PREVIEW_QUICK_ADD, RoundedCornerShape(PREVIEW_CARD_RADIUS))
            .padding(12.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content,
    )
}

/** 圆角图标块 + 字符符号（对应 IconBadge，圆角与字号比例同 size/3、size*0.52）。 */
@Composable
internal fun PreviewBadge(face: PreviewFace, symbol: String, size: Dp = 26.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .background(face.icon, RoundedCornerShape(size / 3)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            color = PREVIEW_ON_HUE,
            fontSize = (size.value * 0.52f).sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }
}

/** 顶行：图标块 + 眉标 + 右侧胶囊（对应 CardHead）。 */
@Composable
internal fun PreviewHead(
    face: PreviewFace,
    symbol: String,
    label: String,
    trailing: String? = null,
    badge: Dp = 26.dp,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        PreviewBadge(face, symbol, badge)
        Spacer(Modifier.width(8.dp))
        Text(text = label, style = previewLabelStyle(), maxLines = 1)
        Spacer(Modifier.weight(1f))
        if (trailing != null) PreviewPill(trailing)
    }
}

/** 右上角胶囊（对应 Pill）。 */
@Composable
internal fun PreviewPill(text: String) {
    Box(
        modifier = Modifier
            .background(PREVIEW_TAG, RoundedCornerShape(999.dp))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text = text, style = previewLabelStyle(), maxLines = 1)
    }
}

/** 内嵌分区面（对应 Section）。 */
@Composable
internal fun PreviewSection(
    face: PreviewFace,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .background(face.section, RoundedCornerShape(14.dp))
            .padding(10.dp),
        content = content,
    )
}

/** 一组「小标签 + 数值」（对应 Stat）。 */
@Composable
internal fun PreviewStat(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    valueColor: Color = PREVIEW_TEXT,
    valueSize: TextUnit = 15.sp,
) {
    Column(modifier = modifier) {
        Text(text = label, style = previewLabelStyle(), maxLines = 1)
        Spacer(Modifier.height(3.dp))
        Text(text = value, style = previewValueStyle(valueColor, valueSize), maxLines = 1)
    }
}

/**
 * 1dp 竖分隔线（对应 VDivider）。
 *
 * 给固定高度而不是 fillMaxHeight：Compose 的 Row 是 wrap 高度，子项 fillMaxHeight 拿到的是父级约束、
 * 不是同行最高子项，实际会量成 0；Glance 侧的 VDivider 走另一套测量才撑得满。预览图按行高取值即可。
 */
@Composable
internal fun PreviewVDivider(color: Color = PREVIEW_BORDER, height: Dp = 36.dp) {
    Box(modifier = Modifier.width(1.dp).height(height).background(color)) { }
}

/** 进度条（对应 Bar：填充 = progress × 轨道宽）。 */
@Composable
internal fun PreviewBar(
    progress: Float,
    face: PreviewFace,
    thickness: Dp = 8.dp,
    modifier: Modifier = Modifier.fillMaxWidth(),
) {
    Box(
        modifier = modifier
            .height(thickness)
            .background(face.track, RoundedCornerShape(999.dp)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .fillMaxHeight()
                .background(face.bar, RoundedCornerShape(999.dp)),
        )
    }
}

/** 状态点（对应 StatusDot）。 */
@Composable
internal fun PreviewDot(color: Color, size: Dp = 7.dp) {
    Box(modifier = Modifier.size(size).background(color, RoundedCornerShape(999.dp))) { }
}

// ===================== 字阶（与 WidgetStyle 的 labelStyle / valueStyle / numberStyle 同参） =====================

internal fun previewTextStyle(color: Color, size: TextUnit, weight: FontWeight): TextStyle =
    TextStyle(color = color, fontSize = size, fontWeight = weight)

internal fun previewLabelStyle(color: Color = PREVIEW_MUTED): TextStyle =
    previewTextStyle(color, 12.sp, FontWeight.Medium)

internal fun previewValueStyle(color: Color = PREVIEW_TEXT, size: TextUnit = 15.sp): TextStyle =
    previewTextStyle(color, size, FontWeight.Bold)

internal fun previewNumberStyle(color: Color = PREVIEW_TEXT, size: TextUnit = 28.sp): TextStyle =
    previewTextStyle(color, size, FontWeight.Bold)

internal fun previewTitleStyle(color: Color = PREVIEW_TEXT): TextStyle =
    previewTextStyle(color, 17.sp, FontWeight.Medium)
