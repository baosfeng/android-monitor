package com.bsfeng.monitor.ui.theme

import androidx.compose.ui.graphics.Color
import com.bsfeng.monitor.domain.model.RecordType

// =============================================
// iOS 系统色板
// =============================================

object IOSColors {
    /** iOS 系统蓝 #007AFF */
    val blue = Color(0xFF007AFF)
    /** iOS 系统红 #FF3B30 */
    val red = Color(0xFFFF3B30)
    /** iOS 系统绿 #34C759 */
    val green = Color(0xFF34C759)
    /** iOS 系统橙 #FF9500 */
    val orange = Color(0xFFFF9500)
    /** iOS 系统黄 #FFCC00 */
    val yellow = Color(0xFFFFCC00)
    /** iOS 系统浅蓝 #5AC8FA */
    val lightBlue = Color(0xFF5AC8FA)
    /** iOS 系统紫 #AF52DE */
    val purple = Color(0xFFAF52DE)
    /** iOS 系统粉 #FF2D55 */
    val pink = Color(0xFFFF2D55)
    /** iOS 系统深紫 #5856D6 */
    val indigo = Color(0xFF5856D6)
    /** iOS 系统青 #00C7BE */
    val teal = Color(0xFF00C7BE)
    /** iOS 系统灰 #8E8E93 */
    val gray = Color(0xFF8E8E93)
    /** iOS 系统浅灰 #E5E5EA */
    val lightGray = Color(0xFFE5E5EA)
    /** iOS 系统背景灰 #F2F2F7 */
    val systemBackground = Color(0xFFF2F2F7)
    /** iOS 系统卡片白 */
    val groupedBackground = Color.White
}

// =============================================
// 事件类型色 — 原 MainScreen.getTypeColor()
// =============================================

/** 获取事件类型对应的事件带颜色 */
fun RecordType.toEventColor(): Color = when (this) {
    RecordType.SCREEN -> Color(0xFF5B8DEF)     // 中蓝
    RecordType.APP_USAGE -> IOSColors.green
    RecordType.CHARGING, RecordType.BATTERY -> IOSColors.orange
    RecordType.WIFI -> IOSColors.blue
    RecordType.AUDIO -> IOSColors.purple
    RecordType.PACKAGE -> IOSColors.pink
    RecordType.POWER_SAVE -> IOSColors.gray
    RecordType.AIRPLANE -> IOSColors.indigo
    RecordType.PHONE_STATE -> Color(0xFF30B0C7) // 青蓝
    RecordType.LOCATION -> Color(0xFF4CD964)    // 浅绿
}

// =============================================
// 财务分类色板 — 原 categoryPalette（15 色）
// =============================================

/** 记账分类调色板，按索引取色 */
val categoryPalette = listOf(
    IOSColors.red,      // 0: 红
    IOSColors.orange,   // 1: 橙
    IOSColors.yellow,   // 2: 黄
    IOSColors.green,    // 3: 绿
    IOSColors.lightBlue,// 4: 浅蓝
    IOSColors.blue,     // 5: 蓝
    IOSColors.purple,   // 6: 紫
    IOSColors.pink,     // 7: 粉红
    IOSColors.indigo,   // 8: 深紫
    IOSColors.teal,     // 9: 青
    Color(0xFFFF6482),  // 10: 粉
    Color(0xFF6EA8FF),  // 11: 淡蓝
    Color(0xFFA2845E),  // 12: 棕
    IOSColors.gray,     // 13: 灰
    Color(0xFFFFD60A),  // 14: 亮黄
)

object FinanceColors {
    val expense = IOSColors.red
    val income = IOSColors.green
    val budget = IOSColors.blue
}

// =============================================
// Material 3 ColorScheme 生成
// =============================================

/** 浅色模式 — iOS 风格 ColorScheme */
val iOSLightColorScheme = androidx.compose.material3.lightColorScheme(
    primary = IOSColors.blue,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7E8FF),
    onPrimaryContainer = Color(0xFF003258),
    secondary = IOSColors.indigo,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEAFB),
    onSecondaryContainer = Color(0xFF1C1B33),
    tertiary = IOSColors.teal,
    onTertiary = Color.White,
    background = IOSColors.systemBackground,
    onBackground = Color(0xFF1C1B1F),
    surface = Color.White,
    onSurface = Color(0xFF1C1B1F),
    surfaceVariant = IOSColors.lightGray,
    onSurfaceVariant = Color(0xFF49454F),
    outline = Color(0xFFC6C6C8),
    outlineVariant = Color(0xFFD9D9DE),
    error = IOSColors.red,
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

/** 深色模式 — iOS 风格 ColorScheme */
val iOSDarkColorScheme = androidx.compose.material3.darkColorScheme(
    primary = Color(0xFF81B4FF),
    onPrimary = Color(0xFF003258),
    primaryContainer = Color(0xFF004A8C),
    onPrimaryContainer = Color(0xFFD7E8FF),
    secondary = Color(0xFFC8C2F0),
    onSecondary = Color(0xFF1C1B33),
    secondaryContainer = Color(0xFF35345C),
    onSecondaryContainer = Color(0xFFEDEAFB),
    tertiary = Color(0xFF72E0D4),
    onTertiary = Color(0xFF003734),
    background = Color(0xFF1C1B1F),
    onBackground = Color(0xFFE6E1E5),
    surface = Color(0xFF1C1B1F),
    onSurface = Color(0xFFE6E1E5),
    surfaceVariant = Color(0xFF2C2C30),
    onSurfaceVariant = Color(0xFFCAC4D0),
    outline = Color(0xFF3C3C40),
    outlineVariant = Color(0xFF2C2C30),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)
