package com.bsfeng.monitor.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
// Dimens 通过 MonitorRadius 便捷访问

/**
 * 统一主题入口。
 *
 * 用法：在 MainActivity 的 setContent 中用 MonitorTheme 包裹整个 UI：
 * ```
 * MonitorTheme {
 *     // ... 你的页面
 * }
 * ```
 *
 * 所有页面从此通过 MaterialTheme.colorScheme / typography / shapes
 * 访问统一的 iOS 风格主题，不再使用硬编码颜色/字号/间距。
 */

/** 统一圆角定义。 */
private val monitorShapes = Shapes(
    extraSmall = RoundedCornerShape(Radius.sm),     // 8dp
    small = RoundedCornerShape(Radius.md),           // 10dp
    medium = RoundedCornerShape(Radius.lg),          // 12dp
    large = RoundedCornerShape(Radius.lg + 4.dp),   // 16dp
    extraLarge = RoundedCornerShape(Radius.xl),     // 16dp
)

/**
 * Monitor 主题入口。
 *
 * @param darkTheme 是否使用深色模式，默认跟随系统
 * @param content   子组件
 */
@Composable
fun MonitorTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) iOSDarkColorScheme else iOSLightColorScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = monitorTypography,
        shapes = monitorShapes,
        content = content,
    )
}

/**
 * 便捷访问当前主题的 spacing 值。
 * Material 3 没有内置 spacing token，所以通过此扩展访问。
 *
 * 用法：`MonitorSpacing.lg` 或 `with(MonitorSpacing) { padding(lg) }`
 */
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
