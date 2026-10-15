package com.bsfeng.monitor.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import com.bsfeng.monitor.domain.model.RecordType

/**
 * 非 Material 语义 token 的派生入口（图表色阶 / 分类圆点 / 事件类型色 / 热力 / 迷你走势 / 圆点 /
 * 收入 / 支出 / 玻璃配方）。所有值都来自 DesignTokenTable，本文件不含任何色值。
 */

/** chart-1..chart-8：中性图表色阶（契约「图表不占用每屏 ≤2 次强调色预算」）。 */
val DesignTokens.chartRamp: List<Color>
    get() = listOf(chart1, chart2, chart3, chart4, chart5, chart6, chart7, chart8)

/** 分类圆点色：按下标循环取中性色阶（替代旧 categoryPalette[i]）。 */
fun DesignTokens.categoryDot(index: Int): Color {
    val ramp = chartRamp
    return ramp[((index % ramp.size) + ramp.size) % ramp.size]
}

/** 旧 categoryPalette 的等价物：15 槽循环色阶，供仍按下标取色的调用点过渡。 */
val DesignTokens.categoryRamp: List<Color>
    get() = (0 until 15).map { categoryDot(it) }

/** 6 级热力透明度：对应原型 .h0..h5（00-shell.html:496-501）。 */
private val HEAT_ALPHA = listOf(0.06f, 0.15f, 0.28f, 0.44f, 0.62f, 0.80f)

/** 热力条色阶：--fg 6/15/28/44/62/80%，越活跃越深（原型 .h0..h5）。 */
val DesignTokens.heatRamp: List<Color>
    get() = HEAT_ALPHA.map { fg.copy(alpha = fg.alpha * it) }

/** 热力条单格颜色：intensity 0..levels-1，越大越深。 */
fun DesignTokens.heatCell(intensity: Int, levels: Int = 6): Color {
    val ramp = heatRamp
    val steps = (levels - 1).coerceAtLeast(1)
    val idx = (intensity.coerceIn(0, levels - 1) * (ramp.size - 1)) / steps
    return ramp[idx.coerceIn(0, ramp.size - 1)]
}

/** 事件类型色（替代 RecordType.toEventColor()）。 */
fun DesignTokens.eventColor(type: RecordType): Color = when (type) {
    RecordType.SCREEN -> chart1
    RecordType.APP_USAGE -> chart2
    RecordType.CHARGING -> warn
    RecordType.BATTERY -> warn
    RecordType.WIFI -> accent
    RecordType.AUDIO -> chart3
    RecordType.PACKAGE -> chart4
    RecordType.POWER_SAVE -> muted
    RecordType.AIRPLANE -> chart5
    RecordType.PHONE_STATE -> chart6
    RecordType.LOCATION -> success
}

/** 强色填充上的文字色（替代散落的 Color.White）。 */
val DesignTokens.onAccent: Color get() = accentOn

/** 强调色弱化填充：color-mix(in srgb, accent p%, surface)，对齐 Material container 槽位。 */
fun DesignTokens.accentSoftFill(percent: Int = 14): Color =
    lerp(surface, accent, percent.coerceIn(0, 100) / 100f)

/** 中性半透明覆盖：等价 CSS color-mix(in srgb, var(--fg) p%, transparent)（原型 chip 7 / 描边 24 / badge 9 / 骨架 6·13）。 */
fun DesignTokens.fgFade(percent: Int): Color =
    fg.copy(alpha = fg.alpha * (percent.coerceIn(0, 100) / 100f))

/** 强调色半透明覆盖：等价 CSS color-mix(in srgb, var(--accent) p%, transparent)（原型 Tab 选中胶囊 14%）。 */
fun DesignTokens.accentFade(percent: Int): Color =
    accent.copy(alpha = accent.alpha * (percent.coerceIn(0, 100) / 100f))

/** 迷你走势条高度（--spark-h）。 */
val DesignTokens.sparkHeight get() = sparkH

/** 热力条高度（--heat-h）。 */
val DesignTokens.heatHeight get() = heatH

/** 圆点直径（--dot-size）。 */
val DesignTokens.dotDiameter get() = dotSize

/** 旧语义名 → 新 token 名 的完整对照表（生成物，供 W1/W2/W3 替换调用点）。 */
internal object DesignTokenCatalog {
    val legacyTable: List<LegacyTokenMap.Entry> get() = LegacyTokenMap.TABLE

    /** 旧语义名（如 "IOSColors.green"）对应的新 token 名（如 "success"）；未登记返回 null。 */
    fun tokenFor(legacyName: String): String? =
        LegacyTokenMap.TABLE.firstOrNull { it.legacy == legacyName }?.token

    /** 生成物自检：Kotlin 侧登记的 CSS 变量数必须与 CSS 变量数一致。 */
    fun cssVariableCount(): Int = DesignTokenTable.CSS_VARIABLE_COUNT

    fun semanticCount(): Int = DesignTokenTable.SEMANTIC_COUNT

    /** 运行时读取 token 原值（自验用）。 */
    fun dump(themeId: String, names: List<String>): List<String> =
        names.map { it + " [" + DesignTokenTable.kind(it).name + "] = " + DesignTokenTable.raw(themeId, it) }

    /** 运行时 dump 某主题全部 token（自验 ④：CSS 原文值 vs 最终生效的运行时值）。 */
    fun dumpAll(themeId: String): List<String> =
        DesignTokenTable.SPECS.keys.sorted().map {
            it + " [" + DesignTokenTable.kind(it).name + "] = " + DesignTokenTable.raw(themeId, it)
        }
}
