package com.bsfeng.monitor.autobook

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    /**
     * 真机 dump 还原：支付宝**首页**。
     *
     * 「最近消息」卡片里有一条**历史消息摘要** `付款成功￥3.00`，与支付结果页的文案一模一样。
     * 改造前它会被认成一笔 3 元支出、浮窗直接盖在首页上（用户反馈「识别有误」），
     * 商户名还会被渲染中间态污染成「松开刷新」。
     */
    private fun alipayHomeLines(): List<String> = listOf(
        "上海",
        "阴 22℃",
        "搜索服务或小程序",
        "扫一扫",
        "收付款",
        "出行",
        "卡包",
        "记账本",
        "最近消息",
        "还有3条未读服务消息",
        "支付宝会员",
        "你有8积分待领取，解锁HelloKitty蝴蝶结皮肤",
        "2小时前",
        "阿里云",
        "付款成功￥3.00 ",
        "9小时前",
        "消息",
        "我的"
    )

    /**
     * 真机 dump 还原：支付宝「支付消息」**列表页**。
     *
     * 危险点在于：每张卡片都自带「付款方式」这种详情标签，而列表第一条的金额商户**恰好也能提取出来**，
     * 于是只看「有没有详情标签」的门禁会放行它、弹出一个「看起来是对的」的浮窗。
     */
    private fun alipayMessageListLines(): List<String> = listOf(
        "10月统计支出",
        "本月支出 ¥1782.88，较上月降低",
        "1 大额消费",
        "18 自动扣款",
        "1 花呗付款",
        "22 信用卡付款",
        "阿里云",
        "昨天 23:14",
        "付款成功",
        "¥3.00",
        "查看详情",
        "付款方式 余额宝",
        "支付奖励 +2积分 | 金典有机奶250mlx10 新鲜到家",
        "某市供水公司",
        "昨天 22:59",
        "付款成功",
        "¥81.00",
        "查看详情",
        "付款方式 招商银行信用卡(8888)",
        "浦发银行",
        "昨天 20:07",
        "还款到账成功",
        "¥849.53",
        "查看详情",
        "付款方式 招商银行储蓄卡(4995)"
    )

    /**
     * 真机 dump 还原：微信支付**服务号会话页**（不该弹窗）。
     *
     * 危险点：支付消息卡片的 content-desc 是整张卡片的描述 ——
     * `使用零钱支付￥43.00交易详情`，
     * 「交易详情」只是卡片上的按钮文字，却会被当成「详情页字段标签」，
     * 于是会话页被误判成详情页、当场弹窗（商户名还取成了消息时间 `10月10日 14:01`）。
     */
    private fun wechatChatLines(): List<String> = listOf(
        "10月10日 14:01",
        "滴滴出行",
        "使用零钱支付￥43.00交易详情 使用零钱支付",
        "￥ 43.00",
        "周一 10:29",
        "收款到账通知",
        "收款金额￥26.00 收款金额",
        "￥ 26.00",
        "收款场景 企业微信红包",
        "备注 成功领取来自史高霜的企业微信红包，已存入零钱。点击可查看详情",
        "我的账单 支付服务 优惠"
    )

    /**
     * 真机 dump 还原：微信**交易详情页**（该弹窗）。
     *
     * 与会话页的分水岭是**成对的字段标签**：当前状态 / 支付时间 / 商户全称 / 支付方式 / 交易单号。
     * 注意微信的时间是中文年月日 `2026年10月10日 14:01:42`，与支付宝的 `2026-10-15 23:14:43` 不同。
     */
    private fun wechatDetailLines(): List<String> = listOf(
        "滴滴出行",
        "服务 会员 交易详情",
        "-43.00",
        "滴滴出行",
        "当前状态 支付成功",
        "支付时间 2026年10月10日 14:01:42",
        "商品 费用",
        "商户全称 某出行服务公司",
        "收单机构 北京滴滴支付技术有限公司",
        "支付方式 零钱",
        "交易单号 4200003144202610109050475236",
        "商户单号 2026101014013615565510575279",
        "交易服务",
        "对订单有疑惑 发起群收款"
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
        val draft = AutoBookParser.parseNotification(alipay, lines, 1L)
        assertEquals(3.0, draft!!.amount, 0.0001)
        assertEquals(AutoBookDraft.TYPE_EXPENSE, draft.type)
    }

    @Test
    fun `支付宝收入通知：「你有一笔…元的收入」判为收入`() {
        val lines = listOf("交易提醒", "支付宝 | 你有一笔200.00元的收入，来自张某某的转账")
        val draft = AutoBookParser.parseNotification(alipay, lines, 1L)
        assertEquals(200.0, draft!!.amount, 0.0001)
        assertEquals(AutoBookDraft.TYPE_INCOME, draft.type)
        assertEquals("收入", draft.typeLabel)
    }

    @Test
    fun `微信已支付通知：聚合前缀「48条」不得被当成金额`() {
        // 真机踩坑：48 曾被当成金额，4.85 元的单记成 48 元
        val lines = listOf("微信支付", "[48条]微信支付：已支付¥4.85")
        val draft = AutoBookParser.parseNotification(wechat, lines, 1L)
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
        val draft = AutoBookParser.parseNotification(wechat, lines, 1L)
        assertEquals(12.30, draft!!.amount, 0.0001)
        assertEquals(AutoBookDraft.TYPE_EXPENSE, draft.type)
    }

    // ==================== 边界与异常路径 ====================

    @Test
    fun `未来时态通知不记账`() {
        val lines = listOf("微信支付", "您签约的自动续费将于明日扣款19.9元，请保证余额充足")
        assertNull(AutoBookParser.parseNotification(wechat, lines, 1L))
    }

    @Test
    fun `无交易特征的页面不弹窗`() {
        val lines = listOf("阿里云", "账单列表", "10月账单 共 12 笔", "本月支出 1234.00 元")
        assertNull("列表页没有「交易成功」类特征，不应识别", AutoBookParser.parse(alipay, lines, 1L))
    }

    @Test
    fun `提现属于账户间转移不记账`() {
        val lines = listOf("交易成功", "提现到银行卡 500.00 元")
        assertNull(AutoBookParser.parseNotification(alipay, lines, 1L))
    }

    @Test
    fun `余额行不会被当成交易金额`() {
        val lines = listOf("交易成功", "账户余额 1234.56 元")
        assertNull("余额不是交易金额", AutoBookParser.parseNotification(alipay, lines, 1L))
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
        val draft = AutoBookParser.parseNotification(alipay, lines, 1L)
        assertEquals("收款应判为收入", AutoBookDraft.TYPE_INCOME, draft!!.type)
    }

    @Test
    fun `商户取不到时留空而不编造`() {
        val lines = listOf("交易成功", "你有一笔 88.00 元的支出，点此查看详情")
        val draft = AutoBookParser.parseNotification(alipay, lines, 1L)
        requireNotNull(draft)
        assertEquals(88.0, draft.amount, 0.0001)
        assertEquals("取不到商户名时留空", "", draft.merchant)
    }

    // ==================== 账单页门禁（真机回归） ====================

    @Test
    fun `支付宝首页消息摘要不得被识别成账单`() {
        assertNull(
            "首页「最近消息」里的历史摘要不是一笔新交易，不该弹浮窗",
            AutoBookParser.parse(alipay, alipayHomeLines(), 1L)
        )
    }

    @Test
    fun `支付消息列表页不得被识别成账单`() {
        assertNull(
            "一屏有多笔交易的列表页不是单笔账单页",
            AutoBookParser.parse(alipay, alipayMessageListLines(), 1L)
        )
    }

    @Test
    fun `微信支付会话页不得被识别成账单`() {
        assertNull(
            "会话页消息卡片上的「交易详情」是按钮文字，不是详情页字段",
            AutoBookParser.parse(wechat, wechatChatLines(), 1L)
        )
    }

    @Test
    fun `门禁能区分微信会话页与交易详情页`() {
        // 两者都含「交易详情」「零钱」「支付成功」这些字眼，靠「成对字段标签」与「一屏多笔」区分
        assertFalse(AutoBookParser.isBillPage(wechatChatLines()))
        assertTrue(AutoBookParser.isBillPage(wechatDetailLines()))
    }

    @Test
    fun `微信交易详情页：中文年月日时间与全部字段`() {
        val draft = AutoBookParser.parse(wechat, wechatDetailLines(), 1L)
        requireNotNull(draft) { "微信交易详情页应当能识别" }
        assertEquals(43.0, draft.amount, 0.0001)
        assertEquals(AutoBookDraft.TYPE_EXPENSE, draft.type)
        assertEquals("某出行服务公司", draft.merchant)
        assertEquals("零钱", draft.payMethod)
        assertEquals("2026年10月10日 14:01:42", draft.timeText)
        assertEquals("微信钱包", draft.accountName)
    }

    @Test
    fun `微信详情页的交易单号进落库键`() {
        val draft = AutoBookParser.parse(wechat, wechatDetailLines(), 1L)
        requireNotNull(draft)
        assertTrue(
            "有交易单号时，落库键与精确指纹都应当以订单号为准",
            draft.dedupKeys.any { it.value == "order:wechat:4200003144202610109050475236" }
        )
    }

    @Test
    fun `门禁能区分支付消息列表页与账单详情页`() {
        // 两者都带「付款方式」标签，靠「一屏是否出现多笔」区分
        assertFalse(AutoBookParser.isBillPage(alipayMessageListLines()))
        assertTrue(AutoBookParser.isBillPage(alipayDetailLines()))
    }

    @Test
    fun `门禁能区分首页与账单详情页`() {
        assertFalse("首页不是账单页", AutoBookParser.isBillPage(alipayHomeLines()))
        assertTrue("账单详情页是账单页", AutoBookParser.isBillPage(alipayDetailLines()))
    }

    @Test
    fun `只有状态锚点没有收尾按钮的页面不放行`() {
        // 「支付成功」单独成行也出现在首页消息摘要里，必须再叠加「完成 / 查看账单」才认
        val lines = listOf("支付成功", "付款成功￥3.00")
        assertNull(AutoBookParser.parse(alipay, lines, 1L))
    }

    @Test
    fun `带收尾按钮的独占结果页放行`() {
        // 付款后立即跳出的那一页：状态锚点 + 金额 + 商户 + 「完成」按钮
        val lines = listOf("支付成功", "¥25.00", "上海星巴克咖啡经营有限公司", "完成")
        val draft = AutoBookParser.parse(alipay, lines, 1L)
        requireNotNull(draft) { "带收尾按钮的结果页应当能识别" }
        assertEquals(25.0, draft.amount, 0.0001)
        assertEquals(AutoBookDraft.TYPE_EXPENSE, draft.type)
        assertEquals("餐饮", draft.categoryName)
    }

    @Test
    fun `首页拼接出来的伪商户名被丢弃`() {
        // 真机：首页渲染中间态把一串 UI 文案拼成了商户名「上海 搜索框 搜索服务或小程序 搜索 更多操作」
        val lines = listOf("交易成功", "¥3.00", "上海 搜索框 搜索服务或小程序 搜索 更多操作")
        val draft = AutoBookParser.parseNotification(alipay, lines, 1L)
        requireNotNull(draft)
        assertEquals("宁可留空也不写垃圾商户名", "", draft.merchant)
    }

    // ==================== 标签取值 ====================

    @Test
    fun `「付款方式」不得被当成商户标签的前缀`() {
        // 真机踩坑：商户标签里的「付款方」是「付款方式」的前缀，
        // 会把商户名取成「式 招商银行信用卡(8888)」
        val lines = listOf(
            "某市供水公司",
            "支出81元",
            "交易成功",
            "创建时间 2026-10-15 22:58:24",
            "付款方式 招商银行信用卡(8888)",
            "缴费说明 水费-*先生"
        )
        val draft = AutoBookParser.parse(alipay, lines, 1L)
        requireNotNull(draft)
        assertEquals(81.0, draft.amount, 0.0001)
        assertEquals("某市供水公司", draft.merchant)
        assertEquals("2026-10-15 22:58:24", draft.timeText)
    }

    @Test
    fun `短标签不得截胡更长标签`() {
        val lines = listOf("收款方全称 阿里云计算有限公司")
        assertEquals(
            "阿里云计算有限公司",
            AutoBookParser.labelValue(lines, listOf("收款方", "收款方全称"))
        )
    }

    @Test
    fun `标签后紧跟冒号仍能取值`() {
        val lines = listOf("付款方式：余额宝")
        assertEquals("余额宝", AutoBookParser.labelValue(lines, listOf("付款方式")))
    }

    // ==================== 订单号提取 ====================

    @Test
    fun `订单号藏在其他字段的值里时也能提取`() {
        // 真机原文：支付宝不单给订单号一行，而是塞在「商品说明」里
        assertEquals("CFP000000000000000000", AutoBookParser.extractOrderId(alipayDetailLines()))
    }

    // ==================== 去重键 ====================

    @Test
    fun `有订单号时用订单号做落库幂等键：不同订单不同键`() {
        val a = AutoBookParser.buildSourceId("alipay", 0, 3.0, "阿里云", "CFP000000000000000000", "", 1000L)
        val b = AutoBookParser.buildSourceId("alipay", 0, 3.0, "阿里云", "CFP202610152314169751", "", 1000L)
        assertNotEquals(a, b)
    }

    @Test
    fun `有交易时间时落库键跨时间窗不漂移`() {
        // 交易时间是账单自带属性；改造前只用「5 分钟桶」，跨桶同一笔会变成两个键、重复记账
        val a = AutoBookParser.buildSourceId("alipay", 0, 3.0, "阿里云", "", "2026-10-15 23:14:43", 1000L)
        val b = AutoBookParser.buildSourceId(
            "alipay", 0, 3.0, "阿里云", "", "2026-10-15 23:14:43",
            1000L + AutoBookParser.FALLBACK_WINDOW_MS * 3
        )
        assertEquals(a, b)
    }

    @Test
    fun `既无订单号也无交易时间时才退回时间窗兜底`() {
        val a = AutoBookParser.buildSourceId("wechat", 0, 12.3, "麦当劳", "", "", 100_000L)
        val b = AutoBookParser.buildSourceId("wechat", 0, 12.3, "麦当劳", "", "", 100_500L)
        assertEquals("同一兜底窗口内视为同一笔", a, b)
        val c = AutoBookParser.buildSourceId(
            "wechat", 0, 12.3, "麦当劳", "", "", 100_000L + AutoBookParser.FALLBACK_WINDOW_MS
        )
        assertNotEquals("跨兜底窗口视为两笔（宁可重复也不能丢账）", a, c)
    }

    @Test
    fun `弹窗指纹不含商户名：商户抖动不影响同一笔的判定`() {
        // 真机实测同一笔两次识别出的商户名可以完全不同，指纹必须对此免疫
        val keys = AutoBookParser.buildDedupKeys("alipay", 0, 3.0, "", "2026-10-15 23:14:43")
        assertTrue("至少要有一条指纹", keys.isNotEmpty())
        assertTrue("交易时间应产出精确指纹", keys.any { it.value.startsWith("time:") })
        assertTrue("必须同时带兜底指纹", keys.any { it.value == "sum:alipay:0:3.0" })
        assertTrue(
            "指纹里不得出现任何商户名字段",
            keys.all { it.value == "sum:alipay:0:3.0" || it.value.startsWith("time:alipay:0:3.0:") }
        )
    }

    @Test
    fun `有订单号时同时产出精确指纹与兜底指纹`() {
        val keys = AutoBookParser.buildDedupKeys("alipay", 0, 3.0, "CFP000000000000000000", "")
        assertTrue(keys.any { it.value == "order:alipay:CFP000000000000000000" })
        assertTrue(keys.any { it.value == "sum:alipay:0:3.0" })
    }

    @Test
    fun `无订单号无交易时间时仍有兜底指纹`() {
        val keys = AutoBookParser.buildDedupKeys("wechat", 0, 12.3, "", "")
        assertEquals(listOf("sum:wechat:0:12.3"), keys.map { it.value })
    }

    @Test
    fun `兜底指纹的安静期比精确指纹短`() {
        val keys = AutoBookParser.buildDedupKeys("alipay", 0, 3.0, "CFP000000000000000000", "2026-10-15 23:14:43")
        val fuzzy = keys.first { it.value.startsWith("sum:") }
        val precise = keys.first { it.value.startsWith("order:") }
        assertTrue("兜底指纹窗口必须更短，否则会吞掉同金额的另一笔交易", fuzzy.ttlMs < precise.ttlMs)
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
