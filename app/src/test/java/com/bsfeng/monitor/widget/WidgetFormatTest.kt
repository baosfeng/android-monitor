package com.bsfeng.monitor.widget

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 小组件文案格式化（纯 JVM）。
 *
 * 为什么这些必须钉住：组件渲染只能靠真机截图，能自动验证的就是「数字 → 文案」这一步；
 * 金额少一位、百分比进错位、时间说成「昨天」这种事肉眼很难在桌面上发现。
 */
class WidgetFormatTest {

    // ========== 金额 ==========

    @Test
    fun `1×1 档金额只留整数不截断`() {
        // 80dp 宽的卡片放不下「¥4,286.50」，主动丢小数，整数部分必须完整
        assertEquals("¥4,286", tinyMoney(4286.4, hidden = false))
        assertEquals("¥0", tinyMoney(0.0, hidden = false))
        assertEquals("¥1,234,568", tinyMoney(1234567.89, hidden = false))
        assertEquals("-¥13", tinyMoney(-12.5, hidden = false))
    }

    @Test
    fun `1×1 档隐藏金额同样不泄露量级`() {
        assertEquals("¥••••", tinyMoney(4286.4, hidden = true))
        assertEquals("¥••••", tinyMoney(-99.9, hidden = true))
    }

    @Test
    fun `金额带千分位与两位小数`() {
        assertEquals("¥1,234.00", money(1234.0, hidden = false))
        assertEquals("¥0.00", money(0.0, hidden = false))
        assertEquals("¥1,234,567.89", money(1234567.891, hidden = false))
    }

    @Test
    fun `负金额把符号放在货币符号外侧`() {
        // 进度条 / 结余算出负数时必须读成「-¥12.50」，而不是「¥-12.50」
        assertEquals("-¥12.50", money(-12.5, hidden = false))
    }

    @Test
    fun `隐藏金额时一律四个圆点且不泄露数量级`() {
        assertEquals("¥••••", money(1234.0, hidden = true))
        assertEquals("¥••••", money(0.0, hidden = true))
        assertEquals("¥••••", money(-99.9, hidden = true))
    }

    // ========== 计数 ==========

    @Test
    fun `计数补千分位`() {
        assertEquals("0", count(0))
        assertEquals("999", count(999))
        assertEquals("1,234", count(1234))
        assertEquals("-5", count(-5))
    }

    // ========== 百分比 ==========

    @Test
    fun `百分比按四舍五入取整`() {
        assertEquals(0, percent(0f))
        assertEquals(12, percent(0.12f))
        assertEquals(50, percent(0.5f))
        assertEquals(100, percent(1f))
        assertEquals(46, percent(0.46f))
        // 46.5% 必须进位到 47：用 toInt() 截断会得到 46，进度条与文字就对不上了
        assertEquals(47, percent(0.465f))
        assertEquals(13, percent(0.125f))
    }

    @Test
    fun `百分比对超界与脏数据夹到 0 到 100`() {
        assertEquals(100, percent(1.5f))
        assertEquals(0, percent(-0.2f))
        assertEquals(0, percent(Float.NaN))
        assertEquals(100, percent(Float.POSITIVE_INFINITY))
    }

    // ========== 预算文案 ==========

    @Test
    fun `没有预算时说未设预算`() {
        assertEquals("未设预算", budgetText(progress = null, remaining = null, hasBudget = false))
        // 有预算但金额为 0 的脏数据同样按未设预算处理
        assertEquals("未设预算", budgetText(progress = 0.4f, remaining = null, hasBudget = false))
    }

    @Test
    fun `进度到满额说已超支`() {
        assertEquals("已超支", budgetText(progress = 1f, remaining = 0.0, hasBudget = true))
        assertEquals("已超支", budgetText(progress = 1.4f, remaining = 0.0, hasBudget = true))
    }

    @Test
    fun `有剩余金额时优先说金额`() {
        assertEquals("剩 ¥123.40", budgetText(progress = 0.5f, remaining = 123.4, hasBudget = true))
        assertEquals("剩 ¥0.00", budgetText(progress = 0.99f, remaining = 0.0, hasBudget = true))
    }

    @Test
    fun `隐藏金额时传 null 退回百分比`() {
        assertEquals("剩 38%", budgetText(progress = 0.62f, remaining = null, hasBudget = true))
        assertEquals("剩 100%", budgetText(progress = 0f, remaining = null, hasBudget = true))
    }

    @Test
    fun `只有预算行没有金额时不说数字`() {
        assertEquals("已设预算", budgetText(progress = null, remaining = null, hasBudget = true))
    }

    // ========== 相对时间 ==========

    private val now = 1_700_000_000_000L

    @Test
    fun `从未更新显示破折号而不是刚刚`() {
        assertEquals("—", ageText(now, 0L))
        assertEquals("—", ageText(now, -1L))
    }

    @Test
    fun `一分钟内说刚刚`() {
        assertEquals("刚刚", ageText(now, now))
        assertEquals("刚刚", ageText(now, now - 59_999L))
        // 时钟回拨（then 在未来）不能显示「-3 分钟前」
        assertEquals("刚刚", ageText(now, now + 180_000L))
    }

    @Test
    fun `分钟边界`() {
        assertEquals("1 分钟前", ageText(now, now - 60_000L))
        assertEquals("59 分钟前", ageText(now, now - 59 * 60_000L))
    }

    @Test
    fun `小时边界`() {
        assertEquals("1 小时前", ageText(now, now - 3_600_000L))
        assertEquals("23 小时前", ageText(now, now - 23 * 3_600_000L))
    }

    @Test
    fun `一天以上先说明天再报天数`() {
        assertEquals("昨天", ageText(now, now - 24 * 3_600_000L))
        assertEquals("昨天", ageText(now, now - 47 * 3_600_000L))
        assertEquals("2 天前", ageText(now, now - 48 * 3_600_000L))
    }
}
