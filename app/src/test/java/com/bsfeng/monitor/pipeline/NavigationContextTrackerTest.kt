package com.bsfeng.monitor.pipeline

import org.junit.Assert.*
import org.junit.Test

class NavigationContextTrackerTest {

    @Test
    fun `onScreenChanged adds frame to stack`() {
        val tracker = NavigationContextTracker()
        tracker.onScreenChanged(ContextFrame(
            timestampMs = 1000L,
            packageName = "com.example",
            className = ".MainActivity",
            screenLabel = "主页"
        ))
        assertEquals(1, tracker.getCurrentContext().size)
    }

    @Test
    fun `getCurrentContext returns all frames in order`() {
        val tracker = NavigationContextTracker()
        tracker.onScreenChanged(ContextFrame(1000L, "com.a", "", "页面A", source = null))
        tracker.onScreenChanged(ContextFrame(2000L, "com.b", "", "页面B", source = "a"))
        val ctx = tracker.getCurrentContext()
        assertEquals(2, ctx.size)
        assertEquals("页面A", ctx[0].screenLabel)
        assertEquals("页面B", ctx[1].screenLabel)
    }

    @Test
    fun `navigateBack pops until matching label`() {
        val tracker = NavigationContextTracker()
        tracker.onScreenChanged(ContextFrame(1000L, "com.a", "", "页面A"))
        tracker.onScreenChanged(ContextFrame(2000L, "com.b", "", "页面B"))
        tracker.onScreenChanged(ContextFrame(3000L, "com.c", "", "页面C"))
        tracker.navigateBack("页面A")
        assertEquals(1, tracker.getCurrentContext().size)
        assertEquals("页面A", tracker.getCurrentContext().first().screenLabel)
    }

    @Test
    fun `clear empties stack`() {
        val tracker = NavigationContextTracker()
        tracker.onScreenChanged(ContextFrame(1000L, "com.a", "", "页面A"))
        tracker.clear()
        assertTrue(tracker.getCurrentContext().isEmpty())
    }
}
