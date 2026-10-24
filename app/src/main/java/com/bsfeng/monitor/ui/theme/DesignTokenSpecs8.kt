// ============================================================================
// DesignTokenSpecs8.kt —— 生成文件，请勿手改。
// 职责：SPECS 分片 （第 200–243 个 token：income-text … splash-ink），由 DesignTokenTable 按原顺序合并。
// 来源：.open-design/08-prototype.html 的 :root 与 [data-theme] 块
// 生成器：tools/gen-design-tokens.mjs（重跑：node tools/gen-design-tokens.mjs）
// ============================================================================
package com.bsfeng.monitor.ui.theme

/**
 * 把本分片的 44 个 token 追加进接收者映射。
 * 由 DesignTokenTable.SPECS 的 buildMap 依次调用，顺序即声明顺序。
 */
internal fun MutableMap<String, CssToken>.putDesignTokenSpecs8() {
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
