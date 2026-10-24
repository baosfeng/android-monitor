// ============================================================================
// DesignTokenSpecs5.kt —— 生成文件，请勿手改。
// 职责：SPECS 分片 （第 54–168 个 token：container-gutter-desktop … page-bg），由 DesignTokenTable 按原顺序合并。
// 来源：.open-design/08-prototype.html 的 :root 与 [data-theme] 块
// 生成器：tools/gen-design-tokens.mjs（重跑：node tools/gen-design-tokens.mjs）
// ============================================================================
package com.bsfeng.monitor.ui.theme

/**
 * 把本分片的 115 个 token 追加进接收者映射。
 * 由 DesignTokenTable.SPECS 的 buildMap 依次调用，顺序即声明顺序。
 */
internal fun MutableMap<String, CssToken>.putDesignTokenSpecs5() {
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
}
