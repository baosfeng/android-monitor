package com.bsfeng.monitor.ui.components

import org.junit.Assert.assertEquals
import org.junit.Test

class ChartSupportTest {

    @Test
    fun `normalize maps min to zero and max to one`() {
        assertEquals(listOf(0f, 0.5f, 1f), normalize(listOf(2f, 4f, 6f)))
    }

    @Test
    fun `normalize handles flat data`() {
        assertEquals(listOf(0f, 0f, 0f), normalize(listOf(3f, 3f, 3f)))
    }

    @Test
    fun `normalizeByMax does not min-max stretch`() {
        assertEquals(listOf(0.33333334f, 0.6666667f, 1f), normalizeByMax(listOf(1f, 2f, 3f)))
    }

    @Test
    fun `chartValueLabel drops integer fraction`() {
        assertEquals("42", chartValueLabel(42f))
        assertEquals("4.2", chartValueLabel(4.16f))
    }
}
