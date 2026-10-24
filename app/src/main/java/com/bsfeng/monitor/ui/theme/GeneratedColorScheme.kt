// ============================================================================
// GeneratedColorScheme.kt —— 生成文件，请勿手改。
// 职责：Material3 ColorScheme 派生：把 SCHEME_RULES 与 GeneratedColorSchemeTables 的原值解析成 Compose ColorScheme。
// 来源：.open-design/08-prototype.html 的 :root 与 [data-theme] 块
// 生成器：tools/gen-design-tokens.mjs（重跑：node tools/gen-design-tokens.mjs）
// ============================================================================
package com.bsfeng.monitor.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme

/**
 * Material3 ColorScheme：每个槽位都由 CSS token 派生，规则表见 tools/gen-design-tokens.mjs 的 SCHEME_RULES。
 * 派生公式：primaryContainer = color-mix(in srgb, accent 14%, surface)；
 *           secondaryContainer = color-mix(in srgb, fg 8%, surface)；
 *           tertiaryContainer = color-mix(in srgb, fg 6%, surface)；
 *           errorContainer = color-mix(in srgb, danger 12%, surface)。
 * 槽位原值见 GeneratedColorSchemeTables（生成物，勿手改）。
 */
internal object GeneratedColorScheme {
    /** apple（默认主题）槽位原值；非 apple 主题只在 PER_THEME 里登记差异槽位。 */
    val BASE: Map<String, String> = GeneratedColorSchemeTables.BASE

    val PER_THEME: Map<String, Map<String, String>> = GeneratedColorSchemeTables.PER_THEME

    fun schemeOf(themeId: String): ColorScheme {
        val over = PER_THEME[themeId]
        val base = BASE
        return lightColorScheme(
            primary = DesignTokenTable.parseCssColor(over?.get("primary") ?: base.getValue("primary")),
            onPrimary = DesignTokenTable.parseCssColor(over?.get("onPrimary") ?: base.getValue("onPrimary")),
            primaryContainer = DesignTokenTable.parseCssColor(over?.get("primaryContainer") ?: base.getValue("primaryContainer")),
            onPrimaryContainer = DesignTokenTable.parseCssColor(over?.get("onPrimaryContainer") ?: base.getValue("onPrimaryContainer")),
            secondary = DesignTokenTable.parseCssColor(over?.get("secondary") ?: base.getValue("secondary")),
            onSecondary = DesignTokenTable.parseCssColor(over?.get("onSecondary") ?: base.getValue("onSecondary")),
            secondaryContainer = DesignTokenTable.parseCssColor(over?.get("secondaryContainer") ?: base.getValue("secondaryContainer")),
            onSecondaryContainer = DesignTokenTable.parseCssColor(over?.get("onSecondaryContainer") ?: base.getValue("onSecondaryContainer")),
            tertiary = DesignTokenTable.parseCssColor(over?.get("tertiary") ?: base.getValue("tertiary")),
            onTertiary = DesignTokenTable.parseCssColor(over?.get("onTertiary") ?: base.getValue("onTertiary")),
            tertiaryContainer = DesignTokenTable.parseCssColor(over?.get("tertiaryContainer") ?: base.getValue("tertiaryContainer")),
            onTertiaryContainer = DesignTokenTable.parseCssColor(over?.get("onTertiaryContainer") ?: base.getValue("onTertiaryContainer")),
            background = DesignTokenTable.parseCssColor(over?.get("background") ?: base.getValue("background")),
            onBackground = DesignTokenTable.parseCssColor(over?.get("onBackground") ?: base.getValue("onBackground")),
            surface = DesignTokenTable.parseCssColor(over?.get("surface") ?: base.getValue("surface")),
            onSurface = DesignTokenTable.parseCssColor(over?.get("onSurface") ?: base.getValue("onSurface")),
            surfaceVariant = DesignTokenTable.parseCssColor(over?.get("surfaceVariant") ?: base.getValue("surfaceVariant")),
            onSurfaceVariant = DesignTokenTable.parseCssColor(over?.get("onSurfaceVariant") ?: base.getValue("onSurfaceVariant")),
            surfaceTint = DesignTokenTable.parseCssColor(over?.get("surfaceTint") ?: base.getValue("surfaceTint")),
            inverseSurface = DesignTokenTable.parseCssColor(over?.get("inverseSurface") ?: base.getValue("inverseSurface")),
            inverseOnSurface = DesignTokenTable.parseCssColor(over?.get("inverseOnSurface") ?: base.getValue("inverseOnSurface")),
            inversePrimary = DesignTokenTable.parseCssColor(over?.get("inversePrimary") ?: base.getValue("inversePrimary")),
            outline = DesignTokenTable.parseCssColor(over?.get("outline") ?: base.getValue("outline")),
            outlineVariant = DesignTokenTable.parseCssColor(over?.get("outlineVariant") ?: base.getValue("outlineVariant")),
            scrim = DesignTokenTable.parseCssColor(over?.get("scrim") ?: base.getValue("scrim")),
            error = DesignTokenTable.parseCssColor(over?.get("error") ?: base.getValue("error")),
            onError = DesignTokenTable.parseCssColor(over?.get("onError") ?: base.getValue("onError")),
            errorContainer = DesignTokenTable.parseCssColor(over?.get("errorContainer") ?: base.getValue("errorContainer")),
            onErrorContainer = DesignTokenTable.parseCssColor(over?.get("onErrorContainer") ?: base.getValue("onErrorContainer")),
        )
    }
}
