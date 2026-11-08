package com.bsfeng.monitor.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * 桌面小组件（6 个 Glance App Widget）的固定色板。
 *
 * 为什么单独一套、不并进 DesignTokenTable：那两套是 **App 内**的 8 套主题，
 * 而组件在桌面上只跟随系统深浅色两档（Glance 的 `ColorProvider(day, night)`）；
 * 一旦混用，「App 里选深色主题 + 系统是浅色」时桌面卡片会和 App 内颜色对不上。
 *
 * 色值来源：`.open-design/widgets-r2/00-decisions.md` 第 2 节 token 表（C 版定稿：每卡一个色相 +
 * 极浅 tint 卡底 + 内嵌同色系分区面 + 实色图标块）。所有值都是算好的**实色**：
 * Glance 的 RemoteViews 不叠 alpha，浅色档不是「白底 + 透明叠色」，而是直接给混好的 hex。
 *
 * 放在 theme 包：这里是全项目唯一允许出现色值的目录（构建期 checkUiStyle 只放行 ui/theme），
 * 组件渲染侧只负责把它绑成 day/night 两档（见 widget/WidgetStyle.kt）。
 */
object WidgetPalette {

    // ===================== 每卡一套 token（5 面 × 浅深） =====================

    /**
     * 一张卡的面色：卡底 / 内嵌分区面 / 图标块 / 进度条填充 / 进度条轨道 / 描边。
     *
     * [edge] 不在 00-decisions 的 5 列 token 表里，但原型每张卡都有自己的描边与分隔线色
     * （`.w-*` 的 `--w-edge`）；浅 tint 卡底上的分隔线必须同色系，用全局描边会发灰。
     */
    data class CardTokens(
        val surfaceDay: Color,
        val surfaceNight: Color,
        val sectionDay: Color,
        val sectionNight: Color,
        val iconDay: Color,
        val iconNight: Color,
        val barDay: Color,
        val barNight: Color,
        val trackDay: Color,
        val trackNight: Color,
        val edgeDay: Color,
        val edgeNight: Color,
    )

    /** 记账：蓝。 */
    val finance = CardTokens(
        surfaceDay = Color(0xFFF4F8FF),
        surfaceNight = Color(0xFF16223A),
        sectionDay = Color(0xFFE2ECFD),
        sectionNight = Color(0xFF1D2C49),
        iconDay = Color(0xFF2F6BFF),
        iconNight = Color(0xFF3D78FF),
        barDay = Color(0xFF6A93F0),
        barNight = Color(0xFF5285F0),
        trackDay = Color(0xFFD9E5FA),
        trackNight = Color(0xFF22314F),
        edgeDay = Color(0xFFDDE7FA),
        edgeNight = Color(0xFF27385A),
    )

    /** 额度：紫。 */
    val quota = CardTokens(
        surfaceDay = Color(0xFFF7F4FF),
        surfaceNight = Color(0xFF1E1934),
        sectionDay = Color(0xFFE7DFFC),
        sectionNight = Color(0xFF2A2247),
        iconDay = Color(0xFF5B4BE0),
        iconNight = Color(0xFF6B58E8),
        barDay = Color(0xFF8D7AE8),
        barNight = Color(0xFF7A68EA),
        trackDay = Color(0xFFE3DBF8),
        trackNight = Color(0xFF2B2447),
        edgeDay = Color(0xFFE2DAF8),
        edgeNight = Color(0xFF382E5C),
    )

    /** 监控健康：绿。 */
    val health = CardTokens(
        surfaceDay = Color(0xFFF2FBF5),
        surfaceNight = Color(0xFF13281F),
        sectionDay = Color(0xFFDAF2E4),
        sectionNight = Color(0xFF1A382C),
        iconDay = Color(0xFF0E9F5B),
        iconNight = Color(0xFF12A862),
        barDay = Color(0xFF4FBF8B),
        barNight = Color(0xFF33AB74),
        trackDay = Color(0xFFD3EDDF),
        trackNight = Color(0xFF1B382C),
        edgeDay = Color(0xFFD5EDDF),
        edgeNight = Color(0xFF234638),
    )

    /** 广告跳过：橙。 */
    val adskip = CardTokens(
        surfaceDay = Color(0xFFFFF8F0),
        surfaceNight = Color(0xFF2A1F13),
        sectionDay = Color(0xFFFCE2CD),
        sectionNight = Color(0xFF3A2A1B),
        iconDay = Color(0xFFCF5F25),
        iconNight = Color(0xFFD9691F),
        barDay = Color(0xFFE29160),
        barNight = Color(0xFFCC7D43),
        trackDay = Color(0xFFF8E0CB),
        trackNight = Color(0xFF3B2B1D),
        edgeDay = Color(0xFFF7DFC9),
        edgeNight = Color(0xFF4A3624),
    )

    /** 传送：青。 */
    val transfer = CardTokens(
        surfaceDay = Color(0xFFF1FBFC),
        surfaceNight = Color(0xFF122730),
        sectionDay = Color(0xFFD8EFF2),
        sectionNight = Color(0xFF193A44),
        iconDay = Color(0xFF0F97AC),
        iconNight = Color(0xFF11A2B8),
        barDay = Color(0xFF40B3C2),
        barNight = Color(0xFF33A2B6),
        trackDay = Color(0xFFD2EDF1),
        trackNight = Color(0xFF173740),
        edgeDay = Color(0xFFD2EBEF),
        edgeNight = Color(0xFF22474F),
    )

    /** 快捷记一笔：实色主色块（深浅色同值），白图标白字。 */
    val quickAdd = Color(0xFF007AFF)

    /** 主色块 / 图标块上的前景（白）：对 5 个色相与 #007AFF 的对比度都够。 */
    val onHue = Color(0xFFFFFFFF)

    // ===================== 全局 token（浅深两档） =====================

    /** 主文字（= 主数值颜色）：浅 #0E1A2E 对卡底 ≥12:1；深 #FFFFFF。 */
    val textDay = Color(0xFF0E1A2E)
    val textNight = Color(0xFFFFFFFF)

    /** 次要文字（眉标 / 标签 / 分区标签）：浅 #5C6B85（≥5.2:1）、深 #A2B1CB。 */
    val mutedDay = Color(0xFF5C6B85)
    val mutedNight = Color(0xFFA2B1CB)

    /** 卡描边 / 分隔线：浅 #DFE6F2、深 #2B3550（比深色卡底亮一档）。 */
    val borderDay = Color(0xFFDFE6F2)
    val borderNight = Color(0xFF2B3550)

    /** 短语 pill 的底：浅白、深 #16203A（比卡底再深一档，让 pill 浮起来）。 */
    val tagDay = Color(0xFFFFFFFF)
    val tagNight = Color(0xFF16203A)

    /** 正常 / 监控中 / 接收中（语义绿，已按「文字色」加深，不直接用系统原色）。 */
    val okDay = Color(0xFF0B7A3B)
    val okNight = Color(0xFF4ADE9B)

    /** 异常 / 未运行（语义红）。 */
    val badDay = Color(0xFFBF3A2E)
    val badNight = Color(0xFFFF7A70)

    /** 额度告警 / 心跳停更（语义橙）。 */
    val warnDay = Color(0xFFA9601F)
    val warnNight = Color(0xFFFFBE6B)

    /** 正常额度的百分比文字（= 额度卡色相，浅色档压深到可读）。 */
    val accentDay = Color(0xFF2F6BFF)
    val accentNight = Color(0xFF6F9DFF)
}
