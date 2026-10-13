package com.bsfeng.monitor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [ActivityNames] 单元测试。
 *
 * 回归防护：真机 30 份快照里 12 份把 widget 类名当成了 Activity，导致 `activityIds` 规则静默失效。
 */
class ActivityNamesTest {

    // ==================== 正常路径 ====================

    @Test
    fun `应用自己的 Activity 类名被识别`() {
        assertTrue(ActivityNames.isActivityClass("com.qq.reader.activity.launch.DefaultAliasSplashActivity"))
        assertTrue(ActivityNames.isActivityClass("com.xingin.xhs.index.v2.IndexActivityV2"))
    }

    @Test
    fun `同一 App 内的 widget 类事件保留已知 Activity`() {
        val current = "tv.danmaku.bili.MainActivityV2"
        assertEquals(current, ActivityNames.resolveActivityClass(current, "android.widget.TextView", false))
        assertEquals(current, ActivityNames.resolveActivityClass(current, "android.widget.FrameLayout", false))
    }

    @Test
    fun `真的 Activity 变化时更新`() {
        assertEquals(
            "com.demo.DetailActivity",
            ActivityNames.resolveActivityClass("com.demo.MainActivity", "com.demo.DetailActivity", false)
        )
    }

    // ==================== 边界条件 ====================

    @Test
    fun `切换 App 后遇到 widget 类事件时清空而不是沿用旧 App 的 Activity`() {
        assertEquals("", ActivityNames.resolveActivityClass("tv.danmaku.bili.MainActivityV2", "android.widget.TextView", true))
    }

    @Test
    fun `切换 App 后遇到 Activity 事件时直接采用`() {
        assertEquals(
            "com.jingdong.app.mall.MainFrameActivity",
            ActivityNames.resolveActivityClass("tv.danmaku.bili.MainActivityV2", "com.jingdong.app.mall.MainFrameActivity", true)
        )
    }

    @Test
    fun `空类名不算 Activity 且不会覆盖已知值`() {
        assertFalse(ActivityNames.isActivityClass(""))
        assertEquals("com.demo.MainActivity", ActivityNames.resolveActivityClass("com.demo.MainActivity", "", false))
        assertEquals("", ActivityNames.resolveActivityClass("com.demo.MainActivity", "", true))
    }

    // ==================== 异常场景 ====================

    @Test
    fun `系统 UI 与输入法类名被排除`() {
        listOf(
            "android.view.View",
            "android.webkit.WebView",
            "android.app.Dialog",
            "android.inputmethodservice.SoftInputWindow",
            "com.android.internal.app.AlertActivity"
        ).forEach { assertFalse(it, ActivityNames.isActivityClass(it)) }
    }

    @Test
    fun `应用详情那种 pkg_cls 形式不算 Activity`() {
        assertFalse(ActivityNames.isActivityClass("com.demo.app/com.demo.app.MainActivity"))
    }

    @Test
    fun `没有已知 Activity 时 widget 事件不会凭空造出一个`() {
        assertEquals("", ActivityNames.resolveActivityClass("", "android.widget.TextView", false))
    }
}
