package com.bsfeng.monitor.ui

import org.junit.Assert.assertTrue
import org.junit.Test

class AdScreenLabelsTest {

    @Test
    fun `monthDayLabel returns localized month-day text`() {
        val label = monthDayLabel(System.currentTimeMillis())
        assertTrue(label.contains("月"))
        assertTrue(label.contains("日"))
    }
}
