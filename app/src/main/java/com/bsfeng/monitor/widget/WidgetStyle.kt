package com.bsfeng.monitor.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.action.Action
import androidx.glance.action.clickable
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.color.ColorProvider as dayNightColor
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.ColumnScope
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.bsfeng.monitor.ui.theme.WidgetPalette

// 6 个组件共用的一层皮（C 版「柔和品牌色」）：每卡一个色相、浅 tint 卡底、内嵌分区面、
// 实色圆角图标块、同色系进度条。改圆角/字号/结构只能在这里改，组件内不写字面量。

/** 卡片圆角 24dp（设计.md 通用规格）。 */
private val CARD_RADIUS = 24.dp

/** 卡描边粗细：Glance 没有 border modifier，用「外层画描边色 + 内缩 1dp」模拟。 */
private val CARD_EDGE = 1.dp

/** 一张卡的一套面色。色值只来自 [WidgetPalette]（theme 包才允许写字面量）。 */
internal class CardFace(
    val surface: ColorProvider,
    val section: ColorProvider,
    val icon: ColorProvider,
    val bar: ColorProvider,
    val track: ColorProvider,
    val edge: ColorProvider,
)

private fun faceOf(tokens: WidgetPalette.CardTokens): CardFace = CardFace(
    surface = dayNightColor(day = tokens.surfaceDay, night = tokens.surfaceNight),
    section = dayNightColor(day = tokens.sectionDay, night = tokens.sectionNight),
    icon = dayNightColor(day = tokens.iconDay, night = tokens.iconNight),
    bar = dayNightColor(day = tokens.barDay, night = tokens.barNight),
    track = dayNightColor(day = tokens.trackDay, night = tokens.trackNight),
    edge = dayNightColor(day = tokens.edgeDay, night = tokens.edgeNight),
)

internal val FACE_FINANCE = faceOf(WidgetPalette.finance)
internal val FACE_QUOTA = faceOf(WidgetPalette.quota)
internal val FACE_HEALTH = faceOf(WidgetPalette.health)
internal val FACE_ADSKIP = faceOf(WidgetPalette.adskip)
internal val FACE_TRANSFER = faceOf(WidgetPalette.transfer)

/** 全局前景/语义（单测仍在用这些名字）。 */
internal val WIDGET_TEXT = dayNightColor(day = WidgetPalette.textDay, night = WidgetPalette.textNight)
internal val WIDGET_MUTED = dayNightColor(day = WidgetPalette.mutedDay, night = WidgetPalette.mutedNight)
internal val WIDGET_BORDER = dayNightColor(day = WidgetPalette.borderDay, night = WidgetPalette.borderNight)
internal val WIDGET_TAG = dayNightColor(day = WidgetPalette.tagDay, night = WidgetPalette.tagNight)
internal val WIDGET_ACCENT = dayNightColor(day = WidgetPalette.accentDay, night = WidgetPalette.accentNight)
internal val WIDGET_EXPENSE = dayNightColor(day = WidgetPalette.badDay, night = WidgetPalette.badNight)
internal val WIDGET_INCOME = dayNightColor(day = WidgetPalette.okDay, night = WidgetPalette.okNight)
internal val WIDGET_WARN = dayNightColor(day = WidgetPalette.warnDay, night = WidgetPalette.warnNight)

/** 主色块 / 图标块上的前景（白，深浅同值）。 */
internal val WIDGET_ON_HUE = dayNightColor(day = WidgetPalette.onHue, night = WidgetPalette.onHue)

/** 快捷记一笔的实色主色块（深浅同值）。 */
internal val WIDGET_QUICK_ADD = dayNightColor(day = WidgetPalette.quickAdd, night = WidgetPalette.quickAdd)

/** TINY 档竖向居中，其余顶对齐（1×1 内容少，顶对齐会在底部留一大块空）。 */
internal fun tierAlignment(tier: WidgetTier): Alignment.Vertical =
    if (tier == WidgetTier.TINY) Alignment.Vertical.CenterVertically else Alignment.Vertical.Top

/**
 * 卡片外壳：tint 卡底 + 1dp 描边 + 24dp 圆角 + 整块可点。
 *
 * 描边用两层 Box 模拟（外层 = 描边色、内缩 1dp 的内层 = 卡底）：Glance 没有 border modifier，
 * 而桌面壁纸深浅不一，没有描边时浅色卡在亮壁纸上会糊掉。点击挂在内层 Column 上、
 * 顺序在 padding 之前 —— 否则用户戳在内边距那块空白时事件落空。
 *
 * ⚠️ [content] 里的直接子元素最多 10 个：Glance 的 Column 超过 10 个只打一行
 * "Column container cannot have more than 10 elements" 日志、其余元素被静默丢掉，
 * 桌面不会报错也没有占位。行数多的列表（额度三行）要先把"行 + 轨道"包进内层 Column 再放进来。
 */
@Composable
internal fun WidgetCard(
    face: CardFace,
    action: Action,
    verticalAlignment: Alignment.Vertical = Alignment.Vertical.Top,
    horizontalAlignment: Alignment.Horizontal = Alignment.Horizontal.Start,
    padding: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    // 根必须是单个 fillMaxSize 的 Column：实测「外层 Box(fillMaxSize + padding) 再套内层 Column(fillMaxSize)」
    // 在 RemoteViews 里会被量成 0 高度，桌面整块空白（Glance 不报错，只交一张空 RemoteViews）。
    // 所以描边从两层 Box 退回到「卡底 + 内嵌分区面」——浮起感靠 tint 色差，不靠 1dp 描边。
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(face.surface)
            .cornerRadius(CARD_RADIUS)
            .clickable(action)
            // 可达标识：Glance 1.2 只提供属性形式（没有 testTag(...) 函数），glance-testing 的 hasTestTag 读它
            .semantics { testTag = "widget.card" }
            .padding(padding),
        verticalAlignment = verticalAlignment,
        horizontalAlignment = horizontalAlignment,
        content = content,
    )
}

/** 不带描边的实色块（快捷记一笔）：主色底 + 白字。 */
@Composable
internal fun SolidTile(action: Action, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WIDGET_QUICK_ADD)
            .cornerRadius(CARD_RADIUS)
            .clickable(action)
            .semantics { testTag = "widget.tile" }
            .padding(12.dp),
        verticalAlignment = Alignment.Vertical.CenterVertically,
        horizontalAlignment = Alignment.Horizontal.CenterHorizontally,
        content = content,
    )
}

/**
 * 圆角图标块：实色块 + 一个字符符号（¥ / % / ♥ / » / ↕）。
 *
 * 为什么不用矢量图标（原型那版）：实测 Glance 的 Image + ColorFilter.tint 生成的 RemoteViews
 * 在 launcher 上渲染成空白（整块卡片不可见、Glance 也不报错，见踩坑记录），所以图标位置改成
 * 系统字体里的字符 —— 零资源依赖、深浅色都稳、还能跟着字号缩放。
 */
@Composable
internal fun IconBadge(face: CardFace, symbol: String, size: Dp = 26.dp) {
    Box(
        modifier = GlanceModifier.size(size).background(face.icon).cornerRadius(size / 3),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = symbol,
            style = TextStyle(color = WIDGET_ON_HUE).copy(
                fontSize = (size.value * 0.52f).sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
}

/** 顶行：图标块 + 眉标 + 右侧胶囊。 */
@Composable
internal fun CardHead(
    face: CardFace,
    symbol: String,
    label: String,
    trailing: String? = null,
    badge: Dp = 26.dp,
) {
    Row(
        modifier = GlanceModifier.fillMaxWidth(),
        verticalAlignment = Alignment.Vertical.CenterVertically,
    ) {
        IconBadge(face = face, symbol = symbol, size = badge)
        Spacer(GlanceModifier.width(8.dp))
        Text(text = label, style = labelStyle(), maxLines = 1)
        Spacer(GlanceModifier.defaultWeight())
        if (trailing != null) {
            Pill(text = trailing)
        }
    }
}

/** 右上角胶囊：白底（深色档深一档）+ 次要色文字。 */
@Composable
internal fun Pill(text: String) {
    Box(
        modifier = GlanceModifier
            .background(WIDGET_TAG)
            .cornerRadius(999.dp)
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) {
        Text(text = text, style = labelStyle(), maxLines = 1)
    }
}

/** 内嵌分区面（底栏两块 / 额度读数块）：同色系深一档 + 14dp 圆角。 */
@Composable
internal fun Section(
    face: CardFace,
    modifier: GlanceModifier = GlanceModifier,
    radius: Dp = 14.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier.background(face.section).cornerRadius(radius).padding(10.dp),
        content = content,
    )
}

/** 一组「小标签 + 数值」，用于底栏分区与 1fr 读数。 */
@Composable
internal fun Stat(
    label: String,
    value: String,
    valueColor: ColorProvider = WIDGET_TEXT,
    valueSize: TextUnit = 15.sp,
    modifier: GlanceModifier = GlanceModifier,
    horizontalAlignment: Alignment.Horizontal = Alignment.Horizontal.Start,
) {
    Column(modifier = modifier, horizontalAlignment = horizontalAlignment) {
        Text(text = label, style = labelStyle(), maxLines = 1)
        Spacer(GlanceModifier.height(3.dp))
        Text(text = value, style = valueStyle(valueColor, valueSize), maxLines = 1)
    }
}

/** 读数之间的 1dp 竖分隔线（靠 [fillMaxHeight] 撑满行高）。 */
@Composable
internal fun VDivider(color: ColorProvider = WIDGET_BORDER) {
    // 空内容也要留块：分隔线靠 Box 自身的背景出颜色，不依赖任何子视图
    Box(modifier = GlanceModifier.width(1.dp).fillMaxHeight().background(color)) { }
}

/** 进度条：粗细靠 height，端头圆角仅 API31+ 生效。 */
@Composable
internal fun Bar(
    progress: Float,
    face: CardFace,
    color: ColorProvider = face.bar,
    thickness: Dp = 8.dp,
    modifier: GlanceModifier = GlanceModifier.fillMaxWidth(),
) {
    androidx.glance.appwidget.LinearProgressIndicator(
        progress = progress.coerceIn(0f, 1f),
        modifier = modifier.height(thickness),
        color = color,
        backgroundColor = face.track,
    )
}

/** 状态点：7dp 圆点（角半径大于边长，系统按圆处理）。 */
@Composable
internal fun StatusDot(color: ColorProvider, size: Dp = 7.dp) {
    // 空内容也要留块：状态点靠 Box 自身的背景出颜色
    Box(modifier = GlanceModifier.size(size).background(color).cornerRadius(999.dp)) { }
}

// ===================== 字阶（SansSerif、无等宽、无字距） =====================

/**
 * 字号只能在这里定：Glance 的 TextStyle 与 App 内 MaterialTheme 排版体系是两套，
 * 桌面组件没有「主题字阶」可引用；集中一处至少保证 6 块组件一致。
 *
 * 为什么不再用 Monospace（旧版）：等宽字字距大、笔画细，桌面小尺寸下显单薄；
 * C 版定稿全部 SansSerif + Bold，数字的「厚度」靠字重与字号给。
 */
private fun widgetTextStyle(
    color: ColorProvider,
    size: TextUnit,
    weight: FontWeight,
): TextStyle = TextStyle(color).copy(fontSize = size, fontWeight = weight, fontFamily = FontFamily.SansSerif)

/** 说明文字 12sp：眉标、标签、分区标签、更新时间。 */
internal fun labelStyle(color: ColorProvider = WIDGET_MUTED): TextStyle =
    widgetTextStyle(color, 12.sp, FontWeight.Medium)

/** 次数值 15sp：底栏金额、读数、provider 名。 */
internal fun valueStyle(color: ColorProvider = WIDGET_TEXT, size: TextUnit = 15.sp): TextStyle =
    widgetTextStyle(color, size, FontWeight.Bold)

/** 标题 17sp Medium：空态主文案。 */
internal fun titleStyle(color: ColorProvider = WIDGET_TEXT): TextStyle =
    widgetTextStyle(color, 17.sp, FontWeight.Medium)

/** 主数值 28sp Bold（默认档）；1×1 传更小的字号。 */
internal fun numberStyle(color: ColorProvider = WIDGET_TEXT, size: TextUnit = 28.sp): TextStyle =
    widgetTextStyle(color, size, FontWeight.Bold)
