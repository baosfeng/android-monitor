package com.bsfeng.monitor.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

/**
 * 标准化字体体系 — 映射 iOS SF 字体层级。
 *
 * 项目中所有硬编码的 sp 值应逐步替换为以下映射：
 * ┌─────────────┬──────┬───────────────────┐
 * │ 旧硬编码     │ Token│ 用途               │
 * ├─────────────┼──────┼───────────────────┤
 * │ 34sp        │ hL   │ 大标题（记账金额）   │
 * │ 28~22sp     │ hM/hS│ 二级标题            │
 * │ 17sp Bold   │ tL   │ 导航栏标题+列表标题  │
 * │ 17sp Regular│ bL   │ 正文                │
 * │ 16sp        │ bM   │ 按钮文字/商品名      │
 * │ 15sp        │ tS   │ 列表项文字           │
 * │ 13sp        │ bS   │ 辅助说明文字         │
 * │ 12sp        │ lM   │ 底部提示/角标        │
 * │ 11sp        │ lS   │ 时间戳/小标签        │
 * │ 9~10sp      │ lS   │ 极小标签            │
 * └─────────────┴──────┴───────────────────┘
 */
val monitorTypography = Typography(
    // iOS Large Title (34pt Bold)
    headlineLarge = TextStyle(
        fontSize = 34.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 41.sp,
        letterSpacing = 0.37.sp,
    ),
    // iOS Title 1 (28pt Bold)
    headlineMedium = TextStyle(
        fontSize = 28.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 34.sp,
        letterSpacing = 0.34.sp,
    ),
    // iOS Title 2 (22pt Bold)
    headlineSmall = TextStyle(
        fontSize = 22.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = 28.sp,
        letterSpacing = 0.35.sp,
    ),
    // iOS Headline (17pt Semibold) — 导航栏标题 / 列表标题
    titleLarge = TextStyle(
        fontSize = 17.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 22.sp,
        letterSpacing = -0.41.sp,
    ),
    // 导航栏标题/段落标题
    titleMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = 21.sp,
        letterSpacing = 0.sp,
    ),
    // 列表项文字
    titleSmall = TextStyle(
        fontSize = 15.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 20.sp,
        letterSpacing = 0.sp,
    ),
    // iOS Body (17pt) — 正文
    bodyLarge = TextStyle(
        fontSize = 17.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 22.sp,
        letterSpacing = -0.41.sp,
    ),
    // iOS Callout (16pt) — 按钮 / 输入框
    bodyMedium = TextStyle(
        fontSize = 16.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 21.sp,
        letterSpacing = -0.32.sp,
    ),
    // iOS Footnote (13pt) — 辅助文字 / 说明
    bodySmall = TextStyle(
        fontSize = 13.sp,
        fontWeight = FontWeight.Normal,
        lineHeight = 18.sp,
        letterSpacing = -0.08.sp,
    ),
    // iOS Caption 1 (12pt) — 底部提示 / 角标
    labelMedium = TextStyle(
        fontSize = 12.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 16.sp,
        letterSpacing = 0.sp,
    ),
    // iOS Caption 2 (11pt) — 时间戳 / 极小标签
    labelSmall = TextStyle(
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        lineHeight = 13.sp,
        letterSpacing = 0.07.sp,
    ),
)
