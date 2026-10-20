package com.bsfeng.monitor.finance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [LegacyRemarkCleaner] 单元测试（纯 JVM）。
 *
 * 回归重点：只清「至少两段、与旧算法完全一致」的自动拼接说明；
 * 用户手写、单段商户名、预采集备注拼进去的旧说明都不能误清。
 */
class LegacyRemarkCleanerTest {

    @Test
    fun `旧拼接说明能被识别`() {
        assertTrue(
            LegacyRemarkCleaner.isLegacyAggregate(
                remark = "淘宝闪购·淘宝闪购订单·招商银行信用卡(8888)",
                merchant = "淘宝闪购",
                productDesc = "淘宝闪购订单",
                payMethod = "招商银行信用卡(8888)",
                channelLabel = "支付宝",
            )
        )
    }

    @Test
    fun `用户手写的说明不会被误清`() {
        assertFalse(
            LegacyRemarkCleaner.isLegacyAggregate(
                remark = "同事聚餐 AA",
                merchant = "淘宝闪购",
                productDesc = "淘宝闪购订单",
                payMethod = "招商银行信用卡(8888)",
                channelLabel = "支付宝",
            )
        )
    }

    @Test
    fun `单段商户名无法与手写区分所以保留`() {
        assertFalse(
            LegacyRemarkCleaner.isLegacyAggregate(
                remark = "淘宝闪购",
                merchant = "淘宝闪购",
                productDesc = "",
                payMethod = "",
                channelLabel = "支付宝",
            )
        )
    }

    @Test
    fun `旧记录里拼了用户预采集备注时保留`() {
        // 旧算法会把 userRemark 拼进 remark；重算只含商户/商品说明/付款方式，不等即保留
        assertFalse(
            LegacyRemarkCleaner.isLegacyAggregate(
                remark = "淘宝闪购·淘宝闪购订单·午饭·招商银行信用卡(8888)",
                merchant = "淘宝闪购",
                productDesc = "淘宝闪购订单",
                payMethod = "招商银行信用卡(8888)",
                channelLabel = "支付宝",
            )
        )
    }

    @Test
    fun `空说明不处理`() {
        assertFalse(
            LegacyRemarkCleaner.isLegacyAggregate(
                remark = "",
                merchant = "淘宝闪购",
                productDesc = "订单",
                payMethod = "零钱",
                channelLabel = "微信",
            )
        )
    }

    @Test
    fun `拼接结果按旧算法截断到 80 字`() {
        val longMerchant = "商".repeat(60)
        val longProduct = "品".repeat(60)
        val aggregated = LegacyRemarkCleaner.aggregated(
            merchant = longMerchant,
            productDesc = longProduct,
            payMethod = "零钱",
            channelLabel = "微信",
        )
        assertEquals(80, aggregated.length)
        assertTrue(aggregated.startsWith(longMerchant))
    }

    @Test
    fun `商户为空时商品说明接管，付款方式追加`() {
        assertEquals(
            "外卖订单·零钱",
            LegacyRemarkCleaner.aggregated(
                merchant = "",
                productDesc = "外卖订单",
                payMethod = "零钱",
                channelLabel = "微信",
            )
        )
    }

    @Test
    fun `渠道名从 source 后缀解析`() {
        assertEquals("支付宝", LegacyRemarkCleaner.channelLabelOf("auto_alipay"))
        assertEquals("微信", LegacyRemarkCleaner.channelLabelOf("auto_wechat"))
        assertEquals("", LegacyRemarkCleaner.channelLabelOf("manual"))
    }
}
