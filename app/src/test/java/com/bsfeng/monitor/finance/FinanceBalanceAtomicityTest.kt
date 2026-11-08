package com.bsfeng.monitor.finance

import android.content.Context
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Transaction
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 账户余额的原子性（Robolectric + 真实 SQLite）。
 *
 * 钉住两件事：
 * 1. **相对增量**：余额更新走 `UPDATE … SET balance = balance + ?`，写入者拿到的旧快照
 *    不影响结果（旧实现「读整行 → 内存相加 → 整行写回」在两个写者之间必丢一次增量）；
 * 2. **同一事务**：交易写库与余额变更在同一个事务里，任何一步失败整体回滚。
 *
 * 自动记账与手动记账并发正是 P1 的现场：前者的落库回调在 IO scope，后者在记账页协程。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class FinanceBalanceAtomicityTest {

    private lateinit var context: Context
    private lateinit var manager: FinanceManager

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        FinanceDatabaseHelper.resetForTesting()
        context.deleteDatabase(FinanceDatabaseHelper.DATABASE_NAME)
        manager = FinanceManager(context)
        manager.dao.insertAccount(Account(name = "支付宝", type = Account.TYPE_THIRD_PARTY, balance = 1000.0))
    }

    @After
    fun tearDown() {
        FinanceDatabaseHelper.resetForTesting()
        context.deleteDatabase(FinanceDatabaseHelper.DATABASE_NAME)
    }

    private fun expense(amount: Double) = Transaction(
        type = 0,
        categoryName = "餐饮",
        amount = amount,
        accountName = "支付宝",
        date = "2026-11-08",
        dateTime = "2026-11-08 12:00",
    )

    private fun balance(): Double = manager.dao.getAccountByName("支付宝")!!.balance

    @Test
    fun `并发两笔支出各 100 元后余额合计扣 200`() = runBlocking {
        // 两个写者同时进入：先各自等在同一个起跑线上，再一起写同一账户
        val ready = CountDownLatch(2)
        val start = CountDownLatch(1)
        val finished = CountDownLatch(2)
        val failures = java.util.Collections.synchronizedList(mutableListOf<Throwable>())
        repeat(2) {
            thread(name = "finance-writer-$it") {
                try {
                    ready.countDown()
                    assertTrue("写者应能在 5 秒内起跑", start.await(5, TimeUnit.SECONDS))
                    runBlocking { manager.addTransaction(expense(100.0)) }
                } catch (t: Throwable) {
                    failures += t
                } finally {
                    finished.countDown()
                }
            }
        }
        assertTrue("两个写者都就绪", ready.await(5, TimeUnit.SECONDS))
        start.countDown()
        assertTrue("两个写者都应结束", finished.await(30, TimeUnit.SECONDS))
        assertTrue("写者不得抛异常：" + failures, failures.isEmpty())

        assertEquals("1000 - 100 - 100，两笔都不能丢", 800.0, balance(), 0.0001)
        assertEquals("账目也必须两笔都在", 2, manager.dao.getRecentTransactions(10).size)
    }

    @Test
    fun `写交易与改余额在同一个事务里失败则整体回滚`() = runBlocking {
        val before = balance()
        val boom = IllegalStateException("余额更新后失败")

        val thrown = runCatching {
            manager.dao.runInTransaction {
                manager.insertTransactionWithEffect(expense(100.0))
                throw boom
            }
        }.exceptionOrNull()

        assertEquals(boom, thrown)
        assertEquals("交易行回滚", 0, manager.dao.getRecentTransactions(10).size)
        assertEquals("余额回滚", before, balance(), 0.0001)
    }

    @Test
    fun `账户不存在时交易照写而余额不动`() = runBlocking {
        val id = manager.addTransaction(expense(100.0).copy(accountName = "已删除的卡"))

        assertTrue("账户名对不上不该阻止记账", id > 0)
        assertEquals("但一分钱也不该动到别的账户", 1000.0, balance(), 0.0001)
        assertNull("不存在的账户不会被顺手创建", manager.dao.getAccountByName("已删除的卡"))
        assertEquals(1, manager.dao.getRecentTransactions(10).size)
    }
}
