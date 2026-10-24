// ============================================================================
// GeneratedTokens.kt —— 生成文件，请勿手改。
// 职责：DesignTokens（132 个 token getter，纯转发到 DesignTokenTable）与 LegacyTokenMap（旧语义名对照表）。
// 来源：.open-design/08-prototype.html 的 :root 与 [data-theme] 块
// 生成器：tools/gen-design-tokens.mjs（重跑：node tools/gen-design-tokens.mjs）
// ============================================================================
package com.bsfeng.monitor.ui.theme

import androidx.compose.animation.core.Easing
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
