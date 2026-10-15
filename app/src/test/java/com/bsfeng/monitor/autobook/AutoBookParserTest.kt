package com.bsfeng.monitor.autobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 自动记账解析器测试（纯 JVM，不依赖 Android）。
 *
 * 输入样例分两类，都来自真实环境：
 * 1. **页面行**：真机 uiautomator dump 支付宝账单详情页后，按 [PageLines] 规则合成的视觉行；
 * 2. **通知文本**：开源项目里标注为真机原文的通知串（微信/支付宝/银行）。
 */
class AutoBookParserTest {

    private val alipay = AutoBookRules.alipay
    private val wechat = AutoBookRules.wechat

    /** 真机 dump 还原：支付宝「账单详情」页（阿里云 3 元） */
    private fun alipayDetailLines(): List<String> = listOf(
        "阿里云",
        "支出3元",
        "交易成功",
        "支付时间 2026-10-15 23:14:43",
        "付款方式 余额宝",
        "商品说明 充值:阿里云服务购买,业务交易号:CFP000000000000000000",
        "支付奖励 立即领取2积分",
        "收款方全称 阿里云计算有限公司",
        "账单分类 日用百货"
    )

    // ==================== 正常路径 ====================

    @Test
    fun `支付宝账单详情页：金额商户账户分类时间全部提取正确`() {
        val draft = AutoBookParser.parse(alipay, alipayDetailLines(), 1_760_000_000_000L)
        requireNotNull(draft) { "真机页面应当能识别出一笔账" }

        assertEquals(AutoBookRules.CHANNEL_ALIPAY, draft.channel)
        assertEquals("支付宝", draft.channelLabel)
        assertEquals(AutoBookDraft.TYPE_EXPENSE, draft.type)
        assertEquals("支出", draft.typeLabel)
        assertEquals(3.0, draft.amount, 0.0001)
        assertEquals("阿里云计算有限公司", draft.merchant)
        assertEquals("日用", draft.categoryName)
        assertEquals("支付宝", draft.accountName)
        assertEquals("余额宝", draft.payMethod)
        assertEquals("2026-10-15 23:14:43", draft.timeText)
        assertTrue("应命中「交易成功/账单详情」类页面特征", draft.markers.isNotEmpty())
    }

    @Test
    fun `页内「立即领取2积分」不影响金额识别`() {
        val draft = AutoBookParser.parse(alipay, alipayDetailLines(), 1L)
        assertEquals("页内积分文案不得被当成金额", 3.0, draft!!.amount, 0.0001)
    }

    @Test
    fun `支付宝付款成功通知：带货币符号的金额`() {
        val lines = listOf("付款成功", "付款成功￥3.00 (AB)湖北武汉黄陂汉口北服装城美宜佳")
        val draft = AutoBookParser.parse(alipay, lines, 1L)
        assertEquals(3.0, draft!!.amount, 0.0001)
        assertEquals(AutoBookDraft.TYPE_EXPENSE, draft.type)
    }

    @Test
    fun `支付宝收入通知：「你有一笔…元的收入」判为收入`() {
        val lines = listOf("交易提醒", "支付宝 | 你有一笔200.00元的收入，来自张某某的转账")
        val draft = AutoBookParser.parse(alipay, lines, 1L)
        assertEquals(200.0, draft!!.amount, 0.0001)
        assertEquals(AutoBookDraft.TYPE_INCOME, draft.type)
        assertEquals("收入", draft.typeLabel)
    }

    @Test
    fun `微信已支付通知：聚合前缀「48条」不得被当成金额`() {
        // 真机踩坑：48 曾被当成金额，4.85 元的单记成 48 元
        val lines = listOf("微信支付", "[48条]微信支付：已支付¥4.85")
        val draft = AutoBookParser.parse(wechat, lines, 1L)
        assertEquals(4.85, draft!!.amount, 0.0001)
        assertEquals(AutoBookDraft.TYPE_EXPENSE, draft.type)
    }

    @Test
    fun `微信转账凭证：从「商户全称」标签取商户`() {
        val lines = listOf(
            "微信支付",
            "支付成功",
            "-¥25.00",
            "商户全称 上海星巴克咖啡经营有限公司",
            "支付方式 零钱",
            "支付时间 2026-10-16 12:06:11"
        )
        val draft = AutoBookParser.parse(wechat, lines, 1L)
        requireNotNull(draft)
        assertEquals(25.0, draft.amount, 0.0001)
        assertEquals("上海星巴克咖啡经营有限公司", draft.merchant)
        assertEquals("微信钱包", draft.accountName)
        assertEquals("零钱", draft.payMethod)
        assertEquals("餐饮", draft.categoryName)
    }

    @Test
    fun `微信「向X付款」句式判为支出`() {
        val lines = listOf("微信支付", "向 麦当劳 付款 12.30 元")
        val draft = AutoBookParser.parse(wechat, lines, 1L)
        assertEquals(12.30, draft!!.amount, 0.0001)
        assertEquals(AutoBookDraft.TYPE_EXPENSE, draft.type)
    }

    // ==================== 边界与异常路径 ====================

    @Test
    fun `未来时态通知不记账`() {
        val lines = listOf("微信支付", "您签约的自动续费将于明日扣款19.9元，请保证余额充足")
        assertNull(AutoBookParser.parse(wechat, lines, 1L))
    }

    @Test
    fun `无交易特征的页面不弹窗`() {
        val lines = listOf("阿里云", "账单列表", "10月账单 共 12 笔", "本月支出 1234.00 元")
        assertNull("列表页没有「交易成功」类特征，不应识别", AutoBookParser.parse(alipay, lines, 1L))
    }

    @Test
    fun `提现属于账户间转移不记账`() {
        val lines = listOf("交易成功", "提现到银行卡 500.00 元")
        assertNull(AutoBookParser.parse(alipay, lines, 1L))
    }

    @Test
    fun `余额行不会被当成交易金额`() {
        val lines = listOf("交易成功", "账户余额 1234.56 元")
        assertNull("余额不是交易金额", AutoBookParser.parse(alipay, lines, 1L))
    }

    @Test
    fun `空文本与纯噪声不崩溃`() {
        assertNull(AutoBookParser.parse(alipay, emptyList(), 1L))
        assertNull(AutoBookParser.parse(alipay, listOf("", "  "), 1L))
    }

    @Test
    fun `品牌名不干扰方向判定`() {
        // 「支付宝」含「支付」二字，必须在判定方向前清洗
        val lines = listOf("收款成功", "支付宝 | 成功收款 1.00 元。享免费提现等更多专属服务")
        val draft = AutoBookParser.parse(alipay, lines, 1L)
        assertEquals("收款应判为收入", AutoBookDraft.TYPE_INCOME, draft!!.type)
    }

    @Test
    fun `商户取不到时留空而不编造`() {
        val lines = listOf("交易成功", "你有一笔 88.00 元的支出，点此查看详情")
        val draft = AutoBookParser.parse(alipay, lines, 1L)
        requireNotNull(draft)
        assertEquals(88.0, draft.amount, 0.0001)
        assertEquals("取不到商户名时留空", "", draft.merchant)
    }

    // ==================== 去重键 ====================

    @Test
    fun `有订单号时用订单号做幂等键：不同订单不同键`() {
        val a = AutoBookParser.buildSourceId("alipay", 0, 3.0, "阿里云", "CFP000000000000000000", 1000L)
        val b = AutoBookParser.buildSourceId("alipay", 0, 3.0, "阿里云", "CFP202610152314169751", 1000L)
        assertNotEquals(a, b)
    }

    @Test
    fun `无订单号时同一时间窗内重复识别得到同一个键`() {
        val a = AutoBookParser.buildSourceId("wechat", 0, 12.3, "麦当劳", "", 100_000L)
        val b = AutoBookParser.buildSourceId("wechat", 0, 12.3, "麦当劳", "", 100_500L)
        assertEquals(a, b)
    }

    @Test
    fun `无订单号时跨时间窗视为两笔`() {
        val a = AutoBookParser.buildSourceId("wechat", 0, 12.3, "麦当劳", "", 100_000L)
        val b = AutoBookParser.buildSourceId("wechat", 0, 12.3, "麦当劳", "", 100_000L + AutoBookParser.DEDUP_WINDOW_MS)
        assertNotEquals(a, b)
    }

    // ==================== 视觉行合成（PageLines） ====================

    @Test
    fun `同一行的标签与值按 x 排序拼接成一行`() {
        val nodes = listOf(
            NodeText("收款方全称", 75, 1349),
            NodeText("阿里云计算有限公司", 375, 1346),
            NodeText("账单分类", 75, 1790),
            NodeText("日用百货", 903, 1784)
        )
        val lines = PageLines.toLines(nodes)
        assertEquals(listOf("收款方全称 阿里云计算有限公司", "账单分类 日用百货"), lines)
    }

    @Test
    fun `不同 y 的节点分成不同行并按从上到下排序`() {
        val nodes = listOf(
            NodeText("交易成功", 75, 713),
            NodeText("阿里云", 531, 488),
            NodeText("支出3元", 75, 563)
        )
        assertEquals(listOf("阿里云", "支出3元", "交易成功"), PageLines.toLines(nodes))
    }

    @Test
    fun `空节点列表返回空行`() {
        assertTrue(PageLines.toLines(emptyList()).isEmpty())
    }

    // ==================== 分类映射 ====================

    @Test
    fun `平台分类优先于商户关键词`() {
        assertEquals("日用", AutoBookParser.mapCategory("日用百货", "星巴克", AutoBookDraft.TYPE_EXPENSE))
        assertEquals("餐饮", AutoBookParser.mapCategory("餐饮美食", "阿里云", AutoBookDraft.TYPE_EXPENSE))
        assertEquals("其他", AutoBookParser.mapCategory(null, "某某科技有限公司", AutoBookDraft.TYPE_EXPENSE))
    }

    @Test
    fun `商户关键词兜底分类`() {
        assertEquals("餐饮", AutoBookParser.mapCategory(null, "上海星巴克咖啡经营有限公司", AutoBookDraft.TYPE_EXPENSE))
        assertEquals("日用", AutoBookParser.mapCategory(null, "阿里云计算有限公司", AutoBookDraft.TYPE_EXPENSE))
    }
}
