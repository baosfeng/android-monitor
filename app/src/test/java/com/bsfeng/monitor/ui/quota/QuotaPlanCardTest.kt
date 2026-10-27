package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.domain.quota.OllamaPlanMode
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/**
 * s18 计费模式条 → 额度卡排版（第 4 批走查 P1-2）。
 *
 * 走查发现：点「按金额（USD）」只落盘、面板不动，toast 却报「已切换为按金额展示」——
 * 面板与提示各说各的。这里把「先按选择、选择撑不起时按实际结构」这条判据钉死，
 * 同时钉住 toast 与面板必须一致。
 */
class QuotaPlanCardTest {

    private fun percentItem(id: String = "session") = QuotaItem(
        id = id,
        title = "会话",
        kind = QuotaKind.PERCENT,
        remaining = BigDecimal("75"),
        unit = QuotaUnit.PERCENT,
        primary = true,
    )

    private fun moneyItem(id: String = "included") = QuotaItem(
        id = id,
        title = "内含额度",
        kind = QuotaKind.MONEY,
        remaining = BigDecimal("72.50"),
        total = BigDecimal("100"),
        unit = QuotaUnit.USD,
        primary = true,
    )

    @Test
    fun `选按金额且返回金额结构时出金额卡`() {
        val layout = quotaCardLayout("ollama", OllamaPlanMode.USD, listOf(moneyItem()))
        assertEquals(QuotaCardLayout.MONEY, layout)
    }

    @Test
    fun `选按百分比且返回百分比结构时出百分比卡`() {
        val layout = quotaCardLayout("ollama", OllamaPlanMode.PERCENT, listOf(percentItem()))
        assertEquals(QuotaCardLayout.PERCENT, layout)
    }

    @Test
    fun `选按金额但只回百分比结构时按实际结构展示`() {
        // 不能给一张空金额卡：结构不符时按百分比面板展示，差异由卡头问号说明
        val layout = quotaCardLayout("ollama", OllamaPlanMode.USD, listOf(percentItem()))
        assertEquals(QuotaCardLayout.PERCENT, layout)
    }

    @Test
    fun `选按百分比但只回金额结构时按实际结构展示`() {
        val layout = quotaCardLayout("ollama", OllamaPlanMode.PERCENT, listOf(moneyItem()))
        assertEquals(QuotaCardLayout.MONEY, layout)
    }

    @Test
    fun `两种结构都在时服从所选计费模式`() {
        val both = listOf(percentItem(), moneyItem())
        assertEquals(QuotaCardLayout.PERCENT, quotaCardLayout("ollama", OllamaPlanMode.PERCENT, both))
        assertEquals(QuotaCardLayout.MONEY, quotaCardLayout("ollama", OllamaPlanMode.USD, both))
    }

    @Test
    fun `计费模式不影响别家的卡片分流`() {
        // 计划选择只属于 Ollama：DeepSeek 恒余额、opencode-go 恒百分比
        assertEquals(
            QuotaCardLayout.MONEY,
            quotaCardLayout("deepseek", OllamaPlanMode.PERCENT, listOf(moneyItem())),
        )
        assertEquals(
            QuotaCardLayout.PERCENT,
            quotaCardLayout("opencode-go", OllamaPlanMode.USD, listOf(percentItem())),
        )
    }

    @Test
    fun `没有额度项时不选卡`() {
        assertNull(quotaCardLayout("ollama", OllamaPlanMode.PERCENT, emptyList()))
    }

    @Test
    fun `toast 报的面板就是选中口径时如实说已切换`() {
        assertEquals(
            "Ollama · 已切换为按百分比展示",
            planSwitchToast(OllamaPlanMode.PERCENT, QuotaCardLayout.PERCENT),
        )
        assertEquals(
            "Ollama · 已切换为按金额展示",
            planSwitchToast(OllamaPlanMode.USD, QuotaCardLayout.MONEY),
        )
    }

    @Test
    fun `结构撑不起所选口径时 toast 说按实际结构展示`() {
        val percentSelected = planSwitchToast(OllamaPlanMode.PERCENT, QuotaCardLayout.MONEY)
        assertTrue(percentSelected, percentSelected.contains("实际结构"))
        val usdSelected = planSwitchToast(OllamaPlanMode.USD, QuotaCardLayout.PERCENT)
        assertTrue(usdSelected, usdSelected.contains("实际结构"))
    }

    @Test
    fun `还没有数据时 toast 只说选择已记住`() {
        val text = planSwitchToast(OllamaPlanMode.USD, layout = null)
        assertTrue(text, text.contains("已记住"))
    }
}
