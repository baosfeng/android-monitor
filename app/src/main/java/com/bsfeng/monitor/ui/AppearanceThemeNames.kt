package com.bsfeng.monitor.ui

// 从 ui/AppearanceScreen.kt 拆出：主题的中英文名映射与标签文案（原型 s13 的 .theme-cn / .theme-en / .s13-current），
// 以及色值 hex 格式化。负责「主题叫什么、色值怎么写」，不含任何布局。

import androidx.compose.ui.graphics.toArgb
import com.bsfeng.monitor.ui.theme.ThemeRepository

/** 主题中文名（原型 s13-appearance.html:51-82 的 .theme-cn）。 */
private val THEME_CN: Map<String, String> = mapOf(
    "apple" to "苹果",
    "glassmorphism" to "玻璃",
    "notion" to "纸面",
    "stripe" to "条纹",
    "claude" to "陶土",
    "ibm" to "工业",
    "kami" to "墨青",
    "bento" to "便当",
)

/** 主题英文名（原型 .theme-en）。 */
private val THEME_EN: Map<String, String> = mapOf(
    "apple" to "Apple",
    "glassmorphism" to "Glassmorphism",
    "notion" to "Notion",
    "stripe" to "Stripe",
    "claude" to "Claude",
    "ibm" to "IBM",
    "kami" to "Kami",
    "bento" to "Bento",
)

/** 未知 id 原样回显（与 ThemeRepository.displayName 同一 fail-open 口径）。 */
internal fun themeNameCn(id: String): String = THEME_CN[id] ?: id

internal fun themeNameEn(id: String): String = THEME_EN[id] ?: id

/** 「当前」标签：原型 s13-current 的 NAME 表（apple 只显英文，其余「中文 英文」）。 */
internal fun themeLabel(id: String): String =
    if (id == ThemeRepository.DEFAULT_THEME) themeNameEn(id) else themeNameCn(id) + " " + themeNameEn(id)

/**
 * 主题 id → 锚点（原型 data-od-id）。
 *
 * 锚点按原型逐字沿用：glassmorphism 在账本里叫 `s13-theme-glass`（不是 s13-theme-glassmorphism），
 * 其余 7 个与 id 同名。改号会断开与原型、评审、实现的对应链，故只在表里转写、不在调用点拼字符串。
 */
private val THEME_ANCHOR: Map<String, String> = mapOf(
    "apple" to "s13-theme-apple",
    "glassmorphism" to "s13-theme-glass",
    "notion" to "s13-theme-notion",
    "stripe" to "s13-theme-stripe",
    "claude" to "s13-theme-claude",
    "ibm" to "s13-theme-ibm",
    "kami" to "s13-theme-kami",
    "bento" to "s13-theme-bento",
)

/** 主题项锚点：未知 id 按 id 原样拼（与 [themeNameCn] 的 fail-open 口径一致）。 */
internal fun themeAnchor(id: String): String = THEME_ANCHOR[id] ?: "s13-theme-" + id

/** 颜色 → #RRGGBB（仅用于展示当前主题取值，不参与样式）。 */
internal fun hex(color: androidx.compose.ui.graphics.Color): String {
    val argb = color.toArgb()
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return "#" + r.toString(16).padStart(2, '0') + g.toString(16).padStart(2, '0') + b.toString(16).padStart(2, '0')
}
