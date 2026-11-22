package com.bsfeng.monitor.finance.ui

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.ui.theme.monitorMonoKpi
import com.bsfeng.monitor.ui.theme.monitorMonoValue
import com.bsfeng.monitor.ui.theme.monitorTypography
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * s04 记账首页「面板归属 / 段落顺序 / 行与徽标尺寸」的回归（纯函数 + 常量，无时间、无随机）。
 *
 * 原型口径（.open-design/src/screens/s04-记账.html）：
 * - 交易明细 pane 是**纯列表**（115-175）：页头 → 月份条 → 子 Tab 条 → 「交易明细」标题 → 日期分组；
 * - KPI 三值卡（227-240）、多周期总花费（241-256）、双入口（259-272）、三图卡（274-288）都在统计 pane（177-289），
 *   顺序即 DOM 顺序：查询卡 → KPI → 多周期 → 双入口 → 三图卡；
 * - .list-row min-height 48px（20-base.html:224）；.tx-auto 高 18px、左右 6px（s04:42）。
 */
class FinanceS04LayoutTest {

    @Test
    fun `交易明细 pane 只保留页头与明细列表`() {
        assertEquals(listOf("head", "month", "listhead"), FINANCE_DETAIL_PANE_ITEMS)
        // KPI 卡 / 双入口卡的 item key 不应再出现在明细 pane 的页头清单里
        assertFalse(FINANCE_DETAIL_PANE_ITEMS.contains("kpis"))
        assertFalse(FINANCE_DETAIL_PANE_ITEMS.contains("entries"))
        assertEquals(FINANCE_DETAIL_PANE_ITEMS.size, FINANCE_MAIN_HEADER_ITEMS)
    }

    @Test
    fun `统计汇总 pane 的段落顺序与原型 DOM 顺序一致`() {
        assertEquals(
            listOf("periodcard", "statskpis", "multiperiod", "entries", "charts"),
            FINANCE_STATS_PANE_SECTIONS,
        )
    }

    @Test
    fun `本月 KPI 卡排在多周期总花费之前`() {
        // 原型 227-240 的 s04-kpis 在 241-256 的「多周期总花费」之前；反过来就会让视线第一落点变成五档支出
        val kpi = FINANCE_STATS_PANE_SECTIONS.indexOf("statskpis")
        val multi = FINANCE_STATS_PANE_SECTIONS.indexOf("multiperiod")
        assertTrue("KPI 卡应先于多周期总花费出现", kpi >= 0 && multi >= 0 && kpi < multi)
    }

    @Test
    fun `统计 pane 以时间单位查询卡开头并以三图卡收尾`() {
        assertEquals("periodcard", FINANCE_STATS_PANE_SECTIONS.first())
        assertEquals("charts", FINANCE_STATS_PANE_SECTIONS.last())
    }

    @Test
    fun `记账交易行高取下限四十八像素`() {
        // 原型 .list-row{min-height:48px}；--row-min-h 是 52px 的通用行高，全站共用、不在这里改
        assertEquals(48f, TX_ROW_MIN_H.value, 0.01f)
        assertTrue(TX_ROW_MIN_H < 52.dp)
    }

    @Test
    fun `自动徽标高度与内边距按原型收窄`() {
        assertEquals(18f, TX_AUTO_BADGE_H.value, 0.01f)
        assertEquals(6f, TX_AUTO_BADGE_PAD_X.value, 0.01f)
        // 公共 Badge 的最小高是 20dp：本页这版必须比它矮，否则等于没改
        assertTrue(TX_AUTO_BADGE_H < 20.dp)
    }

    @Test
    fun `KPI 数值走等宽族十七像素半粗`() {
        // 原型 .s04-kv：font-family var(--font-mono) + --text-base 17px + 600
        assertEquals(FontFamily.Monospace, monitorMonoValue.fontFamily)
        assertEquals(17f, monitorMonoValue.fontSize.value, 0.01f)
        assertEquals(FontWeight.SemiBold, monitorMonoValue.fontWeight)
    }

    @Test
    fun `预算环心大字走等宽族二十一像素粗体`() {
        // 原型 .s04-ring-txt b：font-mono + --text-lg 21px + 700
        assertEquals(FontFamily.Monospace, monitorMonoKpi.fontFamily)
        assertEquals(21f, monitorMonoKpi.fontSize.value, 0.01f)
        assertEquals(FontWeight.Bold, monitorMonoKpi.fontWeight)
    }

    @Test
    fun `分组标题第三组起用星期而不是前天`() {
        val today = LocalDate.now()
        assertTrue("今天", groupLabel(today.toString()).endsWith(" · 今天"))
        assertTrue("昨天", groupLabel(today.minusDays(1).toString()).endsWith(" · 昨天"))
        // 原型 s04:119/155/164 的第三组是星期（今天 / 昨天 / 周一），不再写「前天」
        val expectedWeekday = listOf("一", "二", "三", "四", "五", "六", "日")[today.minusDays(2).dayOfWeek.value - 1]
        assertEquals(" · 周" + expectedWeekday, groupLabel(today.minusDays(2).toString()).substringAfter(" 日"))
        assertFalse(groupLabel(today.minusDays(2).toString()).contains("前天"))
    }

    @Test
    fun `预算环描边按原型收窄到九dp`() {
        // 原型 s04 预算环宽 ≈8.7px（104 直径），RingChart 默认 14dp 会把孔径压没
        assertEquals(9f, FINANCE_RING_STROKE.value, 0.01f)
        assertTrue(FINANCE_RING_STROKE < 14.dp)
    }

    @Test
    fun `列表 meta 用十四像素档而不是十二像素档`() {
        // 原型 .meta = --text-sm 14px（20-base.html:177）；12sp 的 labelSmall 比它小一档。
        // 「最近 3 天 · N 笔」这行改走 bodySmall（14sp），本类只钉住档位，屏内调用点见 FinanceMainListHead。
        assertEquals(14f, monitorTypography.bodySmall.fontSize.value, 0.01f)
        assertEquals(12f, monitorTypography.labelSmall.fontSize.value, 0.01f)
    }
}
