package com.bsfeng.monitor.finance.importer

import com.bsfeng.monitor.finance.model.CategoryCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BillCategoryResolver] 的优先级：平台分类 > 商品名称 > 交易对方 > 兜底。
 *
 * 为什么商品排在交易对方前：交易对方常是平台泛称（「淘宝闪购」「财付通」），
 * 商品名才带品类（「某某湘菜馆外卖订单」）。这条顺序错了，整批导入会一起偏。
 */
class BillCategoryResolverTest {

    private val catalogNames = CategoryCatalog.allNames().toSet()

    private fun record(
        platformCategory: String = "",
        product: String = "",
        counterparty: String = "",
        direction: BillDirection = BillDirection.EXPENSE,
    ) = BillRecord(
        channel = BillChannel.ALIPAY,
        direction = direction,
        platformCategory = platformCategory,
        product = product,
        counterparty = counterparty,
    )

    @Test
    fun `平台分类最优先`() {
        // 平台分类命中「外卖」，商品名会命中「咖啡奶茶」—— 结果必须是平台那一档
        val r = record(platformCategory = "美团外卖", product = "星巴克咖啡", counterparty = "瑞幸")
        assertEquals("外卖", BillCategoryResolver.resolve(r))
    }

    @Test
    fun `平台分类认不出时退到商品`() {
        val r = record(platformCategory = "其他平台分类", product = "星巴克咖啡", counterparty = "瑞幸")
        assertEquals("咖啡奶茶", BillCategoryResolver.resolve(r))
    }

    @Test
    fun `商品优先于交易对方`() {
        // 交易对方是平台泛称（淘宝），商品名才带真实品类
        val r = record(product = "某某湘菜馆外卖订单", counterparty = "淘宝闪购")
        assertEquals("外卖", BillCategoryResolver.resolve(r))
    }

    @Test
    fun `商品认不出时退到交易对方`() {
        val r = record(product = "订单商品", counterparty = "美团单车")
        assertEquals("共享单车", BillCategoryResolver.resolve(r))
    }

    @Test
    fun `三档都认不出时支出兜底其他`() {
        assertEquals("其他", BillCategoryResolver.resolve(record(counterparty = "某某科技有限公司")))
    }

    @Test
    fun `收入侧兜底其他收入`() {
        val r = record(counterparty = "某某科技有限公司", direction = BillDirection.INCOME)
        assertEquals("其他收入", BillCategoryResolver.resolve(r))
    }

    @Test
    fun `收入侧按收入表推断`() {
        assertEquals("工资", BillCategoryResolver.resolve(
            record(product = "代发工资", direction = BillDirection.INCOME)
        ))
        assertEquals("退款", BillCategoryResolver.resolve(
            record(platformCategory = "退款", direction = BillDirection.INCOME)
        ))
        assertEquals("公积金", BillCategoryResolver.resolve(
            record(product = "住房公积金", direction = BillDirection.INCOME)
        ))
    }

    @Test
    fun `推断结果一定落在分类表内`() {
        val samples = listOf(
            record(platformCategory = "餐饮美食"),
            record(product = "星巴克咖啡"),
            record(counterparty = "某某科技有限公司"),
            record(product = "乱码文本 xyz"),
            record(direction = BillDirection.INCOME, product = "代发工资"),
            record(direction = BillDirection.INCOME, counterparty = "某某"),
        )
        val outside = samples.map { BillCategoryResolver.resolve(it) }.filter { it !in catalogNames }
        assertTrue("推断出分类表外的名字：" + outside, outside.isEmpty())
    }

    @Test
    fun `isFallback 只认两个兜底名`() {
        assertTrue(BillCategoryResolver.isFallback("其他"))
        assertTrue(BillCategoryResolver.isFallback("其他收入"))
        assertTrue(!BillCategoryResolver.isFallback("外卖"))
        assertTrue(!BillCategoryResolver.isFallback("其他购物"))
    }
}