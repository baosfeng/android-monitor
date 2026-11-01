package com.bsfeng.monitor.ui.components

import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.DEFAULT_APP_BUTTON_TONE
import com.bsfeng.monitor.ui.foundation.HapticTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享组件默认值回归（口径口径 §二 点击区 / §三 触感）。
 *
 * - AppSwitch：视觉轨道 52×32 不变，点击域 ≥ 44×44（回归：改动前点击区实测 52×32）；
 * - AppButton：默认 LIGHT；主操作 SUCCESS / 破坏性 REJECT 必须由调用方显式指定
 *   （回归：改动前 performConfirmHaptic → SUCCESS，全站 25 处普通按钮都震「成功」）。
 *
 * 焦点环 2dp 已由 [FocusRingTest] 钉住，本类不重复。
 */
class SharedComponentDefaultsTest {

    // 本项目 token：switchTrackW = 52dp、switchTrackH = 32dp、tapMin = 44dp（--tap-min）。
    private val trackW = 52.dp
    private val trackH = 32.dp
    private val tapMin = 44.dp

    @Test
    fun 开关点击区撑到四十四_宽度不变() {
        val (w, h) = switchTouchSize(trackW, trackH, tapMin)
        assertEquals("轨道宽度 52dp 已 ≥ 44dp，宽度不该被改", trackW, w)
        assertEquals("轨道高度 32dp < 44dp，点击域补到 44dp", tapMin, h)
        assertTrue("点击区宽度 ≥ 44dp", w >= tapMin)
        assertTrue("点击区高度 ≥ 44dp", h >= tapMin)
    }

    @Test
    fun 触达尺寸与视觉尺寸可区分() {
        // 函数只产出外层触达尺寸；视觉轨道由内层按原尺寸绘制，两者高度不同是预期的
        val (w, h) = switchTouchSize(trackW, trackH, tapMin)
        assertEquals(trackW, w)
        assertNotEquals("高度被撑开才说明触达修好了", trackH, h)
    }

    @Test
    fun 普通按钮默认触感是_LIGHT() {
        assertEquals(HapticTone.LIGHT, DEFAULT_APP_BUTTON_TONE)
    }

    @Test
    fun 默认触感既不是成功也不是拒绝() {
        assertNotEquals(HapticTone.SUCCESS, DEFAULT_APP_BUTTON_TONE)
        assertNotEquals(HapticTone.REJECT, DEFAULT_APP_BUTTON_TONE)
    }
}
