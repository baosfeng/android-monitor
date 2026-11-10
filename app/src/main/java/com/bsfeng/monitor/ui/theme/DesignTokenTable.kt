// ============================================================================
// DesignTokenTable.kt —— 生成文件，请勿手改。
// 职责：全部设计令牌的汇总入口：常量、SPECS 合并（数据在 DesignTokenSpecs1..N.kt）、取值与解析 API。
// 来源：.open-design/08-prototype.html 的 :root 与 [data-theme] 块
// 生成器：tools/gen-design-tokens.mjs（重跑：node tools/gen-design-tokens.mjs）
// ============================================================================
package com.bsfeng.monitor.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.util.concurrent.ConcurrentHashMap

/** CSS token 的类型。 */
internal enum class TokenKind { COLOR, LENGTH, FONT_SIZE, DURATION, NUMBER, EM, EASING, SHADOW, FILTER, FONT, RAW }

/** 单个 CSS 变量：名字 + 类型 + 基础值（apple）+ 其余主题的覆盖值（无覆盖表示与基础值同值）。 */
internal class CssToken(val name: String, val kind: TokenKind, val base: String, val overrides: Map<String, String>) {
    fun raw(themeId: String): String = overrides[themeId] ?: base
}

internal object DesignTokenTable {
    const val DEFAULT_THEME: String = "apple"
    /** CSS 变量总数（00-tokens.css 的 :root 声明数；extra 文件只复述同名槽位，不新增名字）。 */
    const val CSS_VARIABLE_COUNT: Int = 271
    /** 其中语义槽位数（其余为 7 套主题的色值命名空间槽位）。 */
    const val SEMANTIC_COUNT: Int = 160
    val THEME_IDS: List<String> = listOf("apple", "glassmorphism", "notion", "stripe", "claude", "ibm", "kami", "bento")
    /** themeId 合法性判定集合：避免 raw() 每次都做 List.contains 线性扫描。 */
    private val THEME_ID_SET: Set<String> = THEME_IDS.toHashSet()
    /** 颜色解析缓存：themeId → (token 名 → Color)，每个 token 每主题只解析一次字符串。 */
    private val colorCache = ConcurrentHashMap<String, ConcurrentHashMap<String, Color>>()
    /** 缓动曲线缓存：themeId → (token 名 → Easing)，避免每次读 token 都新建 CubicBezierEasing。 */
    private val easingCache = ConcurrentHashMap<String, ConcurrentHashMap<String, Easing>>()
    /** 数值型 token 缓存（长度 / 字号 / 时长 / 倍数）：themeId → (token 名 → 数值)。 */
    private val numberCache = ConcurrentHashMap<String, ConcurrentHashMap<String, Float>>()
    /** 颜色 / 缓动原文的数字提取正则：一次性构造，热路径复用（原先每次调用都新建 Regex）。 */
    private val COLOR_NUM_RE = Regex("[0-9]+(?:\\.[0-9]+)?")
    private val EASING_NUM_RE = Regex("-?[0-9]+(?:\\.[0-9]+)?")

    /**
     * 全部 CSS 变量。数据按令牌类别分片在 DesignTokenSpecs1..8.kt，
     * 这里按声明顺序合并；顺序与拆分前一致，取值完全相同。
     */
    val SPECS: Map<String, CssToken> = buildMap {
        putDesignTokenSpecs1()
        putDesignTokenSpecs2()
        putDesignTokenSpecs3()
        putDesignTokenSpecs4()
        putDesignTokenSpecs5()
        putDesignTokenSpecs6()
        putDesignTokenSpecs7()
        putDesignTokenSpecs8()
    }

    /** 非法 themeId 回落到 apple，合法则原样返回。 */
    private fun normalize(themeId: String): String =
        if (THEME_ID_SET.contains(themeId)) themeId else DEFAULT_THEME

    fun raw(themeId: String, name: String): String {
        val spec = SPECS[name] ?: error("未知 token: " + name)
        return spec.raw(normalize(themeId))
    }

    fun kind(name: String): TokenKind = (SPECS[name] ?: error("未知 token: " + name)).kind

    /** 解析 CSS 颜色字面量（#rgb / #rrggbb / #rrggbbaa / rgb() / rgba()）。 */
    fun colorOf(themeId: String, name: String): Color {
        val id = normalize(themeId)
        val cache = colorCache[id] ?: ConcurrentHashMap<String, Color>().also { colorCache[id] = it }
        val hit = cache[name]
        if (hit != null) return hit
        val parsed = parseCssColor(raw(id, name))
        cache[name] = parsed
        return parsed
    }

    fun parseCssColor(v: String): Color {
        val s = v.trim()
        if (s.startsWith("#")) {
            val h = s.substring(1)
            val full = if (h.length == 3 || h.length == 4) h.map { "" + it + it }.joinToString("") else h
            val argb = if (full.length == 6) "FF" + full else full.substring(6) + full.substring(0, 6)
            return Color(argb.toLong(16).toInt())
        }
        val nums = COLOR_NUM_RE.findAll(s).map { it.value.toFloat() }.toList()
        require(nums.size >= 3) { "无法解析颜色: " + v }
        val a = if (nums.size >= 4) nums[3].coerceIn(0f, 1f) else 1f
        return Color(nums[0] / 255f, nums[1] / 255f, nums[2] / 255f, a)
    }

    /** 数值型 token：去掉单位后缀后解析为 Float，结果按 themeId + 名缓存。 */
    private fun number(themeId: String, name: String, suffix: String): Float {
        val id = normalize(themeId)
        val cache = numberCache[id] ?: ConcurrentHashMap<String, Float>().also { numberCache[id] = it }
        val hit = cache[name]
        if (hit != null) return hit
        val parsed = raw(id, name).removeSuffix(suffix).trim().toFloat()
        cache[name] = parsed
        return parsed
    }

    fun px(themeId: String, name: String): Float = number(themeId, name, "px")
    fun dp(themeId: String, name: String): Dp = px(themeId, name).dp
    fun fp(themeId: String, name: String): TextUnit = px(themeId, name).sp
    fun ms(themeId: String, name: String): Int = number(themeId, name, "ms").toInt()
    fun num(themeId: String, name: String): Float = number(themeId, name, "em")
    fun easing(themeId: String, name: String): Easing {
        val id = normalize(themeId)
        val cache = easingCache[id] ?: ConcurrentHashMap<String, Easing>().also { easingCache[id] = it }
        val hit = cache[name]
        if (hit != null) return hit
        val rawValue = raw(id, name)
        val p = EASING_NUM_RE.findAll(rawValue).map { it.value.toFloat() }.toList()
        require(p.size == 4) { "无法解析缓动曲线: " + rawValue }
        val curve = CubicBezierEasing(p[0], p[1], p[2], p[3])
        cache[name] = curve
        return curve
    }
}
