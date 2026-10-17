package com.bsfeng.monitor.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import com.bsfeng.monitor.domain.model.RecordType

/**
 * 非 Material 语义 token 的派生入口（图表色阶 / 分类圆点 / 事件类型色 / 热力 / 迷你走势 / 圆点 /
 * 收入 / 支出 / 玻璃配方）。所有值都来自 DesignTokenTable，本文件不含任何色值。
 */

/**
 * chart-1..chart-16：**分类色板**（跨主题固定，切主题时同一分类保持同色）。
 * 供扇形图分片 / 分类圆点 / 分类图例 / 分类网格取色；按分类在 category 表中的
 * sort_order 取 index % 16，可保证相邻分类不重色。
 */
val DesignTokens.chartRamp: List<Color>
    get() = listOf(
        chart1, chart2, chart3, chart4, chart5, chart6, chart7, chart8,
        chart9, chart10, chart11, chart12, chart13, chart14, chart15, chart16,
    )

/** 分类圆点色：按下标循环取分类色板（替代旧 categoryPalette[i]）。 */
fun DesignTokens.categoryDot(index: Int): Color {
    val ramp = chartRamp
    return ramp[((index % ramp.size) + ramp.size) % ramp.size]
}

/** 旧 categoryPalette 的等价物：16 槽循环色阶，供仍按下标取色的调用点过渡。 */
val DesignTokens.categoryRamp: List<Color>
    get() = (0 until 16).map { categoryDot(it) }

/** 6 级热力透明度：对应原型 .h0..h5 的 8/20/36/54/72/90%。 */
private val HEAT_ALPHA = listOf(0.08f, 0.20f, 0.36f, 0.54f, 0.72f, 0.90f)

/**
 * 热力条色阶：**序列色**（--chart-seq）的 8/20/36/54/72/90% 透明阶梯，越活跃越深。
 * 0 值格用最低一档（8%），在卡片上几乎不可见 —— 与原型 .h0 一致。
 */
val DesignTokens.heatRamp: List<Color>
    get() = HEAT_ALPHA.map { chartSeq.copy(alpha = chartSeq.alpha * it) }

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

/**
 * 浮层面板底色：surface-warm → surface → bg 依次合成，保证 **100% 不透明**。
 *
 * 为什么不能只写 surfaceWarm：玻璃拟态主题的 surface-warm 是 rgba(238,246,255,.72)、
 * surface 是 rgba(255,255,255,.74)，两层叠完 alpha 仍有 0.927，会轻微透出下层文字。
 * 再压一层不透明的 bg（8 套主题的 bg 都是实色）才是真的实底。
 */
val DesignTokens.opaquePanel: Color
    get() = surfaceWarm.compositeOver(surface).compositeOver(bg)

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
