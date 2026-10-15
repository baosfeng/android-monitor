package com.bsfeng.monitor.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 标准化字体体系 — 对齐原型字阶（--text-xs 12 / --text-sm 14 / --text-base 17 / --text-xl 28，
 * 见 `.open-design/parts/00-tokens.css:100-107`）。
 *
 * 原型类名 → 本表映射：
 * ┌────────────────────────┬───────┬────────────────────────────────┐
 * │ 原型                   │ Token │ 用途                            │
 * ├────────────────────────┼───────┼────────────────────────────────┤
 * │ .h1 (28/700)           │ hM    │ 页大标题                        │
 * │ .h2 (21/600)           │ hS    │ 区块标题                        │
 * │ .h3 (17/600)           │ tM    │ 卡片头 / 空态标题（.st-title）   │
 * │ .row-title (17/500)    │ tS    │ 列表行主标题                     │
 * │ .meta / .row-sub (14)  │ bS/lM │ 说明 / 列表副标题 / 分组标签      │
 * │ .fine (12)             │ lS    │ 极小说明 / 时间戳                │
 * │ .btn / .chip (14/500)  │ lL    │ 控件文字（M3 labelLarge 默认 14）│
 * │ .kpi-value (28 mono)   │ —     │ `monitorMonoNumber` 等宽数字族    │
 * └────────────────────────┴───────┴────────────────────────────────┘
 */
val monitorTypography = Typography(
    // .h1 --text-xl 28px/700 之上还有 34sp 大标题（记账金额等强调场景）
    headlineLarge = TextStyle(
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 41.sp,
        letterSpacing = 0.37.sp,
    ),
    // .h1 --text-xl 28px/700 — 页大标题
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 32.sp,
        letterSpacing = -0.42.sp,
    ),
    // .h2 --text-lg 21px/600 — 区块标题
    headlineSmall = TextStyle(
        fontSize = 21.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 26.sp,
        letterSpacing = -0.25.sp,
    ),
    // iOS Headline (17pt Semibold) — 导航栏标题
    titleLarge = TextStyle(
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 22.sp,
        letterSpacing = -0.41.sp,
    ),
    // .h3 --text-base 17px/600 — 卡片头 / .st-title
    titleMedium = TextStyle(
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 22.sp,
        letterSpacing = -0.41.sp,
    ),
    // .row-title --text-base 17px/500 — 列表行主标题
    titleSmall = TextStyle(
        fontSize = 17.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 21.sp,
        letterSpacing = -0.41.sp,
    ),
    // .tl-title / 正文（--text-base 17）
    bodyLarge = TextStyle(
        fontSize = 17.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 24.sp,
        letterSpacing = -0.41.sp,
    ),
    // 按钮 / 输入框
    bodyMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 21.sp,
        letterSpacing = -0.32.sp,
    ),
    // .meta --text-sm 14px — 辅助说明 / .st-desc
    bodySmall = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 19.sp,
        letterSpacing = -0.15.sp,
    ),
    // .row-sub / .group-label --text-sm 14px — 列表副标题
    labelMedium = TextStyle(
        fontSize = 14.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 18.sp,
        letterSpacing = -0.15.sp,
    ),
    // .fine --text-xs 12px — 极小说明 / 时间戳
    labelSmall = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 15.sp,
        letterSpacing = 0.07.sp,
    ),
)

/**
 * 等宽数字族（原型 .kpi-value = --font-mono + font-variant-numeric:tabular-nums）：
 * 金额 / 计数 / KPI 数值用这一族，保证逐位对齐。
 */
private val monoBase = TextStyle(
    fontFamily = FontFamily.Monospace,
    fontFeatureSettings = "tnum",
)

/** .kpi-value --text-xl 28px/700 等宽（KPI 主数值、大额金额）。 */
val monitorMonoNumber: TextStyle = monoBase.copy(
    fontSize = 28.sp,
    fontWeight = FontWeight.Bold,
    lineHeight = 31.sp,
    letterSpacing = -0.42.sp,
)

/** .s07-kv 21px/700 等宽（统计页 KPI 瓦片）。 */
val monitorMonoKpi: TextStyle = monoBase.copy(
    fontSize = 21.sp,
    fontWeight = FontWeight.Bold,
    lineHeight = 24.sp,
    letterSpacing = -0.25.sp,
)

/** .badge --text-xs 等宽 11sp/600（角标）。 */
val monitorMonoBadge: TextStyle = monoBase.copy(
    fontSize = 11.sp,
    fontWeight = FontWeight.SemiBold,
    lineHeight = 14.sp,
    letterSpacing = 0.2.sp,
)

/** .tl-time / .legend-num --text-sm 14px 等宽（时间列、图例数值）。 */
val monitorMonoMeta: TextStyle = monoBase.copy(
    fontSize = 14.sp,
    fontWeight = FontWeight.Normal,
    lineHeight = 18.sp,
)
