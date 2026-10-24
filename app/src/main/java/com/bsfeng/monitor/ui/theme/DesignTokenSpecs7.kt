// ============================================================================
// DesignTokenSpecs7.kt —— 生成文件，请勿手改。
// 职责：SPECS 分片 （第 188–199 个 token：glass-bg … accent-text），由 DesignTokenTable 按原顺序合并。
// 来源：.open-design/08-prototype.html 的 :root 与 [data-theme] 块
// 生成器：tools/gen-design-tokens.mjs（重跑：node tools/gen-design-tokens.mjs）
// ============================================================================
package com.bsfeng.monitor.ui.theme

/**
 * 把本分片的 12 个 token 追加进接收者映射。
 * 由 DesignTokenTable.SPECS 的 buildMap 依次调用，顺序即声明顺序。
 */
internal fun MutableMap<String, CssToken>.putDesignTokenSpecs7() {
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
}
