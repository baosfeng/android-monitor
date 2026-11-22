package com.bsfeng.monitor.finance.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import com.bsfeng.monitor.ui.theme.monitorMonoNumber
import com.bsfeng.monitor.ui.theme.monitorTypography
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * s07「本月日均」卡的文案与字阶回归（[monthDailyAverageNote] / [S07_DAILY_AVG_UNIT]）。
 *
 * 原型逐字：大号数字 ¥176（.kpi-value 28px/700 mono）、单位 元/天（.kpi-unit 14px/500 muted）、
 * 口径行「本月累计 ¥2,468 · 已过 14 天」（.meta 14px muted）。
 * 这里钉住的是「实现侧用的档位 == 原型档位」，屏内调用点见 FinanceStatsDailyAvgCard。
 */
class FinanceS07DailyAvgCopyTest {

    @Test
    fun `口径行与原型逐字一致`() {
        assertEquals("本月累计 ¥2,468 · 已过 14 天", monthDailyAverageNote(2468.0, 14))
        // 千分位与 0 位小数与大号数字同精度
        assertEquals("本月累计 ¥0 · 已过 0 天", monthDailyAverageNote(0.0, 0))
        assertEquals("本月累计 ¥12,345 · 已过 31 天", monthDailyAverageNote(12345.0, 31))
    }

    @Test
    fun `单位文案是元每天`() {
        assertEquals("元/天", S07_DAILY_AVG_UNIT)
    }

    @Test
    fun `大号数字走等宽二十八像素粗体`() {
        // 原型 .kpi-value：font-mono + --text-xl 28px + 700
        assertEquals(FontFamily.Monospace, monitorMonoNumber.fontFamily)
        assertEquals(28f, monitorMonoNumber.fontSize.value, 0.01f)
        assertEquals(FontWeight.Bold, monitorMonoNumber.fontWeight)
    }

    @Test
    fun `单位与口径行都是十四像素档`() {
        // 原型 .kpi-unit 14px/500 与 .meta 14px：实现分别走 labelMedium / bodySmall
        assertEquals(14f, monitorTypography.labelMedium.fontSize.value, 0.01f)
        assertEquals(FontWeight.Medium, monitorTypography.labelMedium.fontWeight)
        assertEquals(14f, monitorTypography.bodySmall.fontSize.value, 0.01f)
        // 卡片标题 21sp（原型 .h2）
        assertEquals(21f, monitorTypography.headlineSmall.fontSize.value, 0.01f)
    }
}
