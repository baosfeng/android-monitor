package com.bsfeng.monitor.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import com.bsfeng.monitor.domain.model.RecordType

// =============================================
// 兼容层：旧语义名 → 新 token 名
//
// 本文件不再持有任何色值：每个名字都读 DesignTokenTable（apple 主题）的解析结果。
// 完整对照表见 tools/LEGACY-TOKEN-MAP.md 与 DesignTokenCatalog.legacyTable。
// 新代码请直接用 LocalDesignTokens.current（含 8 套主题），不要再用本兼容层。
// =============================================

private const val LEGACY_THEME: String = DesignTokenTable.DEFAULT_THEME

private fun legacyColor(token: String): Color = DesignTokenTable.colorOf(LEGACY_THEME, token)

/** 旧 iOS 语义色。每个名字 → 新 token 见 tools/LEGACY-TOKEN-MAP.md。 */
@Deprecated("改用 LocalDesignTokens.current 的对应 token")
object IOSColors {
    /** → accent */
    val blue = legacyColor("accent")
    /** → danger */
    val red = legacyColor("danger")
    /** → success */
    val green = legacyColor("success")
    /** → warn */
    val orange = legacyColor("warn")
    /** → chart-4 */
    val yellow = legacyColor("chart-4")
    /** → chart-5 */
    val lightBlue = legacyColor("chart-5")
    /** → chart-3 */
    val purple = legacyColor("chart-3")
    /** → chart-2 */
    val pink = legacyColor("chart-2")
    /** → chart-6 */
    val indigo = legacyColor("chart-6")
    /** → chart-7 */
    val teal = legacyColor("chart-7")
    /** → muted */
    val gray = legacyColor("muted")
    /** → border-soft */
    val lightGray = legacyColor("border-soft")
    /** → bg */
    val systemBackground = legacyColor("bg")
    /** → surface */
    val groupedBackground = legacyColor("surface")
}

/** 旧记账分类色板（15 槽）。改用 [DesignTokens.categoryDot]（chart-1..chart-8 循环）。 */
@Deprecated("改用 LocalDesignTokens.current.categoryDot(index)")
val categoryPalette: List<Color> = (0 until 15).map { legacyColor("chart-" + (it % 8 + 1)) }

/** 旧记账语义色。填充态用 danger / success / accent，文本态用 danger-text / success-text。 */
@Deprecated("改用 LocalDesignTokens.current.danger / success / accent")
object FinanceColors {
    /** → danger（文本态 danger-text） */
    val expense = legacyColor("danger")
    /** → success（文本态 success-text） */
    val income = legacyColor("success")
    /** → accent */
    val budget = legacyColor("accent")
}

private val EVENT_TOKEN: Map<RecordType, String> = mapOf(
    RecordType.SCREEN to "chart-1",
    RecordType.APP_USAGE to "chart-2",
    RecordType.CHARGING to "warn",
    RecordType.BATTERY to "warn",
    RecordType.WIFI to "accent",
    RecordType.AUDIO to "chart-3",
    RecordType.PACKAGE to "chart-4",
    RecordType.POWER_SAVE to "muted",
    RecordType.AIRPLANE to "chart-5",
    RecordType.PHONE_STATE to "chart-6",
    RecordType.LOCATION to "success",
)

/** 获取事件类型对应的事件带颜色。改用 [DesignTokens.eventColor]（会跟随当前主题）。 */
@Deprecated("改用 LocalDesignTokens.current.eventColor(type)")
fun RecordType.toEventColor(): Color = legacyColor(EVENT_TOKEN.getValue(this))

/**
 * apple（默认主题）ColorScheme 的静态快照，仅为兼容旧引用保留。
 * 当前主题请用 MaterialTheme.colorScheme 或 ThemeRepository.colorScheme()。
 */
@Deprecated("改用 MaterialTheme.colorScheme 或 ThemeRepository.colorScheme()")
val iOSLightColorScheme: ColorScheme = GeneratedColorScheme.schemeOf(DesignTokenTable.DEFAULT_THEME)
