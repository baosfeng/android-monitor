// ============================================================================
// DesignTokenSpecs3.kt —— 生成文件，请勿手改。
// 职责：SPECS 分片 （第 24–41 个 token：text-xl … section-y-phone），由 DesignTokenTable 按原顺序合并。
// 来源：.open-design/08-prototype.html 的 :root 与 [data-theme] 块
// 生成器：tools/gen-design-tokens.mjs（重跑：node tools/gen-design-tokens.mjs）
// ============================================================================
package com.bsfeng.monitor.ui.theme

/**
 * 把本分片的 18 个 token 追加进接收者映射。
 * 由 DesignTokenTable.SPECS 的 buildMap 依次调用，顺序即声明顺序。
 */
internal fun MutableMap<String, CssToken>.putDesignTokenSpecs3() {
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
}
