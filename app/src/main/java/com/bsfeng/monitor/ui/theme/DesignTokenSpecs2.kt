// ============================================================================
// DesignTokenSpecs2.kt —— 生成文件，请勿手改。
// 职责：SPECS 分片 （第 12–23 个 token：accent-hover … text-lg），由 DesignTokenTable 按原顺序合并。
// 来源：.open-design/08-prototype.html 的 :root 与 [data-theme] 块
// 生成器：tools/gen-design-tokens.mjs（重跑：node tools/gen-design-tokens.mjs）
// ============================================================================
package com.bsfeng.monitor.ui.theme

/**
 * 把本分片的 12 个 token 追加进接收者映射。
 * 由 DesignTokenTable.SPECS 的 buildMap 依次调用，顺序即声明顺序。
 */
internal fun MutableMap<String, CssToken>.putDesignTokenSpecs2() {
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
}
