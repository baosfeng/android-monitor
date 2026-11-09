package com.bsfeng.monitor.finance

import android.content.Context
import com.bsfeng.monitor.finance.model.Transaction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * 五档周期统计（[FinancePeriodService] / [FinanceManager.getPeriodStats]）单测。
 *
 * 固定「今天」= 2026-10-15（周四），避免跨零点抖动；三档长度（day=24 / week=7 / year=12）
 * 与三值口径（支出含负额退款抵扣、收入只取 type=1）是本轮验收的核心断言。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FinancePeriodStatsTest {

    private lateinit var context: Context
    private lateinit var manager: FinanceManager

    /** 固定「今天」：周三 2026-10-14 用作对照时要注意周一是 2026-10-12 */
    private val today: LocalDate = LocalDate.parse("2026-10-15")

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        FinanceDatabaseHelper.resetForTesting()
        context.deleteDatabase(FinanceDatabaseHelper.DATABASE_NAME)
        manager = FinanceManager(context)
    }

    @After
    fun tearDown() {
        FinanceDatabaseHelper.resetForTesting()
        context.deleteDatabase(FinanceDatabaseHelper.DATABASE_NAME)
    }

    private fun tx(
        type: Int = 0,
        amount: Double = 10.0,
        date: String = "2026-10-15",
        hour: String = "12",
        category: String = "餐饮",
    ) = Transaction(
        type = type,
        categoryName = category,
        amount = amount,
        accountName = "支付宝",
        date = date,
        dateTime = "$date $hour:00:00",
    )

    private fun insert(period: FinancePeriod): com.bsfeng.monitor.finance.model.PeriodStats =
        runBlocking { manager.getPeriodStatsAt(period, today) }

    @Test
    fun `day 档 24 小时分桶 只算今天`() {
        manager.dao.insertTransaction(tx(amount = 20.0, date = "2026-10-15", hour = "09"))
        manager.dao.insertTransaction(tx(amount = 30.0, date = "2026-10-15", hour = "12"))
        manager.dao.insertTransaction(tx(type = 1, amount = 5.0, date = "2026-10-15", hour = "12", category = "红包"))
        // 昨天与前天的都不该进今日档
        manager.dao.insertTransaction(tx(amount = 99.0, date = "2026-10-14", hour = "23"))
        manager.dao.insertTransaction(tx(amount = 99.0, date = "2026-10-13", hour = "08"))

        val stats = insert(FinancePeriod.DAY)
        assertEquals("day 桶数固定 24", 24, stats.buckets.size)
        assertEquals(50.0, stats.totalExpense, 0.001)
        assertEquals(5.0, stats.totalIncome, 0.001)
        assertEquals("结余 = 收入 - 支出", -45.0, stats.balance, 0.001)
        assertEquals("今日 3 笔", 3, stats.transactionCount)
        assertEquals("09 点桶", 20.0, stats.buckets[9].expense, 0.001)
        assertEquals("12 点桶", 30.0, stats.buckets[12].expense, 0.001)
        assertEquals("12 点桶收入", 5.0, stats.buckets[12].income, 0.001)
        assertEquals("12 点桶含支出与收入两笔", 2, stats.buckets[12].count)
        assertEquals("空桶为 0", 0.0, stats.buckets[0].expense, 0.001)
        assertEquals("标签是两位小时", "09", stats.labels[9])
    }

    @Test
    fun `week 档 7 日分桶 从周一算到今天`() {
        // 2026-10-15 是周四 → 本周一 2026-10-12；上周日 2026-10-11 不在本周
        manager.dao.insertTransaction(tx(amount = 11.0, date = "2026-10-12"))
        manager.dao.insertTransaction(tx(amount = 22.0, date = "2026-10-15"))
        manager.dao.insertTransaction(tx(amount = 99.0, date = "2026-10-11"))

        val stats = insert(FinancePeriod.WEEK)
        assertEquals("week 桶数固定 7", 7, stats.buckets.size)
        assertEquals(listOf("一", "二", "三", "四", "五", "六", "日"), stats.labels)
        assertEquals(33.0, stats.totalExpense, 0.001)
        assertEquals("周一的桶", 11.0, stats.buckets[0].expense, 0.001)
        assertEquals("周四（今天）的桶", 22.0, stats.buckets[3].expense, 0.001)
        assertEquals("上周日不算", 0.0, stats.buckets[6].expense, 0.001)
    }

    @Test
    fun `month 档按当月逐日分桶`() {
        manager.dao.insertTransaction(tx(amount = 10.0, date = "2026-10-01"))
        manager.dao.insertTransaction(tx(amount = 40.0, date = "2026-10-15"))
        // 上月末与下月都不在本月
        manager.dao.insertTransaction(tx(amount = 99.0, date = "2026-09-30"))

        val stats = insert(FinancePeriod.MONTH)
        assertEquals("2026-10 有 31 天", 31, stats.buckets.size)
        assertEquals(50.0, stats.totalExpense, 0.001)
        assertEquals(10.0, stats.buckets[0].expense, 0.001)
        assertEquals(40.0, stats.buckets[14].expense, 0.001)
        assertEquals("2026-10-01", stats.fromDate)
        assertEquals("2026-10-15", stats.toDate)
    }

    @Test
    fun `year 档 12 月分桶`() {
        manager.dao.insertTransaction(tx(amount = 100.0, date = "2026-01-20"))
        manager.dao.insertTransaction(tx(amount = 200.0, date = "2026-10-15"))
        manager.dao.insertTransaction(tx(amount = 99.0, date = "2025-12-31"))

        val stats = insert(FinancePeriod.YEAR)
        assertEquals("year 桶数固定 12", 12, stats.buckets.size)
        assertEquals(300.0, stats.totalExpense, 0.001)
        assertEquals(100.0, stats.buckets[0].expense, 0.001)
        assertEquals(200.0, stats.buckets[9].expense, 0.001)
        assertEquals("2026-01-01", stats.fromDate)
    }

    @Test
    fun `all 档逐月分桶 覆盖库内最早月到今天`() {
        manager.dao.insertTransaction(tx(amount = 10.0, date = "2025-11-08"))
        manager.dao.insertTransaction(tx(amount = 20.0, date = "2026-10-15"))
        manager.dao.insertTransaction(tx(type = 1, amount = 30.0, date = "2026-10-15", category = "薪资"))

        val stats = insert(FinancePeriod.ALL)
        assertEquals("2025-11..2026-10 共 12 个月", 12, stats.buckets.size)
        assertEquals("2025-11 是首桶", "2025-11", stats.labels.first())
        assertEquals("2026-10 是末桶", "2026-10", stats.labels.last())
        assertEquals(10.0, stats.buckets[0].expense, 0.001)
        assertEquals(20.0, stats.buckets.last().expense, 0.001)
        assertEquals(30.0, stats.buckets.last().income, 0.001)
        assertEquals("2025-11-08", stats.fromDate)
    }

    @Test
    fun `五档三值口径一致 支出含负额退款抵扣`() {
        manager.dao.insertTransaction(tx(amount = 100.0, date = "2026-10-15"))
        manager.dao.insertTransaction(tx(amount = -30.0, date = "2026-10-15", category = "退款"))
        manager.dao.insertTransaction(tx(type = 1, amount = 500.0, date = "2026-10-15", category = "薪资"))

        val day = insert(FinancePeriod.DAY)
        assertEquals("退款负额抵扣支出", 70.0, day.totalExpense, 0.001)
        assertEquals(500.0, day.totalIncome, 0.001)
        assertEquals(430.0, day.balance, 0.001)
        // 同一笔数据在周 / 月 / 年 / 全部四档都应给出同样的三值
        listOf(FinancePeriod.WEEK, FinancePeriod.MONTH, FinancePeriod.YEAR, FinancePeriod.ALL)
            .forEach { unit ->
                val stats = insert(unit)
                assertEquals(unit.key + " 支出", 70.0, stats.totalExpense, 0.001)
                assertEquals(unit.key + " 收入", 500.0, stats.totalIncome, 0.001)
            }
    }

    @Test
    fun `空库五档都返回定长零桶`() {
        val expected = mapOf(
            FinancePeriod.DAY to 24,
            FinancePeriod.WEEK to 7,
            FinancePeriod.MONTH to 31,
            FinancePeriod.YEAR to 12,
            FinancePeriod.ALL to 1,
        )
        expected.forEach { (unit, size) ->
            val stats = insert(unit)
            assertEquals(unit.key + " 桶数", size, stats.buckets.size)
            assertTrue(unit.key + " 无数据", !stats.hasData)
            assertEquals(0.0, stats.totalExpense, 0.001)
            assertTrue(unit.key + " 桶全零", stats.buckets.all { it.expense == 0.0 && it.count == 0 })
        }
    }

    @Test
    fun `五档一次取齐顺序与 unitbar 一致`() {
        val all = runBlocking { manager.getPeriodStatsAll() }
        assertEquals(5, all.size)
        assertEquals(
            listOf("今日", "本周", "本月", "本年", "全部"),
            all.map { it.unitLabel },
        )
        assertEquals(listOf("day", "week", "month", "year", "all"), all.map { it.unitKey })
    }

    @Test
    fun `分桶键与标签口径可单测校验`() {
        assertEquals(24, FinancePeriodRange.labelsOf(FinancePeriod.DAY, "2026-10-15", "2026-10-15").size)
        assertEquals(7, FinancePeriodRange.labelsOf(FinancePeriod.WEEK, "2026-10-12", "2026-10-15").size)
        assertEquals(12, FinancePeriodRange.labelsOf(FinancePeriod.YEAR, "2026-01-01", "2026-10-15").size)
        assertEquals("四", FinancePeriodRange.dateToWeekLabel("2026-10-15"))
        assertEquals("一", FinancePeriodRange.dateToWeekLabel("2026-10-12"))
    }
}
