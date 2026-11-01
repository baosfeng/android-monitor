package com.bsfeng.monitor.ui

// 共享行 TokenRow 的按压反馈判定（纯 JVM，不依赖 Compose 运行时）。
//
// P1：TokenRow 只有 clickable、没有按压缩放与触感，而锚点 s09-goto-* 要求
// 「整行按压缩到 0.96（120ms，LIGHT）」。修法是接 pressFeedback 基元 + LIGHT，
// 但**纯展示行不能跟着缩放**（s12-rows「静态行点按无变化、不进 Tab 顺序」）——
// 这条判定抽成纯函数后用单测钉死，避免以后一刀切给所有行加反馈。

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenRowPressFeedbackTest {

    @Test
    fun `纯展示行不参与按压反馈`() {
        assertFalse(tokenRowPressFeedback(onClick = null, onLongClick = null))
    }

    @Test
    fun `可点击行参与按压反馈`() {
        assertTrue(tokenRowPressFeedback(onClick = {}, onLongClick = null))
    }

    @Test
    fun `仅可长按的行也参与按压反馈`() {
        // 版本行只挂了长按（解锁调试入口），按下同样该有 0.96 + LIGHT
        assertTrue(tokenRowPressFeedback(onClick = null, onLongClick = {}))
    }

    @Test
    fun `两个回调都挂时参与按压反馈`() {
        assertTrue(tokenRowPressFeedback(onClick = {}, onLongClick = {}))
    }
}
