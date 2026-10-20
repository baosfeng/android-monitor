package com.bsfeng.monitor.autobook

import com.bsfeng.monitor.finance.model.CategoryCatalog
import com.bsfeng.monitor.finance.model.Transaction
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

    /**
     * 真机 dump 还原：微信**首页会话列表**（2026-10-17 20:47，误弹现场）。
     *
     * 误弹链路：会话名「微信支付」命中 pageMarkers → 会话预览
     * `收款方24小时内未接收你的转账，已过期` 被「收款方」标签取到值 → 判成账单详情页；
     * `微信支付 20:47` 的「支付」前缀又把小时数 20 取成金额 → 「识别到一笔收入 ¥20.00」。
     */
    private fun wechatHomeSessionListLines(): List<String> = listOf(
        "搜索 更多功能",
        "微信",
        "Mac 微信已登录",
        "18:33", "这个阿姨现在才过来，唉",
        "微信支付 20:47", "[4条] 红包退款到账通知",
        "李四 20:39",
        "收款方24小时内未接收你的转账，已过期",
        "20:36", "新华社: 男子为女友母亲养老送终，老人外…",
        "服务通知 20:31", "医保码: 医保账户变动提醒",
        "闲置物品分享群6群 20:17", "[2条] 笨小孩: 出14寸儿童自行车，徐径…",
        "上海小米YU7车主🤡群🚗 19:55", "月至: 累麻了",
        "QQ邮箱提醒 19:49", "X: 639100 is your X verification code",
        "顾晨曦 16:36", "可以的",
        "通讯录 发现 我",
        "Cool Request用户交流（1） 12:46"
    )

    @Test
    fun `微信首页会话列表不得被识别成账单`() {
        assertFalse(
            "首页会话列表没有任何账单页签名，白名单门禁必须拒绝",
            AutoBookParser.isBillPage(wechatHomeSessionListLines())
        )
        assertNull(AutoBookParser.parse(wechat, wechatHomeSessionListLines(), 1_760_000_000_000L))
    }

    @Test
    fun `会话列表里的「红包」与「40元」不得凑成红包结果页`() {
        // 真机 2026-10-17 20:52：会话预览「红包退款到账通知」+ 会话名「微信支付」
        // + 群消息「徐径自提40元」曾被宽签名（红包 + 微信支付 + 金额）拼成一笔 ¥40.00 收入。
        val lines = listOf(
            "搜索 更多功能", "微信", "Mac 微信已登录", "18:33", "这个阿姨现在才过来，唉",
            "微信支付 20:50", "红包退款到账通知", "李四 20:39",
            "收款方24小时内未接收你的转账，已过期", "20:36",
            "新华社: 男子为女友母亲养老送终，老人外…", "服务通知 20:31", "医保码: 医保账户变动提醒",
            "闲置物品分享群6群 20:17", "笨小孩: 出14寸儿童自行车，徐径自提40元",
            "上海小米YU7车主🤡群🚗 19:55", "月至: 累麻了", "QQ邮箱提醒 19:49",
            "X: 639100 is your X verification code", "顾晨曦 16:36", "可以的",
            "通讯录 发现 我", "Cool Request用户交流（1） 12:46"
        )
        assertFalse(AutoBookParser.isBillPage(lines))
        assertNull(AutoBookParser.parse(wechat, lines, 1_760_000_000_000L))
    }

    @Test
    fun `会话名带时间不得被当成金额`() {
        // 真机：`微信支付 20:47` 里「支付」是前缀、`20:47` 是时间，不是 ¥20.00
        assertNull(AutoBookParser.extractAmount(listOf("微信支付 20:47")))
        assertNull(AutoBookParser.extractAmount(listOf("支付 20:47")))
        // 真实金额仍然要能取到（不能把金额一起抹掉）
        assertEquals(20.0, AutoBookParser.extractAmount(listOf("微信支付 20.00"))!!.second, 0.0001)
    }

    @Test
    fun `会话预览的通知文案不得当作字段值`() {
        val lines = listOf("李四 20:39", "收款方24小时内未接收你的转账，已过期", "20:36")
        assertNull(AutoBookParser.labelValue(lines, listOf("收款方")))
    }

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
        assertEquals("日用百货", draft.platformCategory)
        assertEquals("家居日用", draft.categoryName)
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
        assertEquals("咖啡奶茶", draft.categoryName)
    }

    @Test
    fun `微信「已选择零钱，更改」只保留零钱`() {
        // 真机原文（微信收银台）：付款方式,已选择零钱,更改 更改 ——
        // 改造前这个值会原样进浮窗与账本备注，出现「，已选择零钱，更改」这种脏值。
        val lines = listOf(
            "支付成功",
            "-¥0.01",
            "商户全称 星巴克咖啡",
            "付款方式,已选择零钱,更改 更改",
            "支付时间 2026-10-16 12:06:11"
        )
        val draft = AutoBookParser.parse(wechat, lines, 1L)
        requireNotNull(draft)
        assertEquals("零钱", draft.payMethod)
        // 说明只来自用户输入：商户 / 付款方式已是结构化字段，不再拼进说明（2026-10-20）
        assertEquals("", draft.remark())
    }

    @Test
    fun `付款方式值前的逗号与「更改」入口都会被清掉`() {
        assertEquals(
            "已选择零钱,更改 更改",
            AutoBookParser.labelValue(listOf("付款方式,已选择零钱,更改 更改"), listOf("付款方式"))
        )
        assertEquals("零钱", AutoBookParser.extractAccount(listOf("付款方式，已选择零钱，更改")))
        assertEquals("零钱", AutoBookParser.extractAccount(listOf("付款方式 零钱")))
        assertEquals(
            "招商银行信用卡(8888)",
            AutoBookParser.extractAccount(listOf("付款方式 招商银行信用卡(8888)"))
        )
    }

    @Test
    fun `微信收银台与指纹页不得被识别成账单`() {
        // 真机日志（2026-10-17 16:06）：这两页都有「付款方式 + 零钱 + 金额」，
        // 旧门禁照样放行 —— 用户还没付款，浮窗就已经排上队了。
        val cashier = listOf(
            "关闭 使用密码",
            "付款给张三(***)",
            "￥0.01",
            "付款方式,已选择零钱,更改 付款方式 更改",
            "零钱",
            "支付"
        )
        assertFalse("收银台不是支付结果页", AutoBookParser.isBillPage(cashier))
        assertNull(AutoBookParser.parse(wechat, cashier, 1L))

        val fingerprint = listOf(
            "使用密码",
            "返回",
            "付款给张三(***)",
            "￥0.01 请验证指纹",
            "付款方式,已选择零钱,更改 更改",
            "零钱",
            "支付"
        )
        assertFalse("指纹验证页不是支付结果页", AutoBookParser.isBillPage(fingerprint))
        assertNull(AutoBookParser.parse(wechat, fingerprint, 1L))
    }

    @Test
    fun `支付宝收银台不得被识别成账单`() {
        assertNull(
            AutoBookParser.parse(
                alipay,
                listOf("确认付款", "付款金额 3.00", "余额宝", "立即支付"),
                1L
            )
        )
    }

    @Test
    fun `支付宝支付成功页标题与正文重复「支付成功」不得误杀`() {
        // 真机 2026-10-17：这一页被旧 hasMultipleTransactions 的 statusHits 判成列表，
        // 日志是 `非账单页,门禁拒绝 行数=6`，支付宝付完永远不弹窗。
        val lines = listOf(
            "支付成功￥0.01 回首页",
            "支付成功",
            "0.01",
            "楠楠的开心杂货铺 ￥0.01",
            "交易方式 余额宝(转出资金付款)",
            "完成"
        )
        assertTrue(
            "同一个金额重复三行是单笔结果页的正常形态，不能按金额行数判列表",
            AutoBookParser.isBillPage(lines)
        )
        val draft = AutoBookParser.parse(alipay, lines, 1L)
        requireNotNull(draft)
        assertEquals(0.01, draft.amount, 0.0001)
        assertEquals("余额宝(转出资金付款)", draft.payMethod)
    }

    @Test
    fun `支付宝转账成功页的「收款方」是标签，不得判成收入`() {
        // 真机 2026-10-17 16:33 原文（用户转账后浮窗显示「收入」）：
        // 「收款方」只是收款人标签，真正的方向词是后面的「转出资金付款」。
        val lines = listOf(
            "转账成功￥0.01 回首页",
            "转账成功",
            "0.01",
            "收款方 张三(***)",
            "交易方式 余额宝(转出资金付款)",
            "完成"
        )
        val draft = AutoBookParser.parse(alipay, lines, 1L)
        requireNotNull(draft)
        assertEquals(0.01, draft.amount, 0.0001)
        assertEquals("转出去的转账不能记成收入", AutoBookDraft.TYPE_EXPENSE, draft.type)
        assertEquals("张三(***)", draft.merchant)
        assertEquals("余额宝(转出资金付款)", draft.payMethod)
    }

    @Test
    fun `微信转账成功页「待对方确认收款」不得判成收入`() {
        // 「待李四确认收款」描述的是收款方的动作，对付款方来说是支出。
        val draft = AutoBookParser.parse(
            wechat,
            listOf("支付成功", "待李四确认收款", "¥0.01", "完成"),
            1L
        )
        requireNotNull(draft)
        assertEquals(AutoBookDraft.TYPE_EXPENSE, draft.type)
    }

    @Test
    fun `支付宝红包发出后聊天页用预采集金额补记一笔`() {
        // 真机 2026-10-17 16:28：红包付款成功后没有任何结果页，直接回聊天页；
        // 气泡只给「红包已被领取 / 张三领取了你的 红包」，没有金额 —— 金额只能来自付款前预采集。
        val pre = PrePaymentDraft(
            channel = AutoBookRules.CHANNEL_ALIPAY,
            type = AutoBookDraft.TYPE_EXPENSE,
            amount = 0.01,
            merchant = "",
            payMethod = "",
            remark = "",
            capturedAtMs = 1_000L
        )
        val chat = listOf(
            "2025年1月28日 19:43",
            "返回 资金往来记录 聊天设置",
            "红包已被领取",
            "发好运红包抽新年上上签， 去抽取",
            "icon 张三领取了你的 红包",
            "张三 表情,[动画表情]",
            "回复",
            "SOOGIF",
            "支付宝红包 16:28",
            "清风明月",
            "发红包 你好 在干嘛 我来了 谢谢老板 OK",
            "语音 发送消息... 表情 更多",
            "图片 灵感回复 红包 转账"
        )
        val draft = AutoBookParser.parseSentRedPacketChat(
            alipay, chat, pre, 2_000L, redPacketFlowPending = true
        )
        requireNotNull(draft)
        assertEquals(0.01, draft.amount, 0.0001)
        assertEquals("领取人应作为商户名", "张三", draft.merchant)
        assertEquals(AutoBookDraft.TYPE_EXPENSE, draft.type)
        assertEquals("红包已发出", draft.markers.single())
    }

    @Test
    fun `没走过红包付款流程时聊天页不得识别`() {
        // 打开历史聊天 / 取消发红包后回到聊天：没有「刚走过红包付款页」的事实，不能弹。
        val chat = listOf(
            "返回 资金往来记录 聊天设置",
            "红包已被领取",
            "icon 张三领取了你的 红包",
            "支付宝红包 16:28",
            "¥0.01",
            "语音 发送消息... 表情 更多"
        )
        val pre = PrePaymentDraft(
            channel = AutoBookRules.CHANNEL_ALIPAY,
            type = AutoBookDraft.TYPE_EXPENSE,
            amount = 0.01,
            merchant = "",
            payMethod = "",
            remark = "",
            capturedAtMs = 1_000L
        )
        assertNull(
            AutoBookParser.parseSentRedPacketChat(
                alipay, chat, pre, 2_000L,
                redPacketFlowPending = false, allowBubbleOnly = true
            )
        )
    }

    @Test
    fun `红包聊天页没有预采集或预采集过期时不得识别`() {
        val chat = listOf(
            "返回 资金往来记录 聊天设置",
            "红包已被领取",
            "icon 张三领取了你的 红包",
            "语音 发送消息... 表情 更多"
        )
        assertNull(
            "没有预采集、气泡也没有金额，宁可漏记也不能猜",
            AutoBookParser.parseSentRedPacketChat(
                alipay, chat, null, 1L, redPacketFlowPending = true
            )
        )
        val expired = PrePaymentDraft(
            channel = AutoBookRules.CHANNEL_ALIPAY,
            type = AutoBookDraft.TYPE_EXPENSE,
            amount = 0.01,
            merchant = "",
            payMethod = "",
            remark = "",
            capturedAtMs = 1L
        )
        assertNull(
            "预采集超过 2 分钟不得复用",
            AutoBookParser.parseSentRedPacketChat(
                alipay,
                chat,
                expired,
                AutoBookDraftMerger.PRE_DRAFT_TTL_MS + 2,
                redPacketFlowPending = true
            )
        )
    }

    @Test
    fun `红包刚出现（对方未领取）时气泡也能识别`() {
        // 真机 2026-10-17 16:49 原文：气泡未领取，页面给出 `支付宝红包 + ¥0.01 + 留言`。
        // allowBubbleOnly 由「红包流程已到指纹页」决定，避免在发红包页取消后打开旧聊天误弹。
        val pre = PrePaymentDraft(
            channel = AutoBookRules.CHANNEL_ALIPAY,
            type = AutoBookDraft.TYPE_EXPENSE,
            amount = 0.01,
            merchant = "张三(***)",
            payMethod = "余额宝",
            remark = "",
            capturedAtMs = 1_000L
        )
        val chat = listOf(
            "返回 资金往来记录 聊天设置",
            "张三",
            "支付宝红包",
            "清风明月",
            "¥0.01",
            "示例留言",
            "语音 发送消息... 表情 更多"
        )
        val draft = AutoBookParser.parseSentRedPacketChat(
            alipay, chat, pre, 2_000L,
            redPacketFlowPending = true, allowBubbleOnly = true
        )
        requireNotNull(draft)
        assertEquals(0.01, draft.amount, 0.0001)
        assertEquals("张三(***)", draft.merchant)
        // 只走过发红包输入页、还没到指纹页时，气泡不能单独触发
        assertNull(
            AutoBookParser.parseSentRedPacketChat(
                alipay, chat, pre, 2_000L,
                redPacketFlowPending = true, allowBubbleOnly = false
            )
        )
    }

    @Test
    fun `预采集丢失时用红包气泡自己的金额补记`() {
        // 服务重启 / 事件丢失会把预采集清空；但气泡本身带 `¥0.01`，只要已验过指纹就仍应能记。
        val chat = listOf(
            "返回 资金往来记录 聊天设置",
            "张三",
            "支付宝红包",
            "清风明月",
            "¥0.01",
            "示例留言",
            "语音 发送消息... 表情 更多"
        )
        val draft = AutoBookParser.parseSentRedPacketChat(
            alipay, chat, null, 2_000L,
            redPacketFlowPending = true, allowBubbleOnly = true
        )
        requireNotNull(draft)
        assertEquals(0.01, draft.amount, 0.0001)
        assertEquals("张三", draft.merchant)
        assertEquals(AutoBookDraft.TYPE_EXPENSE, draft.type)
    }

    @Test
    fun `气泡金额与预采集不一致时不得当成同一笔`() {
        val pre = PrePaymentDraft(
            channel = AutoBookRules.CHANNEL_ALIPAY,
            type = AutoBookDraft.TYPE_EXPENSE,
            amount = 0.01,
            merchant = "",
            payMethod = "",
            remark = "",
            capturedAtMs = 1_000L
        )
        val chat = listOf(
            "返回 资金往来记录 聊天设置",
            "支付宝红包",
            "¥0.50",
            "语音 发送消息... 表情 更多"
        )
        assertNull(
            AutoBookParser.parseSentRedPacketChat(
                alipay, chat, pre, 2_000L,
                redPacketFlowPending = true, allowBubbleOnly = true
            )
        )
    }

    @Test
    fun `支付宝红包确认页与收银台都要打上「正在发红包」标记`() {
        // 16:38 真机：确认页按钮「发红包」+ `24小时内未被领取，红包金额将退回`；
        // 收银台写的是「发普通红包」—— 只认「发红包」会在这里丢掉标记，聊天页兜底就不会触发。
        val confirm = listOf(
            "张三(***)", "金额", "0.01 元", "示例留言", "红包封面", "¥0.01",
            "发红包", "24小时内未被领取，红包金额将退回", "红包记录"
        )
        assertTrue(AutoBookParser.isRedPacketPrePayPage(confirm))

        val cashier = listOf(
            "关闭", "使用密码", "发普通红包", "余额宝", "招商银行储蓄卡",
            "付款", "确认付款，发普通红包，付款金额0.01元人民币"
        )
        assertTrue(AutoBookParser.isRedPacketPrePayPage(cashier))

        val chat = listOf(
            "返回 资金往来记录 聊天设置", "支付宝红包", "¥0.01",
            "语音 发送消息... 表情 更多"
        )
        assertFalse("聊天页底部也有「发红包」入口，不能被当成付款前页面", AutoBookParser.isRedPacketPrePayPage(chat))

        // 指纹页 / 密码页是「离付款成功只差一步」的标记
        assertTrue(AutoBookParser.isRedPacketFingerprintPage(listOf("取消 使用密码", "请验证指纹")))
        assertTrue(AutoBookParser.isRedPacketFingerprintPage(listOf("取消 使用密码", "正在校验指纹...")))
        assertFalse(AutoBookParser.isRedPacketFingerprintPage(listOf("返回 资金往来记录 聊天设置")))
    }

    @Test
    fun `只有「红包」两个字的页面不得被当成红包已发出`() {
        val pre = PrePaymentDraft(
            channel = AutoBookRules.CHANNEL_ALIPAY,
            type = AutoBookDraft.TYPE_EXPENSE,
            amount = 0.01,
            merchant = "",
            payMethod = "",
            remark = "",
            capturedAtMs = 1_000L
        )
        // 聊天页有红包气泡，但还没人领取；也没到指纹页 —— 不能凭猜弹窗
        val chat = listOf(
            "返回 资金往来记录 聊天设置",
            "支付宝红包 16:28",
            "语音 发送消息... 表情 更多"
        )
        assertNull(
            AutoBookParser.parseSentRedPacketChat(
                alipay, chat, pre, 2_000L, redPacketFlowPending = true
            )
        )
        // 微信红包走独立结果页，不走这条聊天页兜底
        assertNull(
            AutoBookParser.parseSentRedPacketChat(
                wechat, chat, pre, 2_000L,
                redPacketFlowPending = true, allowBubbleOnly = true
            )
        )
    }

    @Test
    fun `交易详情页与支付成功结果页要能区分`() {
        // 详情页有支付时间 / 订单号 / 商品说明 / 商户全称；结果页只有「交易方式」这类弱字段。
        assertTrue("支付宝账单详情页", AutoBookParser.isDetailPage(alipayDetailLines()))
        val wechatDetail = listOf(
            "微信支付", "支付成功", "-¥25.00",
            "商户全称 上海星巴克咖啡经营有限公司",
            "支付方式 零钱",
            "支付时间 2026-10-16 12:06:11"
        )
        assertTrue("微信交易详情页", AutoBookParser.isDetailPage(wechatDetail))

        val resultPage = listOf(
            "支付成功￥0.01 回首页", "支付成功", "0.01",
            "楠楠的开心杂货铺 ￥0.01", "交易方式 余额宝(转出资金付款)", "完成"
        )
        assertFalse("结果页不是详情页", AutoBookParser.isDetailPage(resultPage))

        val chat = listOf(
            "返回 资金往来记录 聊天设置", "张三", "¥0.01",
            "支付宝红包 16:45", "语音 发送消息... 表情 更多"
        )
        assertFalse("聊天页不是详情页", AutoBookParser.isDetailPage(chat))

        assertFalse(
            "付款前页面不是详情页",
            AutoBookParser.isDetailPage(listOf("请验证指纹", "付款金额0.01"))
        )
    }

    @Test
    fun `支付宝红包账单详情页不得把「领取中」当商户名`() {
        // 真机 2026-10-17 16:55 原文：详情页没有收款方字段，状态行是「领取中」。
        // 补全时应保留记账时的商户（张三），不能让状态词覆盖。
        val lines = listOf(
            "返回 全部账单", "账单详情", "支付宝红包", "支出0.01元", "领取中",
            "创建时间 2026-10-17 16:51:24", "付款方式 余额宝", "红包说明 发普通红包",
            "更多", "账单管理", "账单分类 转账红包", "标签 请选择", "为您推荐 发红包",
            "计入收支 开关", "备注 添加", "对此订单有疑问"
        )
        assertEquals("", AutoBookParser.extractMerchant(lines))
    }

    @Test
    fun `支付宝转账输入页与备注弹窗不得被识别成账单`() {
        // 真机 2026-10-17：输入页 / 备注弹窗同样带金额与「付款」，旧门禁在 16:20:32 误弹，
        // 商户名还取成了首页天气「多云 27℃」。
        val input = listOf(
            "付款给个人",
            "楠楠的开心杂货铺(**生)",
            "输入付款金额",
            "0.01",
            "推荐付款方式",
            "添加备注",
            "1 2 3 删除",
            "付款"
        )
        assertFalse("转账输入页不是账单页", AutoBookParser.isBillPage(input))
        assertNull(AutoBookParser.parse(alipay, input, 1L))

        val pre = AutoBookParser.parsePrePayment(alipay, input, 1L)
        requireNotNull(pre)
        assertEquals(0.01, pre.amount, 0.0001)
        assertEquals("楠楠的开心杂货铺(**生)", pre.merchant)

        val noteDialog = listOf("添加备注", "备注一下", "取消 确认")
        assertNull(AutoBookParser.parse(alipay, noteDialog, 1L))
        assertNull("只有占位文案，没有用户备注", AutoBookParser.parseRemarkDialog(noteDialog))
    }

    @Test
    fun `支付宝备注弹窗能捕获用户输入`() {
        assertEquals(
            "午饭",
            AutoBookParser.parseRemarkDialog(listOf("添加备注", "午饭", "取消 确认"))
        )
        // 混进首页长文本的「弹窗」不可信：宁可不要备注，也不能写「多云 27℃」
        assertNull(
            AutoBookParser.parseRemarkDialog(
                listOf(
                    "上海 搜索框", "多云 27℃", "付款给个人", "输入付款金额", "0.01",
                    "添加备注", "收款人可见，最多20个字", "取消 确认", "付款", "首页", "消息"
                )
            )
        )
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
        assertEquals("咖啡奶茶", draft.categoryName)
    }

    @Test
    fun `首页拼接出来的伪商户名被丢弃`() {
        // 真机：首页渲染中间态把一串 UI 文案拼成了商户名「上海 搜索框 搜索服务或小程序 搜索 更多操作」
        val lines = listOf("交易成功", "¥3.00", "上海 搜索框 搜索服务或小程序 搜索 更多操作")
        val draft = AutoBookParser.parseNotification(alipay, lines, 1L)
        requireNotNull(draft)
        assertEquals("宁可留空也不写垃圾商户名", "", draft.merchant)
    }

    @Test
    fun `转账支付链路各页的文案不得被当成商户名`() {
        // 真机实测（微信 8.0.79 / HyperOS，2026-10-16）：伪装白名单身份后微信交出支付页节点，
        // 但转账支付成功页没有任何商户标签，兜底逻辑把提示语「待李四确认收款」写成了商户名。
        // 转账给个人本来就没有商户，正确结果是留空。
        assertEquals(
            "支付成功页的提示语不得当商户名",
            "",
            AutoBookParser.extractMerchant(listOf("支付成功", "待李四确认收款", "¥ 0.01", "完成"))
        )
        // 同一条支付链路上的确认页 / 收银台文案同样不得入选
        assertEquals(
            "确认页文案不得当商户名",
            "",
            AutoBookParser.extractMerchant(listOf("返回", "向李四转账", "￥0.01", "微信支付"))
        )
        assertEquals(
            "收银台文案不得当商户名",
            "",
            AutoBookParser.extractMerchant(
                listOf("使用密码", "返回", "请验证指纹", "付款方式,已选择零钱,更改 更改", "支付")
            )
        )
    }

    @Test
    fun `微信红包发送结果页被识别为账单`() {
        // 真机实测（2026-10-16，微信 8.0.79）：红包付款成功后**没有**「支付成功」锚点页 ——
        // 转账有，红包发完直接回聊天页，而聊天页的红包气泡不含金额。
        // 金额只在这个极简结果页里出现过，不认它红包就永远记不上。
        val draft = AutoBookParser.parse(wechat, listOf("返回", "微信红包", "￥0.01", "微信支付"), 1L)
        requireNotNull(draft)
        assertEquals(0.01, draft.amount, 0.001)
        assertEquals(AutoBookDraft.TYPE_EXPENSE, draft.type)
        assertEquals("红包结果页没有收款方字段，商户应留空而不是写产品名", "", draft.merchant)
    }

    @Test
    fun `红包发送前与付款中的页面不得被识别`() {
        // 输入页（塞钱进红包 / 未领取的红包）
        assertNull(
            "塞钱进红包的输入页不得弹窗",
            AutoBookParser.parse(
                wechat,
                listOf(
                    "更多信息", "发红包", "金额", "恭喜发财，大吉大利", "添加表情", "红包封面",
                    "¥0.01", "塞钱进红包", "未领取的红包，将于24小时后发起退款"
                ),
                1L
            )
        )
        // 收银台（使用密码）
        assertNull(
            "收银台页面不得弹窗",
            AutoBookParser.parse(wechat, listOf("关闭 使用密码", "￥0.01 微信红包"), 1L)
        )
        // 指纹验证页
        assertNull(
            "指纹验证页不得弹窗",
            AutoBookParser.parse(wechat, listOf("返回", "请验证指纹"), 1L)
        )
    }

    @Test
    fun `说明不拼接商品说明与付款方式`() {
        // 2026-10-20 用户要求：账本那一列改成「说明」，只写用户在浮窗里填的文字。
        // 商品说明 / 付款方式各有结构化字段，再拼进说明只会让账本列表出现一长串重复文字。
        val lines = listOf(
            "返回 全部账单", "账单详情", "淘宝闪购", "支出23.9元", "交易成功",
            "支付时间 2026-10-16 18:32:21", "付款方式 招商银行信用卡(8888)",
            "商品说明 某某湘菜馆(示例店)外卖订单", "账单分类 餐饮美食"
        )
        val draft = AutoBookParser.parse(alipay, lines, 1L)
        requireNotNull(draft)
        assertEquals("淘宝闪购", draft.merchant)
        assertEquals("某某湘菜馆(示例店)外卖订单", draft.productDesc)
        assertEquals("招商银行信用卡(8888)", draft.payMethod)
        assertEquals("用户没写说明就是空串", "", draft.remark())
    }

    @Test
    fun `商户名取不到时说明仍为空`() {
        val lines = listOf(
            "交易成功", "支出23.9元", "支付时间 2026-10-16 18:32:21",
            "商品说明 某某湘菜馆(示例店)外卖订单"
        )
        val draft = AutoBookParser.parse(alipay, lines, 1L)
        requireNotNull(draft)
        assertEquals("这个模板没有商户字段", "", draft.merchant)
        assertEquals("商品说明只进结构化字段，不进说明", "", draft.remark())
    }

    @Test
    fun `交易时间规范化支持支付宝与微信两种格式`() {
        // 支付宝给 ISO，微信给中文年月日（真机实测两种都出现过）
        assertEquals("2026-10-16 18:32:21", AutoBookParser.normalizeTradeTime("2026-10-16 18:32:21"))
        assertEquals("2026-10-10 14:01:42", AutoBookParser.normalizeTradeTime("2026年10月10日 14:01:42"))
        // 页面只给到分时补 00（精度取页面能给到的最高，不编造秒）
        assertEquals("2026-10-16 18:32:00", AutoBookParser.normalizeTradeTime("2026-10-16 18:32"))
        // 认不出来时返回 null，由调用方退回记账时刻（宁可时间降级，也不能记错日期）
        assertNull(AutoBookParser.normalizeTradeTime(""))
        assertNull(AutoBookParser.normalizeTradeTime("昨天 14:01"))
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

    @Test
    fun `图标字体的私有区码位被剥掉，页面标题不再混进商户名`() {
        // 真机实测（2026-10-16 支付宝账单详情页）：顶部栏节点文本是「\ue642 账单详情」——
        // 图标字符让整行既不等于任何标签、也不以标签开头，躲过了噪声表，
        // 被兜底逻辑当成商户名写进账本（备注成了「自动记账·\ue642 账单详情」）。
        val nodes = listOf(
            NodeText("\ue642 账单详情", 75, 225),
            NodeText("全部账单", 960, 228),
            NodeText("淘宝闪购", 500, 365),
            NodeText("-23.90", 500, 445),
            NodeText("交易成功", 500, 510)
        )
        val lines = PageLines.toLines(nodes)
        assertEquals("图标字符应被剥掉、余下文字保留", "账单详情 全部账单", lines[0])

        // 剥掉图标后顶部栏被噪声表挡住，兜底才能取到真正的商户名（页面顶部居中大标题）
        assertEquals("淘宝闪购", AutoBookParser.extractMerchant(lines))
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

    // ==================== 分类映射（一级 + 二级） ====================

    /** 分类表全部合法名称：映射结果无论落一级还是二级，都不能游离在表外 */
    private val categoryNames: Set<String> = CategoryCatalog.allNames().toSet()

    private fun draftWithCategory(category: String): AutoBookDraft = AutoBookDraft(
        channel = AutoBookRules.CHANNEL_ALIPAY,
        type = AutoBookDraft.TYPE_EXPENSE,
        amount = 3.0,
        merchant = "阿里云计算有限公司",
        categoryName = category,
        accountName = "支付宝",
        payMethod = "余额宝",
        timeText = "",
        sourceId = "test:source",
        dedupKeys = emptyList(),
        markers = emptyList()
    )

    private fun historyWithCategory(type: Int, category: String): Transaction = Transaction(
        type = type,
        categoryName = category,
        amount = 3.0,
        accountName = "支付宝",
        date = "2026-10-15",
        dateTime = "2026-10-15 23:14:43",
        merchant = "阿里云计算有限公司"
    )

    @Test
    fun `平台分类优先于商户关键词`() {
        assertEquals("家居日用", AutoBookParser.mapCategory("日用百货", "星巴克", AutoBookDraft.TYPE_EXPENSE))
        assertEquals("餐饮", AutoBookParser.mapCategory("餐饮美食", "阿里云", AutoBookDraft.TYPE_EXPENSE))
        assertEquals("电商", AutoBookParser.mapCategory("购物", "阿里云", AutoBookDraft.TYPE_EXPENSE))
        assertEquals("其他", AutoBookParser.mapCategory(null, "某某科技有限公司", AutoBookDraft.TYPE_EXPENSE))
    }

    @Test
    fun `平台分类按设计文档映射到二级或一级`() {
        val cases = listOf(
            "餐饮美食" to "餐饮",
            "购物" to "电商",
            "电商" to "电商",
            "交通出行" to "交通",
            "医疗健康" to "医疗健康",
            "保险" to "保险",
            "生活缴费" to "居住",
            "数码电器" to "数码电子",
            "服饰装扮" to "服饰美妆",
            "教育培训" to "教育学习",
            "酒店旅游" to "旅游",
            "文化休闲" to "娱乐休闲",
            "日用百货" to "家居日用"
        )
        for ((platform, expected) in cases) {
            assertEquals(
                "平台分类「$platform」",
                expected,
                AutoBookParser.mapCategory(platform, "", AutoBookDraft.TYPE_EXPENSE)
            )
        }
    }

    @Test
    fun `设计文档列出的高频商户落到二级`() {
        val cases = listOf(
            // 购物
            "淘宝" to "电商",
            "天猫" to "电商",
            "京东" to "电商",
            "拼多多" to "电商",
            "唯品会" to "电商",
            // 餐饮
            "美团" to "外卖",
            "饿了么" to "外卖",
            "星巴克" to "咖啡奶茶",
            "瑞幸咖啡" to "咖啡奶茶",
            "麦当劳" to "正餐",
            "肯德基" to "正餐",
            "某某餐厅" to "正餐",
            "海底捞火锅" to "正餐",
            "烧烤店" to "正餐",
            // 交通 / 汽车
            "滴滴出行" to "打车",
            "T3出行" to "打车",
            "曹操出行" to "打车",
            "中国铁路12306" to "火车高铁",
            "上海地铁" to "公交地铁",
            "公交集团" to "公交地铁",
            "中国石化" to "加油",
            "中石油" to "加油",
            "停车缴费" to "停车",
            // 娱乐休闲
            "万达影城" to "电影",
            "腾讯游戏" to "游戏",
            "腾讯视频会员" to "会员订阅",
            // 医疗健康
            "市第一医院" to "门诊",
            "社区卫生服务中心" to "门诊",
            "大药房" to "药品",
            "药店" to "药品",
            // 通讯 / 居住
            "中国联通" to "话费",
            "中国移动" to "话费",
            "中国电信" to "话费",
            "水费缴纳" to "水电燃气",
            "电费" to "水电燃气",
            "燃气费" to "水电燃气",
            "物业管理有限公司" to "物业",
            // 金融保险 / 旅游
            "平安保险" to "保险",
            "如家酒店" to "住宿",
            "某民宿" to "住宿"
        )
        for ((merchant, expected) in cases) {
            assertEquals(
                "商户「$merchant」",
                expected,
                AutoBookParser.mapCategory(null, merchant, AutoBookDraft.TYPE_EXPENSE)
            )
        }
    }

    @Test
    fun `未知商户与未知平台分类兜底一级`() {
        assertEquals(
            "其他",
            AutoBookParser.mapCategory(null, "某某科技有限公司", AutoBookDraft.TYPE_EXPENSE)
        )
        assertEquals(
            "其他",
            AutoBookParser.mapCategory("不明平台分类", "某某科技有限公司", AutoBookDraft.TYPE_EXPENSE)
        )
        assertEquals(
            "其他收入",
            AutoBookParser.mapCategory(null, "某某科技有限公司", AutoBookDraft.TYPE_INCOME)
        )
        assertEquals(
            "其他收入",
            AutoBookParser.mapCategory("不明平台分类", "张三", AutoBookDraft.TYPE_INCOME)
        )
    }

    @Test
    fun `收入侧用收入分类表：退款工资与红包落到收入分类`() {
        assertEquals("退款", AutoBookParser.mapCategory(null, "淘宝退款", AutoBookDraft.TYPE_INCOME))
        assertEquals("工资", AutoBookParser.mapCategory(null, "某某公司代发工资", AutoBookDraft.TYPE_INCOME))
        assertEquals("理财收益", AutoBookParser.mapCategory(null, "某某基金分红", AutoBookDraft.TYPE_INCOME))
        assertEquals("红包", AutoBookParser.mapCategory("红包", "", AutoBookDraft.TYPE_INCOME))
        // 同一商户在支出侧走支出表，不能把收入分类套到支出上
        assertEquals("电商", AutoBookParser.mapCategory(null, "淘宝", AutoBookDraft.TYPE_EXPENSE))
    }

    @Test
    fun `所有映射结果都在分类表内`() {
        val probes = listOf(
            "餐饮美食", "购物", "电商", "交通出行", "医疗健康", "保险", "生活缴费",
            "数码电器", "服饰装扮", "教育培训", "酒店旅游", "文化休闲", "日用百货",
            "转账红包", "不明平台分类", "其他"
        ) + listOf(
            "淘宝", "美团", "饿了么", "滴滴", "12306", "地铁", "星巴克", "瑞幸", "麦当劳",
            "肯德基", "火锅", "中石化", "停车", "电影", "游戏", "腾讯视频会员", "医院",
            "药房", "中国联通", "水费", "电费", "燃气", "物业", "平安保险", "酒店", "民宿",
            "阿里云", "某市供水公司", "某某科技有限公司"
        )
        for (probe in probes) {
            for (type in listOf(AutoBookDraft.TYPE_EXPENSE, AutoBookDraft.TYPE_INCOME)) {
                val category = AutoBookParser.mapCategory(probe, probe, type)
                assertTrue(
                    "「$probe」在 type=$type 下映射出分类表外的名字「$category」",
                    category in categoryNames
                )
            }
        }
    }

    @Test
    fun `红包聊天页兜底落人情往来的发红包`() {
        val chat = listOf(
            "返回 资金往来记录 聊天设置", "张三", "支付宝红包", "清风明月", "¥0.01", "示例留言",
            "语音 发送消息... 表情 更多"
        )
        val draft = AutoBookParser.parseSentRedPacketChat(
            alipay, chat, null, 2_000L,
            redPacketFlowPending = true, allowBubbleOnly = true
        )
        requireNotNull(draft)
        assertEquals("发红包", draft.categoryName)
    }

    @Test
    fun `历史默认分类是旧扁平名时回退解析结果`() {
        val draft = draftWithCategory("云服务")
        val legacy = historyWithCategory(AutoBookDraft.TYPE_EXPENSE, "日用")
        assertEquals("云服务", AutoBookDefaults.apply(draft, legacy).categoryName)
    }

    @Test
    fun `历史默认分类是二级名时原样沿用`() {
        val draft = draftWithCategory("其他")
        val last = historyWithCategory(AutoBookDraft.TYPE_EXPENSE, "咖啡奶茶")
        assertEquals("咖啡奶茶", AutoBookDefaults.apply(draft, last).categoryName)
    }

    @Test
    fun `历史默认分类与类型不同侧时忽略`() {
        val draft = draftWithCategory("其他")
        // 历史是收入分类「工资」，这次是支出：不能把它套到支出上
        val last = historyWithCategory(AutoBookDraft.TYPE_EXPENSE, "工资")
        assertEquals("其他", AutoBookDefaults.apply(draft, last).categoryName)
    }
}
