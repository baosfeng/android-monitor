package com.bsfeng.monitor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

/**
 * 统一主题入口（浅色唯一形态，无深色分支、不跟随系统）。
 *
 * ```
 * MonitorTheme {                            // 跟随 ThemeRepository 当前主题
 *     // ...
 * }
 * MonitorTheme(themeId = "bento") { ... }   // 强制指定主题（s13 预览用）
 * ```
 *
 * 非 Material 用色走 [LocalDesignTokens]：
 * ```
 * val t = LocalDesignTokens.current
 * Box(Modifier.background(t.chart1)) { ... }
 * ```
 */

/** 当前主题的非 Material token；默认 apple。 */
val LocalDesignTokens = staticCompositionLocalOf { DesignTokens(DesignTokenTable.DEFAULT_THEME) }

/** 统一圆角定义。 */
private val monitorShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.sm),
    small = RoundedCornerShape(Radius.md),
    medium = RoundedCornerShape(Radius.lg),
    large = RoundedCornerShape(Radius.lg + 4.dp),
    extraLarge = RoundedCornerShape(Radius.xl),
)

@Composable
fun MonitorTheme(
    themeId: String? = null,
    content: @Composable () -> Unit
) {
    val activeId = themeId ?: ThemeRepository.themeId.collectAsState().value
    val tokens = remember(activeId) { ThemeRepository.tokens(activeId) }
    val colorScheme = remember(activeId) { ThemeRepository.colorScheme(activeId) }
    CompositionLocalProvider(LocalDesignTokens provides tokens) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = monitorTypography,
            shapes = monitorShapes,
            content = content,
        )
    }
}

/** 便捷访问当前主题的 spacing 值。 */
object MonitorSpacing {
    val xxs get() = Spacing.xxs
    val xs get() = Spacing.xs
    val sm get() = Spacing.sm
    val md get() = Spacing.md
    val lg get() = Spacing.lg
    val xl get() = Spacing.xl
    val xxl get() = Spacing.xxl
    val xxxl get() = Spacing.xxxl
    val huge get() = Spacing.huge
}

/** 便捷访问当前主题的圆角值。 */
object MonitorRadius {
    val xs get() = Radius.xs
    val sm get() = Radius.sm
    val md get() = Radius.md
    val lg get() = Radius.lg
    val circle get() = Radius.circle
}

/** 便捷访问当前主题的图标尺寸。 */
object MonitorIconSize {
    val xs get() = IconSize.xs
    val sm get() = IconSize.sm
    val md get() = IconSize.md
    val lg get() = IconSize.lg
    val xl get() = IconSize.xl
}
