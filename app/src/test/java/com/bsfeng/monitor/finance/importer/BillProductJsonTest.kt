package com.bsfeng.monitor.finance.importer

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 商品说明里内嵌平台分类的提取（真实支付宝导出形态）。
 *
 * 样例全部自造：只保留字段结构，商户 / 金额 / 分类名都是示例。
 */
class BillProductJsonTest {

    @Test
    fun `标准 JSON 形态能取到分类`() {
        val product = "{\"商品说明\":\"收钱码收款\",\"支付宝交易分类\":\"美容美发\"}"
        assertEquals("美容美发", BillProductJson.platformCategory(product))
    }

    @Test
    fun `冒号后带空格也能取到`() {
        assertEquals("餐饮美食", BillProductJson.platformCategory("{\"支付宝交易分类\": \"餐饮美食\"}"))
    }

    @Test
    fun `等号写法兜底`() {
        assertEquals("日用百货", BillProductJson.platformCategory("支付宝交易分类=日用百货"))
    }

    @Test
    fun `普通商品说明不误判`() {
        assertEquals("", BillProductJson.platformCategory("某某湘菜馆外卖订单"))
        assertEquals("", BillProductJson.platformCategory(""))
    }

    @Test
    fun `正文里提到分类字样但无值时不误取`() {
        assertEquals("", BillProductJson.platformCategory("{\"商品说明\":\"支付宝交易分类说明文档\"}"))
    }
}
