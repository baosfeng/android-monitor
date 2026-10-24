// ============================================================================
// DesignTokenSpecs4.kt —— 生成文件，请勿手改。
// 职责：SPECS 分片 （第 42–53 个 token：radius-sm … container-max），由 DesignTokenTable 按原顺序合并。
// 来源：.open-design/08-prototype.html 的 :root 与 [data-theme] 块
// 生成器：tools/gen-design-tokens.mjs（重跑：node tools/gen-design-tokens.mjs）
// ============================================================================
package com.bsfeng.monitor.ui.theme

/**
 * 把本分片的 12 个 token 追加进接收者映射。
 * 由 DesignTokenTable.SPECS 的 buildMap 依次调用，顺序即声明顺序。
 */
internal fun MutableMap<String, CssToken>.putDesignTokenSpecs4() {
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
}
