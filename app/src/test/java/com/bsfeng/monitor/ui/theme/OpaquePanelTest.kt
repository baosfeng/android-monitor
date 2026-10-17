package com.bsfeng.monitor.ui.theme

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 浮层面板不透明度测试（纯 JVM）。
 *
 * 真机反馈「弹窗背景还是透明的」：玻璃拟态主题的 surface 是 rgba(255,255,255,.74)、
 * surface-warm 是 rgba(238,246,255,.72)，只叠这两层 alpha 仍有 0.927 ——
 * 必须再压一层不透明的 bg 才是真的实底。这组用例把 8 套主题都钉住。
 */
class OpaquePanelTest {

    @Test
    fun `8 套主题的浮层面板都必须百分百不透明`() {
        DesignTokenTable.THEME_IDS.forEach { themeId ->
            val tokens = DesignTokens(themeId)
            assertEquals(
                "主题 $themeId 的面板仍带透明度，会透出下层页面文字",
                1f,
                tokens.opaquePanel.alpha,
                0.0001f
            )
        }
    }

    @Test
    fun `玻璃拟态面板合成为预期实色而不是原半透明色`() {
        val tokens = DesignTokens("glassmorphism")
        val panel = tokens.opaquePanel

        assertEquals(1f, panel.alpha, 0.0001f)
        // surface-warm(.72) → surface(.74) → bg(#eef6ff) 的理论合成为 #f2f8ff
        assertEquals("红通道", 242f, panel.red * 255f, 1.5f)
        assertEquals("绿通道", 248f, panel.green * 255f, 1.5f)
        assertEquals("蓝通道", 255f, panel.blue * 255f, 1.5f)
    }

    @Test
    fun `非玻璃主题的面板与原 surface-warm 保持同一色相`() {
        val tokens = DesignTokens("apple")
        val panel = tokens.opaquePanel
        // apple 的 surface-warm 与 surface 都是实色，合成结果就是 surface-warm 本身
        assertEquals(tokens.surfaceWarm.red, panel.red, 0.0001f)
        assertEquals(tokens.surfaceWarm.green, panel.green, 0.0001f)
        assertEquals(tokens.surfaceWarm.blue, panel.blue, 0.0001f)
        assertEquals(1f, panel.alpha, 0.0001f)
    }
}
