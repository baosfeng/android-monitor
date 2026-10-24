// ============================================================================
// DesignTokenSpecs1.kt —— 生成文件，请勿手改。
// 职责：SPECS 分片 （第 1–11 个 token：bg … accent-on），由 DesignTokenTable 按原顺序合并。
// 来源：.open-design/08-prototype.html 的 :root 与 [data-theme] 块
// 生成器：tools/gen-design-tokens.mjs（重跑：node tools/gen-design-tokens.mjs）
// ============================================================================
package com.bsfeng.monitor.ui.theme

/**
 * 把本分片的 11 个 token 追加进接收者映射。
 * 由 DesignTokenTable.SPECS 的 buildMap 依次调用，顺序即声明顺序。
 */
internal fun MutableMap<String, CssToken>.putDesignTokenSpecs1() {
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
}
