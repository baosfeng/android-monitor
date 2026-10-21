package com.bsfeng.monitor.ui

import com.bsfeng.monitor.ui.foundation.PRESS_SCALE_TARGET
import com.bsfeng.monitor.ui.foundation.pressScaleTargetFor
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 按下反馈回归：缩放目标只有 0.96 / 1f 两个取值，REDUCED（系统动画缩放 = 0）恒为 1f。
 *
 * [pressScaleTargetFor] 是从 Composable 里抽出来的纯函数 —— 缩放画面本身要上设备看，
 * 但「关掉动效的用户不该看到缩放」这条最容易回归，用单测钉死。
 */
class PressFeedbackTest {

    @Test
    fun `允许动效时按下缩到零点九六、松手回到一点零`() {
        assertEquals(PRESS_SCALE_TARGET, pressScaleTargetFor(motionEnabled = true, pressed = true, isPressedNow = true), 0.0001f)
        assertEquals(1f, pressScaleTargetFor(motionEnabled = true, pressed = true, isPressedNow = false), 0.0001f)
    }

    @Test
    fun `REDUCED 档（系统动画缩放为零）按压缩放恒为一点零`() {
        // 关键回归：关掉动画的用户按下时不该出现任何缩放
        assertEquals(1f, pressScaleTargetFor(motionEnabled = false, pressed = true, isPressedNow = true), 0.0001f)
        assertEquals(1f, pressScaleTargetFor(motionEnabled = false, pressed = true, isPressedNow = false), 0.0001f)
    }

    @Test
    fun `不参与缩放的控件（禁用态）按下也不缩`() {
        assertEquals(1f, pressScaleTargetFor(motionEnabled = true, pressed = false, isPressedNow = true), 0.0001f)
    }

    @Test
    fun `缩放目标与原型一致为百分之九十六`() {
        assertEquals(0.96f, PRESS_SCALE_TARGET, 0.0001f)
    }
}
