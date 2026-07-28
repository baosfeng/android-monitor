package com.bsfeng.monitor.ui.theme

import androidx.compose.ui.unit.dp

/**
 * 标准化间距体系。
 *
 * 项目中所有硬编码的 dp 值应逐步替换为以下映射：
 * ┌────────┬────────┬─────────────────────────┐
 * │ Token  │ 数值   │ 典型用途                 │
 * ├────────┼────────┼─────────────────────────┤
 * │ xxs    │ 2.dp   │ 极小间距 / 图标内边距     │
 * │ xs     │ 4.dp   │ 元素间极小间距            │
 * │ sm     │ 8.dp   │ 元素间紧凑间距            │
 * │ md     │ 12.dp  │ 标准元素间距              │
 * │ lg     │ 16.dp  │ 内容内边距 / 列表行间距    │
 * │ xl     │ 20.dp  │ 大间距                   │
 * │ xxl    │ 24.dp  │ 区块间距                 │
 * │ xxxl   │ 32.dp  │ 大区块间距               │
 * │ huge   │ 48.dp  │ 页面间距                 │
 * └────────┴────────┴─────────────────────────┘
 */
object Spacing {
    val xxs = 2.dp
    val xs = 4.dp
    val sm = 8.dp
    val md = 12.dp
    val lg = 16.dp
    val xl = 20.dp
    val xxl = 24.dp
    val xxxl = 32.dp
    val huge = 48.dp
}

/**
 * 标准圆角。
 */
object Radius {
    /** 极小圆角 — 角标 */
    val xxs = 2.dp
    /** 小圆角 — 按钮 */
    val xs = 4.dp
    /** 小圆角 — 按钮 */
    val sm = 8.dp
    /** 中圆角 — 卡片 / InsetGroupedSection */
    val md = 10.dp
    /** 大圆角 — 弹窗 */
    val lg = 12.dp
    /** 超大圆角 — 底部弹窗 / 卡片 */
    val xl = 16.dp
    /** 全圆 — 图标圆点 / 头像 */
    val circle = 999.dp
}

/**
 * 标准图标尺寸。
 */
object IconSize {
    val xs = 12.dp
    val sm = 16.dp
    val md = 20.dp
    val lg = 24.dp
    val xl = 32.dp
}
