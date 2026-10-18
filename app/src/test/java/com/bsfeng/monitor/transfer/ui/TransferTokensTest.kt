package com.bsfeng.monitor.transfer.ui

import com.bsfeng.monitor.ui.components.MotionToken
import com.bsfeng.monitor.ui.components.durationMsOf
import com.bsfeng.monitor.ui.theme.DesignTokenTable
import com.bsfeng.monitor.ui.theme.DesignTokens
import com.bsfeng.monitor.ui.theme.accentFade
import com.bsfeng.monitor.ui.theme.fgFade
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「传送」页 8 套主题取 token 自查（纯 JVM，与 checkUiStyle 静态检查互补）。
 *
 * 静态侧：`checkUiStyle` 在 preBuild 拦 `Color(0xFF......)` / `fontSize = X.sp`；
 * 运行时侧（本用例）：逐主题核对「传送」页引用的每个 token 都能从 [DesignTokenTable] 取到值，
 * 且强调色 / 语义色随主题变化——证明页面没有写死色值。
 */
class TransferTokensTest {

    /** 「传送」页直接引用的 token（TransferScreen.kt + TransferService 通知配色之外的全部 UI token）。 */
    private val usedTokens = listOf(
        "accent", "accent-on", "surface", "surface-warm", "fg", "fg-2", "muted",
        "border-soft", "success", "danger", "scrim",
    )

    /** 「传送」页引用的时长 / 曲线 token。 */
    private val usedMotionTokens = listOf(
        "dur-press", "dur-state", "dur-overlay-in", "dur-overlay-out", "dur-page",
        "stagger", "ease-in", "ease-out", "ease-spring", "ease-standard",
    )

    @Test
    fun `8 套主题下传送页引用的每个 token 都有值`() {
        assertEquals("主题数应为 8", 8, DesignTokenTable.THEME_IDS.size)
        DesignTokenTable.THEME_IDS.forEach { themeId ->
            val tokens = DesignTokens(themeId)
            usedTokens.forEach { name ->
                val raw = DesignTokenTable.raw(themeId, name)
                assertTrue("主题 $themeId 的 $name 为空", raw.isNotBlank())
            }
            // 颜色 token 与 DesignTokenTable 同源（证明没有屏内手抄色值）
            assertEquals(DesignTokenTable.colorOf(themeId, "accent"), tokens.accent)
            assertEquals(DesignTokenTable.colorOf(themeId, "danger"), tokens.danger)
            assertEquals(DesignTokenTable.colorOf(themeId, "success"), tokens.success)
            assertEquals(DesignTokenTable.colorOf(themeId, "surface"), tokens.surface)
            // 半透明覆盖由 token 派生（进度轨道 / PIN 底 / 选中胶囊）
            assertEquals(tokens.fg.alpha * 0.1f, tokens.fgFade(10).alpha, 0.01f)
            assertEquals(tokens.accent.alpha * 0.12f, tokens.accentFade(12).alpha, 0.01f)
        }
    }

    @Test
    fun `8 套主题下强调色与语义色都不同`() {
        val accents = DesignTokenTable.THEME_IDS.map { DesignTokens(it).accent }
        assertTrue("8 套主题的强调色不应全都相同", accents.distinct().size >= 4)

        DesignTokenTable.THEME_IDS.forEach { themeId ->
            val tokens = DesignTokens(themeId)
            assertNotEquals(
                "主题 $themeId 的完成色与失败色不应同色",
                tokens.success.value,
                tokens.danger.value,
            )
        }
    }

    @Test
    fun `传送页动效 token 与协议第七节表格对齐`() {
        val tokens = DesignTokens(DesignTokenTable.THEME_IDS.first())
        // 进度 120ms / 配对弹层 220ms 进 150ms 出 / 列表入场 340ms + 逐项 24ms
        assertEquals(120, tokens.durationMsOf(MotionToken.State))
        assertEquals(220, tokens.durationMsOf(MotionToken.OverlayIn))
        assertEquals(150, tokens.durationMsOf(MotionToken.OverlayOut))
        assertEquals(340, tokens.durationMsOf(MotionToken.Page))
        assertEquals(24, tokens.stagger)
    }
}
