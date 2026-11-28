package com.bsfeng.monitor.widget

import androidx.compose.ui.unit.sp
import androidx.glance.text.FontFamily
import androidx.glance.text.FontWeight
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider

// 额度组件自己的字阶：Glance 的 TextStyle 与 App 内的 MaterialTheme 字阶是两套，
// 而额度组件又比其余 5 块多一个「等宽数值」需求，所以单独一层。
//
// 字号由 [QuotaWidgetScale] 给（矮箱子 / 高箱子两档），这里只负责字体族与字重 ——
// 不塞进 WidgetStyle.kt 的原因：那个文件贴住 300 行上限，且等宽数值只属于额度组件。

/**
 * 数值：等宽 + Bold，对应原型 .wr b 的 --font-mono + tabular-nums。
 *
 * 为什么只有这一块用 Monospace（其余 5 块组件已改 SansSerif）：桌面上一列 12% / 96% / 34%
 * 只有等宽时数字才上下对齐；Glance 的 TextStyle 没有 fontFeatureSettings，Monospace 是唯一的等宽入口。
 */
internal fun quotaValueStyle(color: ColorProvider, sizeSp: Int): TextStyle =
    widgetTextStyle(color, sizeSp.sp, FontWeight.Bold).copy(fontFamily = FontFamily.Monospace)

/** 说明行「账号 窗口 · 重置时刻」：单行截断，对应原型 .wr span。 */
internal fun quotaCaptionStyle(sizeSp: Int): TextStyle =
    widgetTextStyle(WIDGET_MUTED, sizeSp.sp, FontWeight.Normal)

/** 顶栏「额度 · 更新于 …」：Bold、卡面墨色，对应原型 .wtop。 */
internal fun quotaHeaderStyle(color: ColorProvider, sizeSp: Int): TextStyle =
    widgetTextStyle(color, sizeSp.sp, FontWeight.Bold)
