// ============================================================================
// DesignTokenTable.kt —— 生成文件，请勿手改。
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
    const val CSS_VARIABLE_COUNT: Int = 243
    /** 其中语义槽位数（其余为 7 套主题的色值命名空间槽位）。 */
    const val SEMANTIC_COUNT: Int = 132
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

    val SPECS: Map<String, CssToken> = buildMap {
        put(
            "bg",
            CssToken(
                "bg",
                TokenKind.COLOR,
                "#ffffff",
                mapOf(
                "glassmorphism" to "#eef6ff",
                "claude" to "#f5f4ed",
                "kami" to "#f5f4ed",
                "bento" to "#f5f8ff",
                ),
            ),
        )
        put(
            "surface",
            CssToken(
                "surface",
                TokenKind.COLOR,
                "#f5f5f7",
                mapOf(
                "glassmorphism" to "rgba(255, 255, 255, 0.74)",
                "notion" to "#f6f5f4",
                "stripe" to "#ffffff",
                "claude" to "#faf9f5",
                "ibm" to "#f4f4f4",
                "kami" to "#faf9f5",
                "bento" to "#ffffff",
                ),
            ),
        )
        put(
            "surface-warm",
            CssToken(
                "surface-warm",
                TokenKind.COLOR,
                "#fbfbfd",
                mapOf(
                "glassmorphism" to "rgba(238, 246, 255, 0.72)",
                "notion" to "#f6f5f4",
                "stripe" to "#f6f9fc",
                "claude" to "#e8e6dc",
                "ibm" to "#edf5ff",
                "kami" to "#e8e6dc",
                "bento" to "#eaf1ff",
                ),
            ),
        )
        put(
            "fg",
            CssToken(
                "fg",
                TokenKind.COLOR,
                "#1d1d1f",
                mapOf(
                "glassmorphism" to "#102033",
                "notion" to "rgba(0, 0, 0, 0.95)",
                "stripe" to "#061b31",
                "claude" to "#141413",
                "ibm" to "#161616",
                "kami" to "#141413",
                "bento" to "#101828",
                ),
            ),
        )
        put(
            "fg-2",
            CssToken(
                "fg-2",
                TokenKind.COLOR,
                "#424245",
                mapOf(
                "glassmorphism" to "#34465f",
                "notion" to "#31302e",
                "stripe" to "#273951",
                "claude" to "#3d3d3a",
                "ibm" to "#525252",
                "kami" to "#3d3d3a",
                "bento" to "#344054",
                ),
            ),
        )
        put(
            "muted",
            CssToken(
                "muted",
                TokenKind.COLOR,
                "#6e6e73",
                mapOf(
                "glassmorphism" to "#60708a",
                "notion" to "#615d59",
                "stripe" to "#64748d",
                "claude" to "#5e5d59",
                "ibm" to "#6f6f6f",
                "kami" to "#504e49",
                "bento" to "#667085",
                ),
            ),
        )
        put(
            "meta",
            CssToken(
                "meta",
                TokenKind.COLOR,
                "#86868b",
                mapOf(
                "glassmorphism" to "#4f8cff",
                "notion" to "#a39e98",
                "stripe" to "#64748d",
                "claude" to "#87867f",
                "ibm" to "#8d8d8d",
                "kami" to "#6b6a64",
                "bento" to "#2563eb",
                ),
            ),
        )
        put(
            "border",
            CssToken(
                "border",
                TokenKind.COLOR,
                "#d2d2d7",
                mapOf(
                "glassmorphism" to "rgba(255, 255, 255, 0.64)",
                "notion" to "rgba(0, 0, 0, 0.1)",
                "stripe" to "#e5edf5",
                "claude" to "#f0eee6",
                "ibm" to "#c6c6c6",
                "kami" to "#e8e6dc",
                "bento" to "#d7e0ef",
                ),
            ),
        )
        put(
            "border-soft",
            CssToken(
                "border-soft",
                TokenKind.COLOR,
                "#e8e8ed",
                mapOf(
                "glassmorphism" to "rgba(255, 255, 255, 0.38)",
                "notion" to "rgba(0, 0, 0, 0.06)",
                "stripe" to "#e5edf5",
                "claude" to "#e8e6dc",
                "ibm" to "#e0e0e0",
                "kami" to "#e5e3d8",
                "bento" to "#edf2f8",
                ),
            ),
        )
        put(
            "accent",
            CssToken(
                "accent",
                TokenKind.COLOR,
                "#0071e3",
                mapOf(
                "glassmorphism" to "#3e6ec9",
                "notion" to "#0075de",
                "stripe" to "#533afd",
                "claude" to "#aa5538",
                "ibm" to "#0f62fe",
                "kami" to "#1b365d",
                "bento" to "#2563eb",
                ),
            ),
        )
        put(
            "accent-on",
            CssToken(
                "accent-on",
                TokenKind.COLOR,
                "#ffffff",
                mapOf(
                "claude" to "#faf9f5",
                "kami" to "#faf9f5",
                ),
            ),
        )
        put(
            "accent-hover",
            CssToken(
                "accent-hover",
                TokenKind.COLOR,
                "#0077ed",
                mapOf(
                "glassmorphism" to "#3662b4",
                "notion" to "#005bab",
                "stripe" to "#4434d4",
                "claude" to "#984b31",
                "ibm" to "#0c57e3",
                "kami" to "#1b365d",
                "bento" to "#2058d2",
                ),
            ),
        )
        put(
            "accent-active",
            CssToken(
                "accent-active",
                TokenKind.COLOR,
                "#0066cc",
                mapOf(
                "glassmorphism" to "#3159a4",
                "notion" to "#005eb5",
                "stripe" to "#2e2b8c",
                "claude" to "#8a442c",
                "ibm" to "#0a4fd0",
                "kami" to "#142a48",
                "bento" to "#1c50c0",
                ),
            ),
        )
        put(
            "success",
            CssToken(
                "success",
                TokenKind.COLOR,
                "#16a34a",
                mapOf(
                "glassmorphism" to "#22c55e",
                "notion" to "#1aae39",
                "stripe" to "#15be53",
                "claude" to "#17a34a",
                "ibm" to "#24a148",
                "kami" to "#4a6b3a",
                ),
            ),
        )
        put(
            "warn",
            CssToken(
                "warn",
                TokenKind.COLOR,
                "#eab308",
                mapOf(
                "glassmorphism" to "#f59e0b",
                "notion" to "#dd5b00",
                "stripe" to "#9b6829",
                "ibm" to "#f1c21b",
                "kami" to "#8a6b1f",
                "bento" to "#f59e0b",
                ),
            ),
        )
        put(
            "danger",
            CssToken(
                "danger",
                TokenKind.COLOR,
                "#dc2626",
                mapOf(
                "glassmorphism" to "#ef4444",
                "stripe" to "#ea2261",
                "claude" to "#b53333",
                "ibm" to "#da1e28",
                "kami" to "#8a3a30",
                "bento" to "#ef4444",
                ),
            ),
        )
        put(
            "font-display",
            CssToken(
                "font-display",
                TokenKind.FONT,
                "-apple-system, \"SF Pro Display\", \"SF Pro Icons\", \"Helvetica Neue\", Helvetica, Arial, sans-serif",
                mapOf(
                "glassmorphism" to "Inter, system-ui, sans-serif",
                "notion" to "\"NotionInter\", \"Inter\", -apple-system, system-ui, \"Segoe UI\", Helvetica, Arial, sans-serif",
                "stripe" to "\"sohne-var\", \"Söhne\", \"Sohne\", \"SF Pro Display\", -apple-system, BlinkMacSystemFont, system-ui, \"Helvetica Neue\", Arial, sans-serif",
                "claude" to "\"Anthropic Serif\", Georgia, \"Times New Roman\", serif",
                "ibm" to "\"IBM Plex Sans\", \"Helvetica Neue\", Arial, sans-serif",
                "kami" to "Charter, Georgia, Palatino, \"Times New Roman\", serif",
                "bento" to "Inter, system-ui, sans-serif",
                ),
            ),
        )
        put(
            "font-body",
            CssToken(
                "font-body",
                TokenKind.FONT,
                "-apple-system, \"SF Pro Text\", \"SF Pro Icons\", \"Helvetica Neue\", Helvetica, Arial, sans-serif",
                mapOf(
                "glassmorphism" to "Inter, system-ui, sans-serif",
                "notion" to "\"NotionInter\", \"Inter\", -apple-system, system-ui, \"Segoe UI\", Helvetica, Arial, sans-serif",
                "stripe" to "\"sohne-var\", \"Söhne\", \"Sohne\", \"SF Pro Display\", -apple-system, BlinkMacSystemFont, system-ui, \"Helvetica Neue\", Arial, sans-serif",
                "claude" to "\"Anthropic Sans\", \"Arial\", system-ui, -apple-system, sans-serif",
                "ibm" to "\"IBM Plex Sans\", \"Helvetica Neue\", Arial, sans-serif",
                "kami" to "Charter, Georgia, Palatino, \"Times New Roman\", serif",
                "bento" to "Inter, system-ui, sans-serif",
                ),
            ),
        )
        put(
            "font-mono",
            CssToken(
                "font-mono",
                TokenKind.FONT,
                "ui-monospace, \"SF Mono\", SFMono-Regular, Menlo, Monaco, Consolas, \"Liberation Mono\", monospace",
                mapOf(
                "glassmorphism" to "\"SF Mono\", ui-monospace, Menlo, monospace",
                "notion" to "ui-monospace, \"SF Mono\", \"JetBrains Mono\", Menlo, Monaco, Consolas, monospace",
                "stripe" to "\"SourceCodePro\", \"Source Code Pro\", ui-monospace, \"SF Mono\", \"JetBrains Mono\", Menlo, Monaco, Consolas, monospace",
                "claude" to "\"Anthropic Mono\", ui-monospace, \"JetBrains Mono\", Menlo, monospace",
                "ibm" to "\"IBM Plex Mono\", \"SF Mono\", Menlo, monospace",
                "kami" to "\"JetBrains Mono\", \"SF Mono\", \"Fira Code\", Consolas, Monaco, \"TsangerJinKai02\", \"Source Han Serif SC\", monospace",
                "bento" to "\"SF Mono\", ui-monospace, Menlo, monospace",
                ),
            ),
        )
        put(
            "text-xs",
            CssToken(
                "text-xs",
                TokenKind.FONT_SIZE,
                "12px",
                mapOf(
                "kami" to "11px",
                ),
            ),
        )
        put("text-sm", CssToken("text-sm", TokenKind.FONT_SIZE, "14px", emptyMap()))
        put(
            "text-base",
            CssToken(
                "text-base",
                TokenKind.FONT_SIZE,
                "17px",
                mapOf(
                "glassmorphism" to "16px",
                "notion" to "16px",
                "stripe" to "16px",
                "claude" to "16px",
                "ibm" to "16px",
                "kami" to "14px",
                "bento" to "16px",
                ),
            ),
        )
        put(
            "text-lg",
            CssToken(
                "text-lg",
                TokenKind.FONT_SIZE,
                "21px",
                mapOf(
                "glassmorphism" to "18px",
                "notion" to "20px",
                "stripe" to "18px",
                "claude" to "20px",
                "ibm" to "18px",
                "kami" to "17px",
                "bento" to "18px",
                ),
            ),
        )
        put(
            "text-xl",
            CssToken(
                "text-xl",
                TokenKind.FONT_SIZE,
                "28px",
                mapOf(
                "glassmorphism" to "24px",
                "notion" to "26px",
                "stripe" to "22px",
                "claude" to "25px",
                "ibm" to "20px",
                "kami" to "22px",
                "bento" to "24px",
                ),
            ),
        )
        put(
            "text-2xl",
            CssToken(
                "text-2xl",
                TokenKind.FONT_SIZE,
                "40px",
                mapOf(
                "glassmorphism" to "36px",
                "stripe" to "32px",
                "claude" to "32px",
                "ibm" to "32px",
                "kami" to "32px",
                "bento" to "36px",
                ),
            ),
        )
        put(
            "text-3xl",
            CssToken(
                "text-3xl",
                TokenKind.FONT_SIZE,
                "56px",
                mapOf(
                "glassmorphism" to "54px",
                "notion" to "48px",
                "stripe" to "48px",
                "claude" to "52px",
                "ibm" to "42px",
                "kami" to "48px",
                "bento" to "54px",
                ),
            ),
        )
        put(
            "text-4xl",
            CssToken(
                "text-4xl",
                TokenKind.FONT_SIZE,
                "80px",
                mapOf(
                "glassmorphism" to "76px",
                "notion" to "64px",
                "stripe" to "56px",
                "claude" to "64px",
                "ibm" to "60px",
                "kami" to "96px",
                "bento" to "76px",
                ),
            ),
        )
        put(
            "leading-body",
            CssToken(
                "leading-body",
                TokenKind.NUMBER,
                "1.47",
                mapOf(
                "glassmorphism" to "1.55",
                "notion" to "1.5",
                "stripe" to "1.40",
                "claude" to "1.6",
                "ibm" to "1.5",
                "kami" to "1.55",
                "bento" to "1.52",
                ),
            ),
        )
        put(
            "leading-tight",
            CssToken(
                "leading-tight",
                TokenKind.NUMBER,
                "1.05",
                mapOf(
                "glassmorphism" to "1.04",
                "notion" to "1.00",
                "stripe" to "1.10",
                "claude" to "1.1",
                "ibm" to "1.17",
                "kami" to "1.25",
                "bento" to "1.06",
                ),
            ),
        )
        put(
            "tracking-display",
            CssToken(
                "tracking-display",
                TokenKind.EM,
                "-0.015em",
                mapOf(
                "glassmorphism" to "-0.025em",
                "notion" to "-0.033em",
                "stripe" to "-0.02em",
                "claude" to "0em",
                "ibm" to "0",
                "kami" to "-1.2px",
                "bento" to "-0.025em",
                ),
            ),
        )
        put("space-1", CssToken("space-1", TokenKind.LENGTH, "4px", emptyMap()))
        put("space-2", CssToken("space-2", TokenKind.LENGTH, "8px", emptyMap()))
        put("space-3", CssToken("space-3", TokenKind.LENGTH, "12px", emptyMap()))
        put("space-4", CssToken("space-4", TokenKind.LENGTH, "16px", emptyMap()))
        put("space-5", CssToken("space-5", TokenKind.LENGTH, "20px", emptyMap()))
        put("space-6", CssToken("space-6", TokenKind.LENGTH, "24px", emptyMap()))
        put("space-8", CssToken("space-8", TokenKind.LENGTH, "32px", emptyMap()))
        put("space-12", CssToken("space-12", TokenKind.LENGTH, "48px", emptyMap()))
        put(
            "section-y-desktop",
            CssToken(
                "section-y-desktop",
                TokenKind.LENGTH,
                "100px",
                mapOf(
                "glassmorphism" to "104px",
                "notion" to "80px",
                "stripe" to "96px",
                "claude" to "96px",
                "ibm" to "96px",
                "kami" to "72px",
                "bento" to "96px",
                ),
            ),
        )
        put(
            "section-y-tablet",
            CssToken(
                "section-y-tablet",
                TokenKind.LENGTH,
                "64px",
                mapOf(
                "glassmorphism" to "72px",
                "notion" to "48px",
                "ibm" to "72px",
                "kami" to "48px",
                "bento" to "68px",
                ),
            ),
        )
        put(
            "section-y-phone",
            CssToken(
                "section-y-phone",
                TokenKind.LENGTH,
                "40px",
                mapOf(
                "glassmorphism" to "52px",
                "notion" to "32px",
                "claude" to "48px",
                "ibm" to "48px",
                "kami" to "32px",
                "bento" to "48px",
                ),
            ),
        )
        put(
            "radius-sm",
            CssToken(
                "radius-sm",
                TokenKind.LENGTH,
                "8px",
                mapOf(
                "glassmorphism" to "16px",
                "notion" to "4px",
                "stripe" to "4px",
                "ibm" to "0px",
                "kami" to "4px",
                "bento" to "10px",
                ),
            ),
        )
        put(
            "radius-md",
            CssToken(
                "radius-md",
                TokenKind.LENGTH,
                "12px",
                mapOf(
                "glassmorphism" to "24px",
                "notion" to "8px",
                "stripe" to "6px",
                "ibm" to "0px",
                "kami" to "8px",
                "bento" to "16px",
                ),
            ),
        )
        put(
            "radius-lg",
            CssToken(
                "radius-lg",
                TokenKind.LENGTH,
                "18px",
                mapOf(
                "glassmorphism" to "36px",
                "notion" to "12px",
                "stripe" to "8px",
                "claude" to "16px",
                "ibm" to "0px",
                "kami" to "12px",
                "bento" to "24px",
                ),
            ),
        )
        put(
            "radius-pill",
            CssToken(
                "radius-pill",
                TokenKind.LENGTH,
                "980px",
                mapOf(
                "glassmorphism" to "9999px",
                "notion" to "9999px",
                "stripe" to "9999px",
                "claude" to "9999px",
                "ibm" to "9999px",
                "kami" to "9999px",
                "bento" to "9999px",
                ),
            ),
        )
        put("elev-flat", CssToken("elev-flat", TokenKind.SHADOW, "none", emptyMap()))
        put(
            "elev-ring",
            CssToken(
                "elev-ring",
                TokenKind.SHADOW,
                "0 0 0 1px #d2d2d7",
                mapOf(
                "glassmorphism" to "0 0 0 1px rgba(255, 255, 255, 0.64)",
                "notion" to "0 0 0 1px rgba(0, 0, 0, 0.1)",
                "stripe" to "0 0 0 1px #e5edf5",
                "claude" to "0 0 0 1px #f0eee6",
                "ibm" to "0 0 0 1px #c6c6c6",
                "kami" to "0 0 0 1px #e8e6dc",
                "bento" to "0 0 0 1px #d7e0ef",
                ),
            ),
        )
        put(
            "elev-raised",
            CssToken(
                "elev-raised",
                TokenKind.SHADOW,
                "0 12px 32px rgba(0, 0, 0, 0.08)",
                mapOf(
                "glassmorphism" to "0 24px 80px rgba(79, 140, 255, 0.18)",
                "notion" to "rgba(0, 0, 0, 0.04) 0px 4px 18px, rgba(0, 0, 0, 0.027) 0px 2px 7.85px, rgba(0, 0, 0, 0.02) 0px 0.8px 2.93px, rgba(0, 0, 0, 0.01) 0px 0.175px 1.04px",
                "stripe" to "rgba(50, 50, 93, 0.25) 0px 30px 45px -30px, rgba(0, 0, 0, 0.10) 0px 18px 36px -18px",
                "claude" to "rgba(0, 0, 0, 0.05) 0px 4px 24px",
                "ibm" to "0 2px 6px rgba(0, 0, 0, 0.12)",
                "kami" to "0 4px 24px rgba(0, 0, 0, 0.05)",
                "bento" to "0 20px 52px rgba(16, 24, 40, 0.11)",
                ),
            ),
        )
        put(
            "focus-ring",
            CssToken(
                "focus-ring",
                TokenKind.SHADOW,
                "0 0 0 4px rgba(0, 113, 227, 0.35)",
                mapOf(
                "glassmorphism" to "0 0 0 4px rgba(79, 140, 255, 0.28)",
                "notion" to "0 0 0 3px rgba(9, 127, 232, 0.3)",
                "stripe" to "0 0 0 2px #533afd, 0 0 0 5px rgba(83, 58, 253, 0.25)",
                "claude" to "0 0 0 3px rgba(56, 152, 236, 0.3)",
                "ibm" to "0 0 0 2px #ffffff, 0 0 0 4px #0f62fe",
                "kami" to "0 0 0 2px #142a48",
                "bento" to "0 0 0 4px rgba(37, 99, 235, 0.22)",
                ),
            ),
        )
        put(
            "motion-fast",
            CssToken(
                "motion-fast",
                TokenKind.DURATION,
                "150ms",
                mapOf(
                "glassmorphism" to "180ms",
                "ibm" to "110ms",
                ),
            ),
        )
        put(
            "motion-base",
            CssToken(
                "motion-base",
                TokenKind.DURATION,
                "220ms",
                mapOf(
                "glassmorphism" to "280ms",
                "notion" to "200ms",
                "stripe" to "200ms",
                "claude" to "200ms",
                "ibm" to "180ms",
                "kami" to "200ms",
                "bento" to "240ms",
                ),
            ),
        )
        put(
            "ease-standard",
            CssToken(
                "ease-standard",
                TokenKind.EASING,
                "cubic-bezier(0.28, 0, 0.22, 1)",
                mapOf(
                "glassmorphism" to "cubic-bezier(0.22, 1, 0.36, 1)",
                "notion" to "cubic-bezier(0.2, 0, 0, 1)",
                "stripe" to "cubic-bezier(0.2, 0, 0, 1)",
                "claude" to "cubic-bezier(0.2, 0, 0, 1)",
                "ibm" to "cubic-bezier(0.2, 0, 0.38, 0.9)",
                "kami" to "cubic-bezier(0.2, 0, 0, 1)",
                "bento" to "cubic-bezier(0.2, 0, 0, 1)",
                ),
            ),
        )
        put(
            "container-max",
            CssToken(
                "container-max",
                TokenKind.LENGTH,
                "1024px",
                mapOf(
                "glassmorphism" to "1180px",
                "notion" to "1200px",
                "stripe" to "1080px",
                "claude" to "1200px",
                "ibm" to "1312px",
                "kami" to "1120px",
                "bento" to "1180px",
                ),
            ),
        )
        put(
            "container-gutter-desktop",
            CssToken(
                "container-gutter-desktop",
                TokenKind.LENGTH,
                "22px",
                mapOf(
                "glassmorphism" to "36px",
                "notion" to "24px",
                "stripe" to "32px",
                "claude" to "24px",
                "ibm" to "32px",
                "kami" to "64px",
                "bento" to "36px",
                ),
            ),
        )
        put(
            "container-gutter-tablet",
            CssToken(
                "container-gutter-tablet",
                TokenKind.LENGTH,
                "18px",
                mapOf(
                "glassmorphism" to "24px",
                "notion" to "16px",
                "stripe" to "24px",
                "claude" to "16px",
                "ibm" to "24px",
                "kami" to "32px",
                "bento" to "24px",
                ),
            ),
        )
        put(
            "container-gutter-phone",
            CssToken(
                "container-gutter-phone",
                TokenKind.LENGTH,
                "16px",
                mapOf(
                "notion" to "12px",
                "claude" to "12px",
                ),
            ),
        )
        put("gm-bg", CssToken("gm-bg", TokenKind.COLOR, "#eef6ff", emptyMap()))
        put("gm-surface", CssToken("gm-surface", TokenKind.COLOR, "rgba(255, 255, 255, 0.74)", emptyMap()))
        put("gm-surface-warm", CssToken("gm-surface-warm", TokenKind.COLOR, "rgba(238, 246, 255, 0.72)", emptyMap()))
        put("gm-fg", CssToken("gm-fg", TokenKind.COLOR, "#102033", emptyMap()))
        put("gm-fg-2", CssToken("gm-fg-2", TokenKind.COLOR, "#34465f", emptyMap()))
        put("gm-muted", CssToken("gm-muted", TokenKind.COLOR, "#60708a", emptyMap()))
        put("gm-meta", CssToken("gm-meta", TokenKind.COLOR, "#4f8cff", emptyMap()))
        put("gm-border", CssToken("gm-border", TokenKind.COLOR, "rgba(255, 255, 255, 0.64)", emptyMap()))
        put("gm-border-soft", CssToken("gm-border-soft", TokenKind.COLOR, "rgba(255, 255, 255, 0.38)", emptyMap()))
        put("gm-accent", CssToken("gm-accent", TokenKind.COLOR, "#3e6ec9", emptyMap()))
        put("gm-accent-on", CssToken("gm-accent-on", TokenKind.COLOR, "#ffffff", emptyMap()))
        put("gm-success", CssToken("gm-success", TokenKind.COLOR, "#22c55e", emptyMap()))
        put("gm-warn", CssToken("gm-warn", TokenKind.COLOR, "#f59e0b", emptyMap()))
        put("gm-danger", CssToken("gm-danger", TokenKind.COLOR, "#ef4444", emptyMap()))
        put("gm-elev-raised", CssToken("gm-elev-raised", TokenKind.SHADOW, "0 24px 80px rgba(79, 140, 255, 0.18)", emptyMap()))
        put("gm-focus-ring", CssToken("gm-focus-ring", TokenKind.SHADOW, "0 0 0 4px rgba(79, 140, 255, 0.28)", emptyMap()))
        put("nt-bg", CssToken("nt-bg", TokenKind.COLOR, "#ffffff", emptyMap()))
        put("nt-surface", CssToken("nt-surface", TokenKind.COLOR, "#f6f5f4", emptyMap()))
        put("nt-fg", CssToken("nt-fg", TokenKind.COLOR, "rgba(0, 0, 0, 0.95)", emptyMap()))
        put("nt-fg-2", CssToken("nt-fg-2", TokenKind.COLOR, "#31302e", emptyMap()))
        put("nt-muted", CssToken("nt-muted", TokenKind.COLOR, "#615d59", emptyMap()))
        put("nt-meta", CssToken("nt-meta", TokenKind.COLOR, "#a39e98", emptyMap()))
        put("nt-border", CssToken("nt-border", TokenKind.COLOR, "rgba(0, 0, 0, 0.1)", emptyMap()))
        put("nt-border-soft", CssToken("nt-border-soft", TokenKind.COLOR, "rgba(0, 0, 0, 0.06)", emptyMap()))
        put("nt-accent", CssToken("nt-accent", TokenKind.COLOR, "#0075de", emptyMap()))
        put("nt-accent-on", CssToken("nt-accent-on", TokenKind.COLOR, "#ffffff", emptyMap()))
        put("nt-accent-hover", CssToken("nt-accent-hover", TokenKind.COLOR, "#005bab", emptyMap()))
        put("nt-success", CssToken("nt-success", TokenKind.COLOR, "#1aae39", emptyMap()))
        put("nt-warn", CssToken("nt-warn", TokenKind.COLOR, "#dd5b00", emptyMap()))
        put("nt-danger", CssToken("nt-danger", TokenKind.COLOR, "#dc2626", emptyMap()))
        put("nt-elev-raised", CssToken("nt-elev-raised", TokenKind.RAW, "rgba(0, 0, 0, 0.04) 0px 4px 18px, rgba(0, 0, 0, 0.027) 0px 2px 7.85px, rgba(0, 0, 0, 0.02) 0px 0.8px 2.93px, rgba(0, 0, 0, 0.01) 0px 0.175px 1.04px", emptyMap()))
        put("nt-focus-ring", CssToken("nt-focus-ring", TokenKind.SHADOW, "0 0 0 3px rgba(9, 127, 232, 0.3)", emptyMap()))
        put("st-bg", CssToken("st-bg", TokenKind.COLOR, "#ffffff", emptyMap()))
        put("st-surface", CssToken("st-surface", TokenKind.COLOR, "#ffffff", emptyMap()))
        put("st-surface-warm", CssToken("st-surface-warm", TokenKind.COLOR, "#f6f9fc", emptyMap()))
        put("st-fg", CssToken("st-fg", TokenKind.COLOR, "#061b31", emptyMap()))
        put("st-fg-2", CssToken("st-fg-2", TokenKind.COLOR, "#273951", emptyMap()))
        put("st-muted", CssToken("st-muted", TokenKind.COLOR, "#64748d", emptyMap()))
        put("st-border", CssToken("st-border", TokenKind.COLOR, "#e5edf5", emptyMap()))
        put("st-accent", CssToken("st-accent", TokenKind.COLOR, "#533afd", emptyMap()))
        put("st-accent-on", CssToken("st-accent-on", TokenKind.COLOR, "#ffffff", emptyMap()))
        put("st-accent-hover", CssToken("st-accent-hover", TokenKind.COLOR, "#4434d4", emptyMap()))
        put("st-accent-active", CssToken("st-accent-active", TokenKind.COLOR, "#2e2b8c", emptyMap()))
        put("st-success", CssToken("st-success", TokenKind.COLOR, "#15be53", emptyMap()))
        put("st-warn", CssToken("st-warn", TokenKind.COLOR, "#9b6829", emptyMap()))
        put("st-danger", CssToken("st-danger", TokenKind.COLOR, "#ea2261", emptyMap()))
        put("st-elev-raised", CssToken("st-elev-raised", TokenKind.RAW, "rgba(50, 50, 93, 0.25) 0px 30px 45px -30px, rgba(0, 0, 0, 0.10) 0px 18px 36px -18px", emptyMap()))
        put("cl-bg", CssToken("cl-bg", TokenKind.COLOR, "#f5f4ed", emptyMap()))
        put("cl-surface", CssToken("cl-surface", TokenKind.COLOR, "#faf9f5", emptyMap()))
        put("cl-surface-warm", CssToken("cl-surface-warm", TokenKind.COLOR, "#e8e6dc", emptyMap()))
        put("cl-fg", CssToken("cl-fg", TokenKind.COLOR, "#141413", emptyMap()))
        put("cl-fg-2", CssToken("cl-fg-2", TokenKind.COLOR, "#3d3d3a", emptyMap()))
        put("cl-muted", CssToken("cl-muted", TokenKind.COLOR, "#5e5d59", emptyMap()))
        put("cl-meta", CssToken("cl-meta", TokenKind.COLOR, "#87867f", emptyMap()))
        put("cl-border", CssToken("cl-border", TokenKind.COLOR, "#f0eee6", emptyMap()))
        put("cl-border-soft", CssToken("cl-border-soft", TokenKind.COLOR, "#e8e6dc", emptyMap()))
        put("cl-accent", CssToken("cl-accent", TokenKind.COLOR, "#aa5538", emptyMap()))
        put("cl-accent-on", CssToken("cl-accent-on", TokenKind.COLOR, "#faf9f5", emptyMap()))
        put("cl-success", CssToken("cl-success", TokenKind.COLOR, "#17a34a", emptyMap()))
        put("cl-warn", CssToken("cl-warn", TokenKind.COLOR, "#eab308", emptyMap()))
        put("cl-danger", CssToken("cl-danger", TokenKind.COLOR, "#b53333", emptyMap()))
        put("cl-elev-raised", CssToken("cl-elev-raised", TokenKind.RAW, "rgba(0, 0, 0, 0.05) 0px 4px 24px", emptyMap()))
        put("cl-focus-ring", CssToken("cl-focus-ring", TokenKind.SHADOW, "0 0 0 3px rgba(56, 152, 236, 0.3)", emptyMap()))
        put("ib-bg", CssToken("ib-bg", TokenKind.COLOR, "#ffffff", emptyMap()))
        put("ib-surface", CssToken("ib-surface", TokenKind.COLOR, "#f4f4f4", emptyMap()))
        put("ib-surface-warm", CssToken("ib-surface-warm", TokenKind.COLOR, "#edf5ff", emptyMap()))
        put("ib-fg", CssToken("ib-fg", TokenKind.COLOR, "#161616", emptyMap()))
        put("ib-fg-2", CssToken("ib-fg-2", TokenKind.COLOR, "#525252", emptyMap()))
        put("ib-muted", CssToken("ib-muted", TokenKind.COLOR, "#6f6f6f", emptyMap()))
        put("ib-meta", CssToken("ib-meta", TokenKind.COLOR, "#8d8d8d", emptyMap()))
        put("ib-border", CssToken("ib-border", TokenKind.COLOR, "#c6c6c6", emptyMap()))
        put("ib-border-soft", CssToken("ib-border-soft", TokenKind.COLOR, "#e0e0e0", emptyMap()))
        put("ib-accent", CssToken("ib-accent", TokenKind.COLOR, "#0f62fe", emptyMap()))
        put("ib-accent-on", CssToken("ib-accent-on", TokenKind.COLOR, "#ffffff", emptyMap()))
        put("ib-success", CssToken("ib-success", TokenKind.COLOR, "#24a148", emptyMap()))
        put("ib-warn", CssToken("ib-warn", TokenKind.COLOR, "#f1c21b", emptyMap()))
        put("ib-danger", CssToken("ib-danger", TokenKind.COLOR, "#da1e28", emptyMap()))
        put("ib-elev-raised", CssToken("ib-elev-raised", TokenKind.SHADOW, "0 2px 6px rgba(0, 0, 0, 0.12)", emptyMap()))
        put("ib-focus-ring", CssToken("ib-focus-ring", TokenKind.SHADOW, "0 0 0 2px #ffffff, 0 0 0 4px #0f62fe", emptyMap()))
        put("km-bg", CssToken("km-bg", TokenKind.COLOR, "#f5f4ed", emptyMap()))
        put("km-surface", CssToken("km-surface", TokenKind.COLOR, "#faf9f5", emptyMap()))
        put("km-surface-warm", CssToken("km-surface-warm", TokenKind.COLOR, "#e8e6dc", emptyMap()))
        put("km-fg", CssToken("km-fg", TokenKind.COLOR, "#141413", emptyMap()))
        put("km-fg-2", CssToken("km-fg-2", TokenKind.COLOR, "#3d3d3a", emptyMap()))
        put("km-muted", CssToken("km-muted", TokenKind.COLOR, "#504e49", emptyMap()))
        put("km-meta", CssToken("km-meta", TokenKind.COLOR, "#6b6a64", emptyMap()))
        put("km-border", CssToken("km-border", TokenKind.COLOR, "#e8e6dc", emptyMap()))
        put("km-border-soft", CssToken("km-border-soft", TokenKind.COLOR, "#e5e3d8", emptyMap()))
        put("km-accent", CssToken("km-accent", TokenKind.COLOR, "#1b365d", emptyMap()))
        put("km-accent-on", CssToken("km-accent-on", TokenKind.COLOR, "#faf9f5", emptyMap()))
        put("km-accent-active", CssToken("km-accent-active", TokenKind.COLOR, "#142a48", emptyMap()))
        put("km-success", CssToken("km-success", TokenKind.COLOR, "#4a6b3a", emptyMap()))
        put("km-warn", CssToken("km-warn", TokenKind.COLOR, "#8a6b1f", emptyMap()))
        put("km-danger", CssToken("km-danger", TokenKind.COLOR, "#8a3a30", emptyMap()))
        put("km-elev-raised", CssToken("km-elev-raised", TokenKind.SHADOW, "0 4px 24px rgba(0, 0, 0, 0.05)", emptyMap()))
        put("bn-bg", CssToken("bn-bg", TokenKind.COLOR, "#f5f8ff", emptyMap()))
        put("bn-surface", CssToken("bn-surface", TokenKind.COLOR, "#ffffff", emptyMap()))
        put("bn-surface-warm", CssToken("bn-surface-warm", TokenKind.COLOR, "#eaf1ff", emptyMap()))
        put("bn-fg", CssToken("bn-fg", TokenKind.COLOR, "#101828", emptyMap()))
        put("bn-fg-2", CssToken("bn-fg-2", TokenKind.COLOR, "#344054", emptyMap()))
        put("bn-muted", CssToken("bn-muted", TokenKind.COLOR, "#667085", emptyMap()))
        put("bn-meta", CssToken("bn-meta", TokenKind.COLOR, "#2563eb", emptyMap()))
        put("bn-border", CssToken("bn-border", TokenKind.COLOR, "#d7e0ef", emptyMap()))
        put("bn-border-soft", CssToken("bn-border-soft", TokenKind.COLOR, "#edf2f8", emptyMap()))
        put("bn-accent", CssToken("bn-accent", TokenKind.COLOR, "#2563eb", emptyMap()))
        put("bn-accent-on", CssToken("bn-accent-on", TokenKind.COLOR, "#ffffff", emptyMap()))
        put("bn-success", CssToken("bn-success", TokenKind.COLOR, "#16a34a", emptyMap()))
        put("bn-warn", CssToken("bn-warn", TokenKind.COLOR, "#f59e0b", emptyMap()))
        put("bn-danger", CssToken("bn-danger", TokenKind.COLOR, "#ef4444", emptyMap()))
        put("bn-elev-raised", CssToken("bn-elev-raised", TokenKind.SHADOW, "0 20px 52px rgba(16, 24, 40, 0.11)", emptyMap()))
        put("bn-focus-ring", CssToken("bn-focus-ring", TokenKind.SHADOW, "0 0 0 4px rgba(37, 99, 235, 0.22)", emptyMap()))
        put(
            "page-bg",
            CssToken(
                "page-bg",
                TokenKind.COLOR,
                "#f4f4f4",
                mapOf(
                "glassmorphism" to "#e3ebf5",
                "notion" to "rgba(243, 243, 243, 0.9975)",
                "stripe" to "#f3f4f5",
                "claude" to "#eae9e2",
                "ibm" to "#f3f3f3",
                "kami" to "#eae9e2",
                "bento" to "#eaedf4",
                ),
            ),
        )
        put(
            "frame-a",
            CssToken(
                "frame-a",
                TokenKind.COLOR,
                "#d9d9d9",
                mapOf(
                "glassmorphism" to "#c8d2dc",
                "notion" to "rgba(213, 213, 213, 0.9915)",
                "stripe" to "#d5d8dc",
                "claude" to "#cfcec8",
                "ibm" to "#d7d7d7",
                "kami" to "#cfcec8",
                "bento" to "#ced2da",
                ),
            ),
        )
        put(
            "frame-b",
            CssToken(
                "frame-b",
                TokenKind.COLOR,
                "#e8e8e9",
                mapOf(
                "glassmorphism" to "#d8e1eb",
                "notion" to "rgba(231, 231, 231, 0.995)",
                "stripe" to "#e6e8ea",
                "claude" to "#dfded7",
                "ibm" to "#e8e8e8",
                "kami" to "#dfded7",
                "bento" to "#dee2ea",
                ),
            ),
        )
        put(
            "frame-edge",
            CssToken(
                "frame-edge",
                TokenKind.COLOR,
                "rgba(255, 255, 255, 0.6)",
                mapOf(
                "glassmorphism" to "rgba(238, 246, 255, 0.6)",
                "claude" to "rgba(245, 244, 237, 0.6)",
                "kami" to "rgba(245, 244, 237, 0.6)",
                "bento" to "rgba(245, 248, 255, 0.6)",
                ),
            ),
        )
        put(
            "frame-inner",
            CssToken(
                "frame-inner",
                TokenKind.COLOR,
                "rgba(29, 29, 31, 0.24)",
                mapOf(
                "glassmorphism" to "rgba(16, 32, 51, 0.24)",
                "notion" to "rgba(0, 0, 0, 0.228)",
                "stripe" to "rgba(6, 27, 49, 0.24)",
                "claude" to "rgba(20, 20, 19, 0.24)",
                "ibm" to "rgba(22, 22, 22, 0.24)",
                "kami" to "rgba(20, 20, 19, 0.24)",
                "bento" to "rgba(16, 24, 40, 0.24)",
                ),
            ),
        )
        put(
            "frame-key",
            CssToken(
                "frame-key",
                TokenKind.COLOR,
                "#b2b2b3",
                mapOf(
                "glassmorphism" to "#a3adba",
                "notion" to "rgba(171, 171, 171, 0.983)",
                "stripe" to "#aab1b9",
                "claude" to "#a9a8a3",
                "ibm" to "#b0b0b0",
                "kami" to "#a9a8a3",
                "bento" to "#a7acb6",
                ),
            ),
        )
        put(
            "frame-shadow",
            CssToken(
                "frame-shadow",
                TokenKind.COLOR,
                "rgba(29, 29, 31, 0.28)",
                mapOf(
                "glassmorphism" to "rgba(16, 32, 51, 0.28)",
                "notion" to "rgba(0, 0, 0, 0.266)",
                "stripe" to "rgba(6, 27, 49, 0.28)",
                "claude" to "rgba(20, 20, 19, 0.28)",
                "ibm" to "rgba(22, 22, 22, 0.28)",
                "kami" to "rgba(20, 20, 19, 0.28)",
                "bento" to "rgba(16, 24, 40, 0.28)",
                ),
            ),
        )
        put(
            "punch",
            CssToken(
                "punch",
                TokenKind.COLOR,
                "#343435",
                mapOf(
                "glassmorphism" to "#263547",
                "notion" to "rgba(27, 27, 27, 0.955)",
                "stripe" to "#1f3246",
                "claude" to "#2b2a29",
                "ibm" to "#2d2d2d",
                "kami" to "#2b2a29",
                "bento" to "#272e3e",
                ),
            ),
        )
        put(
            "punch-ring",
            CssToken(
                "punch-ring",
                TokenKind.COLOR,
                "rgba(255, 255, 255, 0.24)",
                mapOf(
                "glassmorphism" to "rgba(238, 246, 255, 0.24)",
                "claude" to "rgba(245, 244, 237, 0.24)",
                "kami" to "rgba(245, 244, 237, 0.24)",
                "bento" to "rgba(245, 248, 255, 0.24)",
                ),
            ),
        )
        put("screen-w", CssToken("screen-w", TokenKind.LENGTH, "393px", emptyMap()))
        put("screen-h", CssToken("screen-h", TokenKind.LENGTH, "852px", emptyMap()))
        put("statusbar-h", CssToken("statusbar-h", TokenKind.LENGTH, "36px", emptyMap()))
        put("appbar-h", CssToken("appbar-h", TokenKind.LENGTH, "56px", emptyMap()))
        put("tab-h", CssToken("tab-h", TokenKind.LENGTH, "52px", emptyMap()))
        put("gesture-h", CssToken("gesture-h", TokenKind.LENGTH, "22px", emptyMap()))
        put("tap-min", CssToken("tap-min", TokenKind.LENGTH, "44px", emptyMap()))
        put("row-min-h", CssToken("row-min-h", TokenKind.LENGTH, "52px", emptyMap()))
        put(
            "app-bg",
            CssToken(
                "app-bg",
                TokenKind.COLOR,
                "#ffffff",
                mapOf(
                "glassmorphism" to "#eef6ff",
                "claude" to "#f5f4ed",
                "kami" to "#f5f4ed",
                "bento" to "#f5f8ff",
                ),
            ),
        )
        put(
            "backdrop-a",
            CssToken(
                "backdrop-a",
                TokenKind.COLOR,
                "#f4f4f4",
                mapOf(
                "glassmorphism" to "#e3ebf5",
                "notion" to "rgba(243, 243, 243, 0.9975)",
                "stripe" to "#f3f4f5",
                "claude" to "#eae9e2",
                "ibm" to "#f3f3f3",
                "kami" to "#eae9e2",
                "bento" to "#eaedf4",
                ),
            ),
        )
        put(
            "backdrop-b",
            CssToken(
                "backdrop-b",
                TokenKind.COLOR,
                "#fdfdfd",
                mapOf(
                "glassmorphism" to "#ecf4fd",
                "claude" to "#f3f2eb",
                "kami" to "#f3f2eb",
                "bento" to "#f3f6fd",
                ),
            ),
        )
        put(
            "glass-bg",
            CssToken(
                "glass-bg",
                TokenKind.COLOR,
                "rgba(245, 245, 247, 0.72)",
                mapOf(
                "glassmorphism" to "rgba(255, 255, 255, 0.5328)",
                "notion" to "rgba(246, 245, 244, 0.72)",
                "stripe" to "rgba(255, 255, 255, 0.72)",
                "claude" to "rgba(250, 249, 245, 0.72)",
                "ibm" to "rgba(244, 244, 244, 0.72)",
                "kami" to "rgba(250, 249, 245, 0.72)",
                "bento" to "rgba(255, 255, 255, 0.72)",
                ),
            ),
        )
        put(
            "glass-border",
            CssToken(
                "glass-border",
                TokenKind.COLOR,
                "rgba(29, 29, 31, 0.08)",
                mapOf(
                "glassmorphism" to "rgba(16, 32, 51, 0.08)",
                "notion" to "rgba(0, 0, 0, 0.076)",
                "stripe" to "rgba(6, 27, 49, 0.08)",
                "claude" to "rgba(20, 20, 19, 0.08)",
                "ibm" to "rgba(22, 22, 22, 0.08)",
                "kami" to "rgba(20, 20, 19, 0.08)",
                "bento" to "rgba(16, 24, 40, 0.08)",
                ),
            ),
        )
        put(
            "glass-highlight",
            CssToken(
                "glass-highlight",
                TokenKind.COLOR,
                "rgba(255, 255, 255, 0.7)",
                mapOf(
                "glassmorphism" to "rgba(238, 246, 255, 0.7)",
                "claude" to "rgba(245, 244, 237, 0.7)",
                "kami" to "rgba(245, 244, 237, 0.7)",
                "bento" to "rgba(245, 248, 255, 0.7)",
                ),
            ),
        )
        put("glass-blur", CssToken("glass-blur", TokenKind.FILTER, "blur(18px) saturate(1.5)", emptyMap()))
        put(
            "elev-1",
            CssToken(
                "elev-1",
                TokenKind.SHADOW,
                "0 1px 2px rgba(29, 29, 31, 0.07)",
                mapOf(
                "glassmorphism" to "0 1px 2px rgba(16, 32, 51, 0.07)",
                "notion" to "0 1px 2px rgba(0, 0, 0, 0.0665)",
                "stripe" to "0 1px 2px rgba(6, 27, 49, 0.07)",
                "claude" to "0 1px 2px rgba(20, 20, 19, 0.07)",
                "ibm" to "0 1px 2px rgba(22, 22, 22, 0.07)",
                "kami" to "0 1px 2px rgba(20, 20, 19, 0.07)",
                "bento" to "0 1px 2px rgba(16, 24, 40, 0.07)",
                ),
            ),
        )
        put(
            "elev-2",
            CssToken(
                "elev-2",
                TokenKind.SHADOW,
                "0 10px 28px -10px rgba(29, 29, 31, 0.22)",
                mapOf(
                "glassmorphism" to "0 10px 28px -10px rgba(16, 32, 51, 0.22)",
                "notion" to "0 10px 28px -10px rgba(0, 0, 0, 0.209)",
                "stripe" to "0 10px 28px -10px rgba(6, 27, 49, 0.22)",
                "claude" to "0 10px 28px -10px rgba(20, 20, 19, 0.22)",
                "ibm" to "0 10px 28px -10px rgba(22, 22, 22, 0.22)",
                "kami" to "0 10px 28px -10px rgba(20, 20, 19, 0.22)",
                "bento" to "0 10px 28px -10px rgba(16, 24, 40, 0.22)",
                ),
            ),
        )
        put(
            "elev-3",
            CssToken(
                "elev-3",
                TokenKind.SHADOW,
                "0 22px 56px -18px rgba(29, 29, 31, 0.34)",
                mapOf(
                "glassmorphism" to "0 22px 56px -18px rgba(16, 32, 51, 0.34)",
                "notion" to "0 22px 56px -18px rgba(0, 0, 0, 0.323)",
                "stripe" to "0 22px 56px -18px rgba(6, 27, 49, 0.34)",
                "claude" to "0 22px 56px -18px rgba(20, 20, 19, 0.34)",
                "ibm" to "0 22px 56px -18px rgba(22, 22, 22, 0.34)",
                "kami" to "0 22px 56px -18px rgba(20, 20, 19, 0.34)",
                "bento" to "0 22px 56px -18px rgba(16, 24, 40, 0.34)",
                ),
            ),
        )
        put(
            "scrim",
            CssToken(
                "scrim",
                TokenKind.COLOR,
                "rgba(29, 29, 31, 0.34)",
                mapOf(
                "glassmorphism" to "rgba(16, 32, 51, 0.34)",
                "notion" to "rgba(0, 0, 0, 0.323)",
                "stripe" to "rgba(6, 27, 49, 0.34)",
                "claude" to "rgba(20, 20, 19, 0.34)",
                "ibm" to "rgba(22, 22, 22, 0.34)",
                "kami" to "rgba(20, 20, 19, 0.34)",
                "bento" to "rgba(16, 24, 40, 0.34)",
                ),
            ),
        )
        put(
            "success-text",
            CssToken(
                "success-text",
                TokenKind.COLOR,
                "#18743b",
                mapOf(
                "glassmorphism" to "#1c8b4f",
                "notion" to "rgba(17, 115, 38, 0.9825)",
                "stripe" to "#108547",
                "claude" to "#167137",
                "ibm" to "#1f7037",
                "kami" to "#374d2c",
                "bento" to "#14723e",
                ),
            ),
        )
        put(
            "warn-text",
            CssToken(
                "warn-text",
                TokenKind.COLOR,
                "#796115",
                mapOf(
                "glassmorphism" to "#775921",
                "notion" to "rgba(102, 42, 0, 0.9725)",
                "stripe" to "#493e2d",
                "claude" to "#745c0e",
                "ibm" to "#796318",
                "kami" to "#493b18",
                "bento" to "#77541b",
                ),
            ),
        )
        put(
            "danger-text",
            CssToken(
                "danger-text",
                TokenKind.COLOR,
                "#992324",
                mapOf(
                "glassmorphism" to "#a1373e",
                "notion" to "rgba(146, 25, 25, 0.9825)",
                "stripe" to "#9a2050",
                "claude" to "#7d2828",
                "ibm" to "#951b22",
                "kami" to "#612d26",
                "bento" to "#a1353a",
                ),
            ),
        )
        put(
            "accent-text",
            CssToken(
                "accent-text",
                TokenKind.COLOR,
                "#0367cb",
                mapOf(
                "glassmorphism" to "#3865b7",
                "notion" to "rgba(0, 104, 197, 0.994)",
                "stripe" to "#4a36e5",
                "claude" to "#984d34",
                "ibm" to "#1059e2",
                "kami" to "#1a3254",
                "bento" to "#225ad4",
                ),
            ),
        )
        put(
            "income-text",
            CssToken(
                "income-text",
                TokenKind.COLOR,
                "#19703a",
                mapOf(
                "glassmorphism" to "#1b864e",
                "notion" to "rgba(16, 110, 36, 0.981)",
                "stripe" to "#0f8046",
                "claude" to "#166d35",
                "ibm" to "#1f6c35",
                "kami" to "#354a2b",
                "bento" to "#146e3d",
                ),
            ),
        )
        put(
            "expense-text",
            CssToken(
                "expense-text",
                TokenKind.COLOR,
                "#932323",
                mapOf(
                "glassmorphism" to "#9a363e",
                "notion" to "rgba(139, 24, 24, 0.981)",
                "stripe" to "#931f4f",
                "claude" to "#782727",
                "ibm" to "#901b21",
                "kami" to "#5d2c25",
                "bento" to "#9a3339",
                ),
            ),
        )
        put(
            "chart-seq",
            CssToken(
                "chart-seq",
                TokenKind.COLOR,
                "#0071e3",
                mapOf(
                "glassmorphism" to "#3e6ec9",
                "notion" to "#0075de",
                "stripe" to "#533afd",
                "claude" to "#aa5538",
                "ibm" to "#0f62fe",
                "kami" to "#1b365d",
                "bento" to "#2563eb",
                ),
            ),
        )
        put("chart-1", CssToken("chart-1", TokenKind.COLOR, "#e8833a", emptyMap()))
        put("chart-2", CssToken("chart-2", TokenKind.COLOR, "#3b82f6", emptyMap()))
        put("chart-3", CssToken("chart-3", TokenKind.COLOR, "#8b5cf6", emptyMap()))
        put("chart-4", CssToken("chart-4", TokenKind.COLOR, "#10b981", emptyMap()))
        put("chart-5", CssToken("chart-5", TokenKind.COLOR, "#ef4444", emptyMap()))
        put("chart-6", CssToken("chart-6", TokenKind.COLOR, "#d9a832", emptyMap()))
        put("chart-7", CssToken("chart-7", TokenKind.COLOR, "#06b6d4", emptyMap()))
        put("chart-8", CssToken("chart-8", TokenKind.COLOR, "#ec4899", emptyMap()))
        put("chart-9", CssToken("chart-9", TokenKind.COLOR, "#84cc16", emptyMap()))
        put("chart-10", CssToken("chart-10", TokenKind.COLOR, "#6366f1", emptyMap()))
        put("chart-11", CssToken("chart-11", TokenKind.COLOR, "#14b8a6", emptyMap()))
        put("chart-12", CssToken("chart-12", TokenKind.COLOR, "#f97316", emptyMap()))
        put("chart-13", CssToken("chart-13", TokenKind.COLOR, "#a855f7", emptyMap()))
        put("chart-14", CssToken("chart-14", TokenKind.COLOR, "#0ea5e9", emptyMap()))
        put("chart-15", CssToken("chart-15", TokenKind.COLOR, "#22c55e", emptyMap()))
        put("chart-16", CssToken("chart-16", TokenKind.COLOR, "#eab308", emptyMap()))
        put("fab-size", CssToken("fab-size", TokenKind.LENGTH, "56px", emptyMap()))
        put("icon-size", CssToken("icon-size", TokenKind.LENGTH, "44px", emptyMap()))
        put("switch-w", CssToken("switch-w", TokenKind.LENGTH, "56px", emptyMap()))
        put("switch-track-w", CssToken("switch-track-w", TokenKind.LENGTH, "52px", emptyMap()))
        put("switch-track-h", CssToken("switch-track-h", TokenKind.LENGTH, "32px", emptyMap()))
        put("knob-size", CssToken("knob-size", TokenKind.LENGTH, "24px", emptyMap()))
        put("ring-size", CssToken("ring-size", TokenKind.LENGTH, "128px", emptyMap()))
        put("heat-h", CssToken("heat-h", TokenKind.LENGTH, "44px", emptyMap()))
        put("spark-h", CssToken("spark-h", TokenKind.LENGTH, "28px", emptyMap()))
        put("chart-axis-fs", CssToken("chart-axis-fs", TokenKind.FONT_SIZE, "11px", emptyMap()))
        put("chart-h", CssToken("chart-h", TokenKind.LENGTH, "148px", emptyMap()))
        put("dot-size", CssToken("dot-size", TokenKind.LENGTH, "9px", emptyMap()))
        put("dur-press", CssToken("dur-press", TokenKind.DURATION, "120ms", emptyMap()))
        put("dur-state", CssToken("dur-state", TokenKind.DURATION, "120ms", emptyMap()))
        put("dur-overlay-in", CssToken("dur-overlay-in", TokenKind.DURATION, "220ms", emptyMap()))
        put("dur-overlay-out", CssToken("dur-overlay-out", TokenKind.DURATION, "150ms", emptyMap()))
        put("dur-page", CssToken("dur-page", TokenKind.DURATION, "340ms", emptyMap()))
        put("dur-chart", CssToken("dur-chart", TokenKind.DURATION, "600ms", emptyMap()))
        put("dur-chart-fast", CssToken("dur-chart-fast", TokenKind.DURATION, "480ms", emptyMap()))
        put("stagger", CssToken("stagger", TokenKind.DURATION, "24ms", emptyMap()))
        put("ease-spring", CssToken("ease-spring", TokenKind.EASING, "cubic-bezier(0.34, 1.56, 0.64, 1)", emptyMap()))
        put("ease-out", CssToken("ease-out", TokenKind.EASING, "cubic-bezier(0.2, 0, 0, 1)", emptyMap()))
        put("ease-in", CssToken("ease-in", TokenKind.EASING, "cubic-bezier(0.4, 0, 1, 1)", emptyMap()))
        put(
            "splash-bg",
            CssToken(
                "splash-bg",
                TokenKind.COLOR,
                "#ffffff",
                mapOf(
                "glassmorphism" to "#eef6ff",
                "claude" to "#f5f4ed",
                "kami" to "#f5f4ed",
                "bento" to "#f5f8ff",
                ),
            ),
        )
        put(
            "splash-ink",
            CssToken(
                "splash-ink",
                TokenKind.COLOR,
                "#1d1d1f",
                mapOf(
                "glassmorphism" to "#102033",
                "notion" to "rgba(0, 0, 0, 0.95)",
                "stripe" to "#061b31",
                "claude" to "#141413",
                "ibm" to "#161616",
                "kami" to "#141413",
                "bento" to "#101828",
                ),
            ),
        )
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
