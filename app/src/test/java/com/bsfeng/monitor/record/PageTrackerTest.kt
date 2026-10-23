package com.bsfeng.monitor.record

import com.bsfeng.monitor.domain.model.PageHandler
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 页面指纹与轨迹状态机的纯逻辑用例。
 *
 * 重点：**同一页面文本顺序不同必须得到同一指纹**（遍历顺序不稳定是真实抖动来源），
 * 以及「不产生页面文本」这一隐私约束的实现方式（文本只做哈希输入）。
 */
class PageTrackerTest {

    private val pkg = "com.example.app"
    private val activity = "com.example.app.MainActivity"

    // ===== 指纹 =====

    @Test
    fun `文本顺序不同得到相同指纹`() {
        val a = PageTracker.fingerprint(pkg, activity, listOf("首页", "搜索", "消息"))
        val b = PageTracker.fingerprint(pkg, activity, listOf("消息", "首页", "搜索"))
        assertEquals(a, b)
        assertEquals(PageTracker.KEY_LENGTH, a.length)
    }

    @Test
    fun `文本内容不同得到不同指纹`() {
        val a = PageTracker.fingerprint(pkg, activity, listOf("首页", "搜索"))
        val b = PageTracker.fingerprint(pkg, activity, listOf("首页", "设置"))
        assertNotEquals(a, b)
    }

    @Test
    fun `不同包名或 Activity 得到不同指纹`() {
        val base = PageTracker.fingerprint(pkg, activity, listOf("首页"))
        assertNotEquals(base, PageTracker.fingerprint("com.other.app", activity, listOf("首页")))
        assertNotEquals(base, PageTracker.fingerprint(pkg, "com.example.app.DetailActivity", listOf("首页")))
    }

    @Test
    fun `空白与重复文本被规整后指纹一致`() {
        val messy = PageTracker.fingerprint(pkg, activity, listOf(" 首页 ", "", "首页", "  "))
        val clean = PageTracker.fingerprint(pkg, activity, listOf("首页"))
        assertEquals(clean, messy)
    }

    @Test
    fun `最多取前 20 条可见文本`() {
        val twenty = (1..20).map { "item%02d".format(it) }
        // 第 21 条按字典序排在最后：不影响排序后的前 20 条，指纹必须一致
        val extra = twenty + "zzz-extra"
        assertEquals(
            PageTracker.fingerprint(pkg, activity, twenty),
            PageTracker.fingerprint(pkg, activity, extra),
        )
        assertEquals(PageTracker.MAX_TEXTS, PageTracker.normalizeVisibleTexts(extra).size)
        assertEquals(PageTracker.MAX_TEXTS, PageTracker.normalizeVisibleTexts((1..25).map { "x$it" }).size)
    }

    // ===== 状态机 =====

    @Test
    fun `首次进入产生 ENTER 且不带时长`() {
        val tracker = PageTracker(pollConfirmMs = 0L)
        val events = tracker.observe(pkg, activity, listOf("首页"), "示例", PageEvent.SOURCE_POLL, 1_000L)
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(PageEvent.ACTION_ENTER, event.action)
        assertNull(event.durationMs)
        assertEquals(pkg, event.packageName)
        assertEquals(PageTracker.fingerprint(pkg, activity, listOf("首页")), event.pageKey)
        assertNotNull(tracker.currentPageKey)
    }

    @Test
    fun `同 App 换页只写一条 CHANGED 并带停留时长`() {
        val tracker = PageTracker(pollConfirmMs = 0L)
        tracker.observe(pkg, activity, listOf("首页"), "示例", PageEvent.SOURCE_POLL, 1_000L)
        val events = tracker.observe(pkg, activity, listOf("详情"), "示例", PageEvent.SOURCE_POLL, 4_000L)
        assertEquals(1, events.size)
        val event = events.single()
        assertEquals(PageEvent.ACTION_CHANGED, event.action)
        assertEquals(3_000L, event.durationMs)
        // 结算的是**离开**的那一页，指纹仍是旧页
        assertEquals(PageTracker.fingerprint(pkg, activity, listOf("首页")), event.pageKey)
    }

    @Test
    fun `换 App 先结算旧页再写 ENTER`() {
        val tracker = PageTracker(pollConfirmMs = 0L)
        tracker.observe(pkg, activity, listOf("首页"), "示例", PageEvent.SOURCE_POLL, 1_000L)
        val events = tracker.observe("com.other.app", "OtherActivity", listOf("首页"), "另一个", PageEvent.SOURCE_WINDOW_EVENT, 6_000L)
        assertEquals(2, events.size)
        assertEquals(PageEvent.ACTION_CHANGED, events[0].action)
        assertEquals(pkg, events[0].packageName)
        assertEquals(5_000L, events[0].durationMs)
        assertEquals(PageEvent.ACTION_ENTER, events[1].action)
        assertEquals("com.other.app", events[1].packageName)
        assertNull(events[1].durationMs)
    }

    @Test
    fun `离开 App 结算当前页并清空状态`() {
        val tracker = PageTracker(pollConfirmMs = 0L)
        tracker.observe(pkg, activity, listOf("首页"), "示例", PageEvent.SOURCE_POLL, 1_000L)
        val events = tracker.observe(null, null, emptyList(), null, PageEvent.SOURCE_POLL, 2_500L)
        assertEquals(1, events.size)
        assertEquals(PageEvent.ACTION_CHANGED, events.single().action)
        assertEquals(1_500L, events.single().durationMs)
        assertNull(tracker.currentPageKey)
        // 已经在桌面：再喂一次不产生事件
        assertTrue(tracker.observe(null, null, emptyList(), null, PageEvent.SOURCE_POLL, 3_000L).isEmpty())
    }

    @Test
    fun `同一页面文本从无到有不算换页`() {
        val tracker = PageTracker(pollConfirmMs = 0L)
        // 窗口事件先到：只有包名 + Activity，无文本
        tracker.observe(pkg, activity, emptyList(), "示例", PageEvent.SOURCE_WINDOW_EVENT, 1_000L)
        // 轮询读到文本：同一页面，只是指纹升级
        val events = tracker.observe(pkg, activity, listOf("首页"), "示例", PageEvent.SOURCE_POLL, 2_000L)
        assertTrue(events.isEmpty())
        assertEquals(PageTracker.fingerprint(pkg, activity, listOf("首页")), tracker.currentPageKey)
    }

    @Test
    fun `轮询抖动要稳定够久才认定换页`() {
        val tracker = PageTracker(pollConfirmMs = 3_000L)
        tracker.observe(pkg, activity, listOf("首页"), "示例", PageEvent.SOURCE_POLL, 1_000L)
        // 滚动导致文本变化：每次新指纹都不同，不能每轮记一条
        assertTrue(tracker.observe(pkg, activity, listOf("滚动1"), "示例", PageEvent.SOURCE_POLL, 2_000L).isEmpty())
        assertTrue(tracker.observe(pkg, activity, listOf("滚动2"), "示例", PageEvent.SOURCE_POLL, 3_000L).isEmpty())
        assertTrue(tracker.observe(pkg, activity, listOf("滚动3"), "示例", PageEvent.SOURCE_POLL, 4_000L).isEmpty())
        assertEquals(PageTracker.fingerprint(pkg, activity, listOf("首页")), tracker.currentPageKey)
        // 稳定 3 秒后同一指纹：认定换页
        assertTrue(tracker.observe(pkg, activity, listOf("滚动3"), "示例", PageEvent.SOURCE_POLL, 6_000L).isEmpty())
        val events = tracker.observe(pkg, activity, listOf("滚动3"), "示例", PageEvent.SOURCE_POLL, 7_000L)
        assertEquals(1, events.size)
        assertEquals(PageEvent.ACTION_CHANGED, events.single().action)
        // duration 是**离开的旧页**停留时长（1_000 → 7_000），不是确认窗口
        assertEquals(6_000L, events.single().durationMs)
        assertEquals(PageTracker.fingerprint(pkg, activity, listOf("滚动3")), tracker.currentPageKey)
    }

    @Test
    fun `flush 结算当前页并标注来源`() {
        val tracker = PageTracker(pollConfirmMs = 0L)
        tracker.observe(pkg, activity, listOf("首页"), "示例", PageEvent.SOURCE_POLL, 1_000L)
        val events = tracker.flush(9_000L)
        assertEquals(1, events.size)
        assertEquals(PageEvent.ACTION_CHANGED, events.single().action)
        assertEquals(PageEvent.SOURCE_FLUSH, events.single().source)
        assertEquals(8_000L, events.single().durationMs)
        assertTrue(tracker.flush(10_000L).isEmpty())
    }

    @Test
    fun `detail 只允许白名单字段`() {
        // 指纹字段本身的约束：pageKey 只有 8 位十六进制，不含任何页面原文
        val key = PageTracker.fingerprint(pkg, activity, listOf("转账给张三 1000 元"))
        assertTrue(key.matches(Regex("[0-9a-f]{8}")))
    }

    // ===== 自身包名（P0：本 App 记录了自己） =====

    @Test
    fun `自身包名不产生页面记录`() {
        val self = PageTracker.SELF_PACKAGE
        val tracker = PageTracker(pollConfirmMs = 0L, selfPackage = self)

        // 冷启动就在本 App 内：不产生 ENTER
        assertTrue(
            tracker.observe(self, "com.bsfeng.monitor.MainActivity", listOf("设置"), "行为记录",
                PageEvent.SOURCE_WINDOW_EVENT, 1_000L).isEmpty()
        )
        assertNull(tracker.currentPageKey)

        // 外部 App → 本 App：只结算外部页（带停留时长），不产生本 App 的 ENTER
        tracker.observe("com.tencent.mm", "LauncherUI", listOf("聊天"), "微信",
            PageEvent.SOURCE_WINDOW_EVENT, 2_000L)
        val leaving = tracker.observe(self, "com.bsfeng.monitor.MainActivity", listOf("实时"), "行为记录",
            PageEvent.SOURCE_WINDOW_EVENT, 5_000L)
        assertEquals(1, leaving.size)
        assertEquals(PageEvent.ACTION_CHANGED, leaving.single().action)
        assertEquals("com.tencent.mm", leaving.single().packageName)
        assertEquals(3_000L, leaving.single().durationMs)
        assertNull(tracker.currentPageKey)

        // 本 App 内继续切 Tab（真实刷屏场景：一秒 4 条）：仍然一条都不产生
        assertTrue(
            tracker.observe(self, "com.bsfeng.monitor.MainActivity", listOf("概览"), "行为记录",
                PageEvent.SOURCE_POLL, 6_000L).isEmpty()
        )
        assertTrue(
            tracker.observe(self, "com.bsfeng.monitor.MainActivity", listOf("广告"), "行为记录",
                PageEvent.SOURCE_WINDOW_EVENT, 6_200L).isEmpty()
        )
        assertTrue(
            tracker.observe(self, "com.bsfeng.monitor.MainActivity", listOf("记账"), "行为记录",
                PageEvent.SOURCE_WINDOW_EVENT, 6_400L).isEmpty()
        )
    }

    // ===== label 可读性（P1：8 位裸 hash → 页面 #短码） =====

    @Test
    fun `label 用页面短码 且短码取自完整指纹前 4 位`() {
        val handler = PageHandler()
        val detail = mapOf("app_name" to "微信", "page_key" to "c74c11eb")
        assertEquals("微信 · 页面 #c74c", handler.createLabel("ENTER", detail))
        assertEquals("c74c", detail.getValue("page_key").take(PageHandler.SHORT_KEY_LENGTH))
        // label 不改 detail：完整 8 位指纹原样保留给去重与聚合
        assertEquals("c74c11eb", detail["page_key"])
    }

    @Test
    fun `label 的降级路径`() {
        val handler = PageHandler()
        // 缺 app_name → 回落包名
        assertEquals(
            "com.tencent.mm · 页面 #c74c",
            handler.createLabel("CHANGED", mapOf("package_name" to "com.tencent.mm", "page_key" to "c74c11eb"))
        )
        // 无 page_key → 只剩应用名（不编造编号）
        assertEquals("微信", handler.createLabel("CHANGED", mapOf("app_name" to "微信")))
        // 两者都缺 → 兜底文案
        assertEquals("页面", handler.createLabel("CHANGED", null))
    }

    @Test
    fun `同指纹必得同短码 不同指纹短码不同`() {
        val a = PageTracker.fingerprint(pkg, activity, listOf("首页", "搜索"))
        val a2 = PageTracker.fingerprint(pkg, activity, listOf("搜索", "首页"))
        val b = PageTracker.fingerprint(pkg, activity, listOf("设置"))
        assertEquals(a.take(PageHandler.SHORT_KEY_LENGTH), a2.take(PageHandler.SHORT_KEY_LENGTH))
        assertNotEquals(a.take(PageHandler.SHORT_KEY_LENGTH), b.take(PageHandler.SHORT_KEY_LENGTH))
    }
}
