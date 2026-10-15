// ============================================================================
// GeneratedTokens.kt —— 生成文件，请勿手改。
// 来源：.open-design/08-prototype.html 的 :root 与 [data-theme] 块
// 生成器：tools/gen-design-tokens.mjs（重跑：node tools/gen-design-tokens.mjs）
// ============================================================================
package com.bsfeng.monitor.ui.theme

import androidx.compose.animation.core.Easing
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit

/**
 * 8 套主题的全部非 Material 语义 token（132 个 getter）。
 * 每个 getter 直接读 DesignTokenTable，因此本文件里没有任何色值/尺寸被手抄。
 * themeId 非法时回落到 apple（DesignTokenTable.raw 内保证）。
 */
@Immutable
class DesignTokens(val themeId: String) {
    val bg: Color get() = DesignTokenTable.colorOf(themeId, "bg")
    val surface: Color get() = DesignTokenTable.colorOf(themeId, "surface")
    val surfaceWarm: Color get() = DesignTokenTable.colorOf(themeId, "surface-warm")
    val fg: Color get() = DesignTokenTable.colorOf(themeId, "fg")
    val fg2: Color get() = DesignTokenTable.colorOf(themeId, "fg-2")
    val muted: Color get() = DesignTokenTable.colorOf(themeId, "muted")
    val meta: Color get() = DesignTokenTable.colorOf(themeId, "meta")
    val border: Color get() = DesignTokenTable.colorOf(themeId, "border")
    val borderSoft: Color get() = DesignTokenTable.colorOf(themeId, "border-soft")
    val accent: Color get() = DesignTokenTable.colorOf(themeId, "accent")
    val accentOn: Color get() = DesignTokenTable.colorOf(themeId, "accent-on")
    val accentHover: Color get() = DesignTokenTable.colorOf(themeId, "accent-hover")
    val accentActive: Color get() = DesignTokenTable.colorOf(themeId, "accent-active")
    val success: Color get() = DesignTokenTable.colorOf(themeId, "success")
    val warn: Color get() = DesignTokenTable.colorOf(themeId, "warn")
    val danger: Color get() = DesignTokenTable.colorOf(themeId, "danger")
    val fontDisplay: String get() = DesignTokenTable.raw(themeId, "font-display")
    val fontBody: String get() = DesignTokenTable.raw(themeId, "font-body")
    val fontMono: String get() = DesignTokenTable.raw(themeId, "font-mono")
    val textXs: TextUnit get() = DesignTokenTable.fp(themeId, "text-xs")
    val textSm: TextUnit get() = DesignTokenTable.fp(themeId, "text-sm")
    val textBase: TextUnit get() = DesignTokenTable.fp(themeId, "text-base")
    val textLg: TextUnit get() = DesignTokenTable.fp(themeId, "text-lg")
    val textXl: TextUnit get() = DesignTokenTable.fp(themeId, "text-xl")
    val text2xl: TextUnit get() = DesignTokenTable.fp(themeId, "text-2xl")
    val text3xl: TextUnit get() = DesignTokenTable.fp(themeId, "text-3xl")
    val text4xl: TextUnit get() = DesignTokenTable.fp(themeId, "text-4xl")
    val leadingBody: Float get() = DesignTokenTable.num(themeId, "leading-body")
    val leadingTight: Float get() = DesignTokenTable.num(themeId, "leading-tight")
    val trackingDisplay: Float get() = DesignTokenTable.num(themeId, "tracking-display")
    val space1: Dp get() = DesignTokenTable.dp(themeId, "space-1")
    val space2: Dp get() = DesignTokenTable.dp(themeId, "space-2")
    val space3: Dp get() = DesignTokenTable.dp(themeId, "space-3")
    val space4: Dp get() = DesignTokenTable.dp(themeId, "space-4")
    val space5: Dp get() = DesignTokenTable.dp(themeId, "space-5")
    val space6: Dp get() = DesignTokenTable.dp(themeId, "space-6")
    val space8: Dp get() = DesignTokenTable.dp(themeId, "space-8")
    val space12: Dp get() = DesignTokenTable.dp(themeId, "space-12")
    val sectionYDesktop: Dp get() = DesignTokenTable.dp(themeId, "section-y-desktop")
    val sectionYTablet: Dp get() = DesignTokenTable.dp(themeId, "section-y-tablet")
    val sectionYPhone: Dp get() = DesignTokenTable.dp(themeId, "section-y-phone")
    val radiusSm: Dp get() = DesignTokenTable.dp(themeId, "radius-sm")
    val radiusMd: Dp get() = DesignTokenTable.dp(themeId, "radius-md")
    val radiusLg: Dp get() = DesignTokenTable.dp(themeId, "radius-lg")
    val radiusPill: Dp get() = DesignTokenTable.dp(themeId, "radius-pill")
    val elevFlat: String get() = DesignTokenTable.raw(themeId, "elev-flat")
    val elevRing: String get() = DesignTokenTable.raw(themeId, "elev-ring")
    val elevRaised: String get() = DesignTokenTable.raw(themeId, "elev-raised")
    val focusRing: String get() = DesignTokenTable.raw(themeId, "focus-ring")
    val motionFast: Int get() = DesignTokenTable.ms(themeId, "motion-fast")
    val motionBase: Int get() = DesignTokenTable.ms(themeId, "motion-base")
    val easeStandard: Easing get() = DesignTokenTable.easing(themeId, "ease-standard")
    val containerMax: Dp get() = DesignTokenTable.dp(themeId, "container-max")
    val containerGutterDesktop: Dp get() = DesignTokenTable.dp(themeId, "container-gutter-desktop")
    val containerGutterTablet: Dp get() = DesignTokenTable.dp(themeId, "container-gutter-tablet")
    val containerGutterPhone: Dp get() = DesignTokenTable.dp(themeId, "container-gutter-phone")
    val pageBg: Color get() = DesignTokenTable.colorOf(themeId, "page-bg")
    val frameA: Color get() = DesignTokenTable.colorOf(themeId, "frame-a")
    val frameB: Color get() = DesignTokenTable.colorOf(themeId, "frame-b")
    val frameEdge: Color get() = DesignTokenTable.colorOf(themeId, "frame-edge")
    val frameInner: Color get() = DesignTokenTable.colorOf(themeId, "frame-inner")
    val frameKey: Color get() = DesignTokenTable.colorOf(themeId, "frame-key")
    val frameShadow: Color get() = DesignTokenTable.colorOf(themeId, "frame-shadow")
    val punch: Color get() = DesignTokenTable.colorOf(themeId, "punch")
    val punchRing: Color get() = DesignTokenTable.colorOf(themeId, "punch-ring")
    val screenW: Dp get() = DesignTokenTable.dp(themeId, "screen-w")
    val screenH: Dp get() = DesignTokenTable.dp(themeId, "screen-h")
    val statusbarH: Dp get() = DesignTokenTable.dp(themeId, "statusbar-h")
    val appbarH: Dp get() = DesignTokenTable.dp(themeId, "appbar-h")
    val tabH: Dp get() = DesignTokenTable.dp(themeId, "tab-h")
    val gestureH: Dp get() = DesignTokenTable.dp(themeId, "gesture-h")
    val tapMin: Dp get() = DesignTokenTable.dp(themeId, "tap-min")
    val rowMinH: Dp get() = DesignTokenTable.dp(themeId, "row-min-h")
    val appBg: Color get() = DesignTokenTable.colorOf(themeId, "app-bg")
    val backdropA: Color get() = DesignTokenTable.colorOf(themeId, "backdrop-a")
    val backdropB: Color get() = DesignTokenTable.colorOf(themeId, "backdrop-b")
    val glassBg: Color get() = DesignTokenTable.colorOf(themeId, "glass-bg")
    val glassBorder: Color get() = DesignTokenTable.colorOf(themeId, "glass-border")
    val glassHighlight: Color get() = DesignTokenTable.colorOf(themeId, "glass-highlight")
    val glassBlur: String get() = DesignTokenTable.raw(themeId, "glass-blur")
    val elev1: String get() = DesignTokenTable.raw(themeId, "elev-1")
    val elev2: String get() = DesignTokenTable.raw(themeId, "elev-2")
    val elev3: String get() = DesignTokenTable.raw(themeId, "elev-3")
    val scrim: Color get() = DesignTokenTable.colorOf(themeId, "scrim")
    val successText: Color get() = DesignTokenTable.colorOf(themeId, "success-text")
    val warnText: Color get() = DesignTokenTable.colorOf(themeId, "warn-text")
    val dangerText: Color get() = DesignTokenTable.colorOf(themeId, "danger-text")
    val accentText: Color get() = DesignTokenTable.colorOf(themeId, "accent-text")
    val incomeText: Color get() = DesignTokenTable.colorOf(themeId, "income-text")
    val expenseText: Color get() = DesignTokenTable.colorOf(themeId, "expense-text")
    val chartSeq: Color get() = DesignTokenTable.colorOf(themeId, "chart-seq")
    val chart1: Color get() = DesignTokenTable.colorOf(themeId, "chart-1")
    val chart2: Color get() = DesignTokenTable.colorOf(themeId, "chart-2")
    val chart3: Color get() = DesignTokenTable.colorOf(themeId, "chart-3")
    val chart4: Color get() = DesignTokenTable.colorOf(themeId, "chart-4")
    val chart5: Color get() = DesignTokenTable.colorOf(themeId, "chart-5")
    val chart6: Color get() = DesignTokenTable.colorOf(themeId, "chart-6")
    val chart7: Color get() = DesignTokenTable.colorOf(themeId, "chart-7")
    val chart8: Color get() = DesignTokenTable.colorOf(themeId, "chart-8")
    val chart9: Color get() = DesignTokenTable.colorOf(themeId, "chart-9")
    val chart10: Color get() = DesignTokenTable.colorOf(themeId, "chart-10")
    val chart11: Color get() = DesignTokenTable.colorOf(themeId, "chart-11")
    val chart12: Color get() = DesignTokenTable.colorOf(themeId, "chart-12")
    val chart13: Color get() = DesignTokenTable.colorOf(themeId, "chart-13")
    val chart14: Color get() = DesignTokenTable.colorOf(themeId, "chart-14")
    val chart15: Color get() = DesignTokenTable.colorOf(themeId, "chart-15")
    val chart16: Color get() = DesignTokenTable.colorOf(themeId, "chart-16")
    val fabSize: Dp get() = DesignTokenTable.dp(themeId, "fab-size")
    val iconSize: Dp get() = DesignTokenTable.dp(themeId, "icon-size")
    val switchW: Dp get() = DesignTokenTable.dp(themeId, "switch-w")
    val switchTrackW: Dp get() = DesignTokenTable.dp(themeId, "switch-track-w")
    val switchTrackH: Dp get() = DesignTokenTable.dp(themeId, "switch-track-h")
    val knobSize: Dp get() = DesignTokenTable.dp(themeId, "knob-size")
    val ringSize: Dp get() = DesignTokenTable.dp(themeId, "ring-size")
    val heatH: Dp get() = DesignTokenTable.dp(themeId, "heat-h")
    val sparkH: Dp get() = DesignTokenTable.dp(themeId, "spark-h")
    val chartAxisFs: TextUnit get() = DesignTokenTable.fp(themeId, "chart-axis-fs")
    val chartH: Dp get() = DesignTokenTable.dp(themeId, "chart-h")
    val dotSize: Dp get() = DesignTokenTable.dp(themeId, "dot-size")
    val durPress: Int get() = DesignTokenTable.ms(themeId, "dur-press")
    val durState: Int get() = DesignTokenTable.ms(themeId, "dur-state")
    val durOverlayIn: Int get() = DesignTokenTable.ms(themeId, "dur-overlay-in")
    val durOverlayOut: Int get() = DesignTokenTable.ms(themeId, "dur-overlay-out")
    val durPage: Int get() = DesignTokenTable.ms(themeId, "dur-page")
    val durChart: Int get() = DesignTokenTable.ms(themeId, "dur-chart")
    val durChartFast: Int get() = DesignTokenTable.ms(themeId, "dur-chart-fast")
    val stagger: Int get() = DesignTokenTable.ms(themeId, "stagger")
    val easeSpring: Easing get() = DesignTokenTable.easing(themeId, "ease-spring")
    val easeOut: Easing get() = DesignTokenTable.easing(themeId, "ease-out")
    val easeIn: Easing get() = DesignTokenTable.easing(themeId, "ease-in")
    val splashBg: Color get() = DesignTokenTable.colorOf(themeId, "splash-bg")
    val splashInk: Color get() = DesignTokenTable.colorOf(themeId, "splash-ink")
}

/**
 * Material3 ColorScheme：每个槽位都由 CSS token 派生，规则表见 tools/gen-design-tokens.mjs 的 SCHEME_RULES。
 * 派生公式：primaryContainer = color-mix(in srgb, accent 14%, surface)；
 *           secondaryContainer = color-mix(in srgb, fg 8%, surface)；
 *           tertiaryContainer = color-mix(in srgb, fg 6%, surface)；
 *           errorContainer = color-mix(in srgb, danger 12%, surface)。
 */
internal object GeneratedColorScheme {
    /** apple（默认主题）槽位原值；非 apple 主题只在 PER_THEME 里登记差异槽位。 */
    val BASE: Map<String, String> = mapOf(
        "primary" to "#0071e3",
        "onPrimary" to "#ffffff",
        "primaryContainer" to "#d3e3f4",
        "onPrimaryContainer" to "#1d1d1f",
        "secondary" to "#424245",
        "onSecondary" to "#ffffff",
        "secondaryContainer" to "#e4e4e6",
        "onSecondaryContainer" to "#1d1d1f",
        "tertiary" to "#6e6e73",
        "onTertiary" to "#ffffff",
        "tertiaryContainer" to "#e8e8ea",
        "onTertiaryContainer" to "#1d1d1f",
        "background" to "#ffffff",
        "onBackground" to "#1d1d1f",
        "surface" to "#f5f5f7",
        "onSurface" to "#1d1d1f",
        "surfaceVariant" to "#fbfbfd",
        "onSurfaceVariant" to "#6e6e73",
        "surfaceTint" to "#0071e3",
        "inverseSurface" to "#1d1d1f",
        "inverseOnSurface" to "#ffffff",
        "inversePrimary" to "#0077ed",
        "outline" to "#d2d2d7",
        "outlineVariant" to "#e8e8ed",
        "scrim" to "rgba(29, 29, 31, 0.34)",
        "error" to "#dc2626",
        "onError" to "#ffffff",
        "errorContainer" to "#f2dcde",
        "onErrorContainer" to "#992324",
    )

    val PER_THEME: Map<String, Map<String, String>> = mapOf(
        "glassmorphism" to mapOf(
            "primary" to "#3e6ec9",
            "primaryContainer" to "rgba(220, 229, 245, 0.7764)",
            "onPrimaryContainer" to "#102033",
            "secondary" to "#34465f",
            "onSecondary" to "#eef6ff",
            "secondaryContainer" to "rgba(230, 232, 234, 0.7608)",
            "onSecondaryContainer" to "#102033",
            "tertiary" to "#60708a",
            "onTertiary" to "#eef6ff",
            "tertiaryContainer" to "rgba(236, 237, 239, 0.7556)",
            "onTertiaryContainer" to "#102033",
            "background" to "#eef6ff",
            "onBackground" to "#102033",
            "surface" to "rgba(255, 255, 255, 0.74)",
            "onSurface" to "#102033",
            "surfaceVariant" to "rgba(238, 246, 255, 0.72)",
            "onSurfaceVariant" to "#60708a",
            "surfaceTint" to "#3e6ec9",
            "inverseSurface" to "#102033",
            "inverseOnSurface" to "#eef6ff",
            "inversePrimary" to "#3662b4",
            "outline" to "rgba(255, 255, 255, 0.64)",
            "outlineVariant" to "rgba(255, 255, 255, 0.38)",
            "scrim" to "rgba(16, 32, 51, 0.34)",
            "error" to "#ef4444",
            "onError" to "#eef6ff",
            "errorContainer" to "rgba(253, 226, 226, 0.7712)",
            "onErrorContainer" to "#a1373e",
        ),
        "notion" to mapOf(
            "primary" to "#0075de",
            "primaryContainer" to "#d4e3f1",
            "onPrimaryContainer" to "rgba(0, 0, 0, 0.95)",
            "secondary" to "#31302e",
            "secondaryContainer" to "rgba(227, 226, 225, 0.996)",
            "onSecondaryContainer" to "rgba(0, 0, 0, 0.95)",
            "tertiary" to "#615d59",
            "tertiaryContainer" to "rgba(232, 231, 230, 0.997)",
            "onTertiaryContainer" to "rgba(0, 0, 0, 0.95)",
            "onBackground" to "rgba(0, 0, 0, 0.95)",
            "surface" to "#f6f5f4",
            "onSurface" to "rgba(0, 0, 0, 0.95)",
            "surfaceVariant" to "#f6f5f4",
            "onSurfaceVariant" to "#615d59",
            "surfaceTint" to "#0075de",
            "inverseSurface" to "rgba(0, 0, 0, 0.95)",
            "inversePrimary" to "#005bab",
            "outline" to "rgba(0, 0, 0, 0.1)",
            "outlineVariant" to "rgba(0, 0, 0, 0.06)",
            "scrim" to "rgba(0, 0, 0, 0.323)",
            "errorContainer" to "#f3dcdb",
            "onErrorContainer" to "rgba(146, 25, 25, 0.9825)",
        ),
        "stripe" to mapOf(
            "primary" to "#533afd",
            "primaryContainer" to "#e7e3ff",
            "onPrimaryContainer" to "#061b31",
            "secondary" to "#273951",
            "secondaryContainer" to "#ebedef",
            "onSecondaryContainer" to "#061b31",
            "tertiary" to "#64748d",
            "tertiaryContainer" to "#f0f1f3",
            "onTertiaryContainer" to "#061b31",
            "onBackground" to "#061b31",
            "surface" to "#ffffff",
            "onSurface" to "#061b31",
            "surfaceVariant" to "#f6f9fc",
            "onSurfaceVariant" to "#64748d",
            "surfaceTint" to "#533afd",
            "inverseSurface" to "#061b31",
            "inversePrimary" to "#4434d4",
            "outline" to "#e5edf5",
            "outlineVariant" to "#e5edf5",
            "scrim" to "rgba(6, 27, 49, 0.34)",
            "error" to "#ea2261",
            "errorContainer" to "#fce4ec",
            "onErrorContainer" to "#9a2050",
        ),
        "claude" to mapOf(
            "primary" to "#aa5538",
            "onPrimary" to "#faf9f5",
            "primaryContainer" to "#efe2db",
            "onPrimaryContainer" to "#141413",
            "secondary" to "#3d3d3a",
            "onSecondary" to "#f5f4ed",
            "secondaryContainer" to "#e8e7e3",
            "onSecondaryContainer" to "#141413",
            "tertiary" to "#5e5d59",
            "onTertiary" to "#f5f4ed",
            "tertiaryContainer" to "#ecebe7",
            "onTertiaryContainer" to "#141413",
            "background" to "#f5f4ed",
            "onBackground" to "#141413",
            "surface" to "#faf9f5",
            "onSurface" to "#141413",
            "surfaceVariant" to "#e8e6dc",
            "onSurfaceVariant" to "#5e5d59",
            "surfaceTint" to "#aa5538",
            "inverseSurface" to "#141413",
            "inverseOnSurface" to "#f5f4ed",
            "inversePrimary" to "#984b31",
            "outline" to "#f0eee6",
            "outlineVariant" to "#e8e6dc",
            "scrim" to "rgba(20, 20, 19, 0.34)",
            "error" to "#b53333",
            "onError" to "#f5f4ed",
            "errorContainer" to "#f2e1de",
            "onErrorContainer" to "#7d2828",
        ),
        "ibm" to mapOf(
            "primary" to "#0f62fe",
            "primaryContainer" to "#d4e0f5",
            "onPrimaryContainer" to "#161616",
            "secondary" to "#525252",
            "secondaryContainer" to "#e2e2e2",
            "onSecondaryContainer" to "#161616",
            "tertiary" to "#6f6f6f",
            "tertiaryContainer" to "#e7e7e7",
            "onTertiaryContainer" to "#161616",
            "onBackground" to "#161616",
            "surface" to "#f4f4f4",
            "onSurface" to "#161616",
            "surfaceVariant" to "#edf5ff",
            "onSurfaceVariant" to "#6f6f6f",
            "surfaceTint" to "#0f62fe",
            "inverseSurface" to "#161616",
            "inversePrimary" to "#0c57e3",
            "outline" to "#c6c6c6",
            "outlineVariant" to "#e0e0e0",
            "scrim" to "rgba(22, 22, 22, 0.34)",
            "error" to "#da1e28",
            "errorContainer" to "#f1dadc",
            "onErrorContainer" to "#951b22",
        ),
        "kami" to mapOf(
            "primary" to "#1b365d",
            "onPrimary" to "#faf9f5",
            "primaryContainer" to "#dbdee0",
            "onPrimaryContainer" to "#141413",
            "secondary" to "#3d3d3a",
            "onSecondary" to "#f5f4ed",
            "secondaryContainer" to "#e8e7e3",
            "onSecondaryContainer" to "#141413",
            "tertiary" to "#504e49",
            "onTertiary" to "#f5f4ed",
            "tertiaryContainer" to "#ecebe7",
            "onTertiaryContainer" to "#141413",
            "background" to "#f5f4ed",
            "onBackground" to "#141413",
            "surface" to "#faf9f5",
            "onSurface" to "#141413",
            "surfaceVariant" to "#e8e6dc",
            "onSurfaceVariant" to "#504e49",
            "surfaceTint" to "#1b365d",
            "inverseSurface" to "#141413",
            "inverseOnSurface" to "#f5f4ed",
            "inversePrimary" to "#1b365d",
            "outline" to "#e8e6dc",
            "outlineVariant" to "#e5e3d8",
            "scrim" to "rgba(20, 20, 19, 0.34)",
            "error" to "#8a3a30",
            "onError" to "#f5f4ed",
            "errorContainer" to "#ede2dd",
            "onErrorContainer" to "#612d26",
        ),
        "bento" to mapOf(
            "primary" to "#2563eb",
            "primaryContainer" to "#e0e9fc",
            "onPrimaryContainer" to "#101828",
            "secondary" to "#344054",
            "onSecondary" to "#f5f8ff",
            "secondaryContainer" to "#ecedee",
            "onSecondaryContainer" to "#101828",
            "tertiary" to "#667085",
            "onTertiary" to "#f5f8ff",
            "tertiaryContainer" to "#f1f1f2",
            "onTertiaryContainer" to "#101828",
            "background" to "#f5f8ff",
            "onBackground" to "#101828",
            "surface" to "#ffffff",
            "onSurface" to "#101828",
            "surfaceVariant" to "#eaf1ff",
            "onSurfaceVariant" to "#667085",
            "surfaceTint" to "#2563eb",
            "inverseSurface" to "#101828",
            "inverseOnSurface" to "#f5f8ff",
            "inversePrimary" to "#2058d2",
            "outline" to "#d7e0ef",
            "outlineVariant" to "#edf2f8",
            "scrim" to "rgba(16, 24, 40, 0.34)",
            "error" to "#ef4444",
            "onError" to "#f5f8ff",
            "errorContainer" to "#fde9e9",
            "onErrorContainer" to "#a1353a",
        ),
    )

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

/** 旧语义名 → 新 token 名 对照表（W1/W2/W3 照此替换调用点；完整表见 tools/LEGACY-TOKEN-MAP.md）。 */
internal object LegacyTokenMap {
    class Entry(val legacy: String, val token: String, val note: String)

    val TABLE: List<Entry> = listOf(
        Entry("IOSColors.blue", "accent", "主强调色（唯一持久色彩动作）"),
        Entry("IOSColors.red", "danger", "危险语义色"),
        Entry("IOSColors.green", "success", "成功语义色（实时事件流的活跃点）"),
        Entry("IOSColors.orange", "warn", "警告语义色"),
        Entry("IOSColors.yellow", "chart-4", "中性图表色阶第 4 级"),
        Entry("IOSColors.lightBlue", "chart-5", "中性图表色阶第 5 级"),
        Entry("IOSColors.purple", "chart-3", "中性图表色阶第 3 级"),
        Entry("IOSColors.pink", "chart-2", "中性图表色阶第 2 级"),
        Entry("IOSColors.indigo", "chart-6", "中性图表色阶第 6 级（原 ColorScheme.secondary）"),
        Entry("IOSColors.teal", "chart-7", "中性图表色阶第 7 级（原 ColorScheme.tertiary）"),
        Entry("IOSColors.gray", "muted", "次要文本 / 中性填充"),
        Entry("IOSColors.lightGray", "border-soft", "进度条轨道 / chip 底色"),
        Entry("IOSColors.systemBackground", "bg", "页面底色"),
        Entry("IOSColors.groupedBackground", "surface", "卡片底色"),
        Entry("FinanceColors.expense", "danger", "填充态；文本态用 expense-text"),
        Entry("FinanceColors.income", "success", "填充态；文本态用 income-text"),
        Entry("FinanceColors.budget", "accent", "预算进度强调色"),
        Entry("RecordType.SCREEN", "chart-1", "事件类型色"),
        Entry("RecordType.APP_USAGE", "chart-2", "事件类型色"),
        Entry("RecordType.CHARGING", "warn", "事件类型色"),
        Entry("RecordType.BATTERY", "warn", "事件类型色"),
        Entry("RecordType.WIFI", "accent", "事件类型色"),
        Entry("RecordType.AUDIO", "chart-3", "事件类型色"),
        Entry("RecordType.PACKAGE", "chart-4", "事件类型色"),
        Entry("RecordType.POWER_SAVE", "muted", "事件类型色"),
        Entry("RecordType.AIRPLANE", "chart-5", "事件类型色"),
        Entry("RecordType.PHONE_STATE", "chart-6", "事件类型色"),
        Entry("RecordType.LOCATION", "success", "事件类型色"),
        Entry("categoryPalette[0]", "chart-1", "循环色阶"),
        Entry("categoryPalette[1]", "chart-2", "循环色阶"),
        Entry("categoryPalette[2]", "chart-3", "循环色阶"),
        Entry("categoryPalette[3]", "chart-4", "循环色阶"),
        Entry("categoryPalette[4]", "chart-5", "循环色阶"),
        Entry("categoryPalette[5]", "chart-6", "循环色阶"),
        Entry("categoryPalette[6]", "chart-7", "循环色阶"),
        Entry("categoryPalette[7]", "chart-8", "循环色阶"),
        Entry("categoryPalette[8]", "chart-1", "循环色阶"),
        Entry("categoryPalette[9]", "chart-2", "循环色阶"),
        Entry("categoryPalette[10]", "chart-3", "循环色阶"),
        Entry("categoryPalette[11]", "chart-4", "循环色阶"),
        Entry("categoryPalette[12]", "chart-5", "循环色阶"),
        Entry("categoryPalette[13]", "chart-6", "循环色阶"),
        Entry("categoryPalette[14]", "chart-7", "循环色阶"),
        Entry("FinanceStatsScreen.legend[0]", "chart-1", "循环色阶（W2 删私有副本）"),
        Entry("FinanceStatsScreen.legend[1]", "chart-2", "循环色阶（W2 删私有副本）"),
        Entry("FinanceStatsScreen.legend[2]", "chart-3", "循环色阶（W2 删私有副本）"),
        Entry("FinanceStatsScreen.legend[3]", "chart-4", "循环色阶（W2 删私有副本）"),
        Entry("FinanceStatsScreen.legend[4]", "chart-5", "循环色阶（W2 删私有副本）"),
        Entry("FinanceStatsScreen.legend[5]", "chart-6", "循环色阶（W2 删私有副本）"),
        Entry("FinanceStatsScreen.legend[6]", "chart-7", "循环色阶（W2 删私有副本）"),
        Entry("FinanceStatsScreen.legend[7]", "chart-8", "循环色阶（W2 删私有副本）"),
        Entry("FinanceStatsScreen.legend[8]", "chart-1", "循环色阶（W2 删私有副本）"),
    )
}
