// ============================================================================
// DesignTokenSpecs6.kt —— 生成文件，请勿手改。
// 职责：SPECS 分片 （第 169–187 个 token：frame-a … backdrop-b），由 DesignTokenTable 按原顺序合并。
// 来源：.open-design/08-prototype.html 的 :root 与 [data-theme] 块
// 生成器：tools/gen-design-tokens.mjs（重跑：node tools/gen-design-tokens.mjs）
// ============================================================================
package com.bsfeng.monitor.ui.theme

/**
 * 把本分片的 19 个 token 追加进接收者映射。
 * 由 DesignTokenTable.SPECS 的 buildMap 依次调用，顺序即声明顺序。
 */
internal fun MutableMap<String, CssToken>.putDesignTokenSpecs6() {
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
}
