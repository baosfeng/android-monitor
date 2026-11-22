package com.bsfeng.monitor.finance.ui

// 来源：原型 s04 统计汇总 pane 的图表 / 图注 / 五态口径（.open-design/src/screens/s04-记账.html:177-289）；
// 职责：钉住 [FinancePeriodDerived] 里「档位 → 直标口径 / 抽稀步长 / 图注 / 五态门控」这几条纯派生，
// 都是无 Android 依赖的纯函数与常量，JVM 直跑。

import com.bsfeng.monitor.finance.FinancePeriod
import com.bsfeng.monitor.finance.model.PeriodBucket
import com.bsfeng.monitor.finance.model.PeriodStats
import com.bsfeng.monitor.ui.components.ChartValueLabels
import com.bsfeng.monitor.ui.components.chartXLabelsOf
import com.bsfeng.monitor.ui.components.chartXTicksByStep
import com.bsfeng.monitor.ui.components.FiveState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FinancePeriodDerivedTest {

    @Test
    fun `热力档不直标其余档只标峰值`() {
        // s04:201 / 208 / 276 三张柱图都写 data-chart-vlabels='peak'；热力是色阶不是柱（99-app.js:816-821 无 text）
        assertEquals(ChartValueLabels.None, periodValueLabels(FinancePeriod.DAY))
        assertEquals(ChartValueLabels.Peak, periodValueLabels(FinancePeriod.WEEK))
        assertEquals(ChartValueLabels.Peak, periodValueLabels(FinancePeriod.MONTH))
        assertEquals(ChartValueLabels.Peak, periodValueLabels(FinancePeriod.YEAR))
        assertEquals(ChartValueLabels.Peak, periodValueLabels(FinancePeriod.ALL))
    }

    @Test
    fun `X 轴抽稀步长只有年档与月档`() {
        // 年档 12 桶跳奇数 = 1/3/5/7/9/11（99-app.js:661-670）；月档 data-chart-tick='5'（s04:208）
        assertEquals(2, periodXTickStep(FinancePeriod.YEAR))
        assertEquals(5, periodXTickStep(FinancePeriod.MONTH))
        // 日档是 5 个格边界、周档 7 根 ≤ 阈值、全部档 9 个年份放宽后全标：都不走步长
        assertNull(periodXTickStep(FinancePeriod.DAY))
        assertNull(periodXTickStep(FinancePeriod.WEEK))
        assertNull(periodXTickStep(FinancePeriod.ALL))
    }

    @Test
    fun `图注只在周档以外补`() {
        // 周档 7 根柱顶已直标峰值，原型 s04:201 也没有说明行；其余档原型有或需要峰值 / 口径说明
        assertFalse(periodShowsValueNote(FinancePeriod.WEEK))
        assertTrue(periodShowsValueNote(FinancePeriod.DAY))
        assertTrue(periodShowsValueNote(FinancePeriod.MONTH))
        assertTrue(periodShowsValueNote(FinancePeriod.YEAR))
        assertTrue(periodShowsValueNote(FinancePeriod.ALL))
    }

    @Test
    fun `年度趋势卡十二桶按原型跳奇数`() {
        // 这张卡与五档年档同一口径；不传步长会退回等距抽稀
        assertEquals(periodXTickStep(FinancePeriod.YEAR), S04_YEAR12_X_TICK_STEP)
        val labels = (1..12).map { it.toString() }
        assertEquals(
            listOf("1", "3", "5", "7", "9", "11"),
            chartXTicksByStep(labels, S04_YEAR12_X_TICK_STEP, keepLast = false).map { it.second },
        )
        // 钉住「为什么必须传」：旧等距抽稀给 1/3/5/7/8/10/12（7 与 8 相邻，模拟器实测）
        assertEquals(listOf("1", "3", "5", "7", "8", "10", "12"), chartXLabelsOf(labels).map { it.second })
    }

    @Test
    fun `图注归一措辞按图形分`() {
        // 柱档（周 / 月）说柱高；年 / 全部档是面积图，说曲线（模拟器验收：面积图下写「柱高」找不到柱）
        assertEquals("柱高按本档最大值归一", periodValueNoteScale(FinancePeriod.WEEK))
        assertEquals("柱高按本档最大值归一", periodValueNoteScale(FinancePeriod.MONTH))
        assertEquals("曲线按本档最大值归一", periodValueNoteScale(FinancePeriod.YEAR))
        assertEquals("曲线按本档最大值归一", periodValueNoteScale(FinancePeriod.ALL))
        // 空数据也按图形给措辞
        assertEquals("曲线按本档最大值归一", periodValueNote(FinancePeriod.YEAR, null))
        // 真图注末尾与 scaleNote 同一措辞
        val stats = PeriodStats(buckets = listOf(PeriodBucket("1", 120.0), PeriodBucket("2", 293.0)))
        assertTrue(periodValueNote(FinancePeriod.YEAR, stats).endsWith("曲线按本档最大值归一"))
        assertTrue(periodValueNote(FinancePeriod.MONTH, stats).endsWith("柱高按本档最大值归一"))
    }

    @Test
    fun `五态门控只藏 KPI 多周期与双入口三块`() {
        // 原型 s04:227 / 242 / 259 带 data-state-hide="loading empty error edge"
        assertEquals(setOf("statskpis", "multiperiod", "entries"), FINANCE_STATS_PANE_STATE_GATED)
        // 查询卡（s04:177）与三图卡（s04:274-288）任何态都显示
        listOf("periodcard", "charts").forEach { section ->
            FiveState.entries.forEach { state ->
                assertTrue("$section 在 $state 下都应渲染", financeStatsSectionVisible(section, state))
            }
        }
        // 三块只在有数据态出现
        FINANCE_STATS_PANE_STATE_GATED.forEach { section ->
            assertTrue(financeStatsSectionVisible(section, FiveState.POPULATED))
            listOf(FiveState.LOADING, FiveState.EMPTY, FiveState.ERROR, FiveState.EDGE).forEach { state ->
                assertFalse("$section 在 $state 下应隐藏", financeStatsSectionVisible(section, state))
            }
        }
    }
}
