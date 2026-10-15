package com.bsfeng.monitor.finance

import android.content.Context
import com.bsfeng.monitor.finance.model.Account
import com.bsfeng.monitor.finance.model.Transaction
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [FinanceManager] 交易 ↔ 账户余额联动（Robolectric，真实 SQLite）。
 *
 * 口径（见 FinanceManager 的 balanceEffectOf / applyBalanceDelta）：
 * 收入按金额加上、支出按金额扣减；负数支出（退款抵扣）反向加上；
 * 删 / 改按签名金额回冲或补差；换账户时旧账户回冲、新账户计入；
 * 账户名为空或账户不存在时跳过联动。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceAccountBalanceTest {

    private lateinit var context: Context
    private lateinit var manager: FinanceManager

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

    private fun seedAccount(name: String, balance: Double) {
        manager.dao.insertAccount(Account(name = name, type = Account.TYPE_THIRD_PARTY, balance = balance))
    }

    private suspend fun balanceOf(name: String): Double = manager.getAccountByName(name)!!.balance

    private fun tx(amount: Double, type: Int = 0, account: String = "支付宝") = Transaction(
        type = type,
        categoryName = if (type == 1) "薪资" else "餐饮",
        amount = amount,
        accountName = account,
        date = "2026-01-05",
        dateTime = "2026-01-05 12:00",
    )

    @Test
    fun `新增支出后账户余额按金额扣减`() = runBlocking {
        seedAccount("支付宝", 100.0)

        manager.addTransaction(tx(amount = 30.0))

        assertEquals(70.0, balanceOf("支付宝"), 0.0001)
    }

    @Test
    fun `新增收入后账户余额按金额增加`() = runBlocking {
        seedAccount("支付宝", 100.0)

        manager.addTransaction(tx(amount = 8800.0, type = 1))

        assertEquals(8900.0, balanceOf("支付宝"), 0.0001)
    }

    @Test
    fun `删除交易后余额按签名金额回冲`() = runBlocking {
        seedAccount("支付宝", 100.0)
        val id = manager.addTransaction(tx(amount = 30.0))
        assertEquals(70.0, balanceOf("支付宝"), 0.0001)

        manager.deleteTransaction(id)

        assertEquals("删支出后应回到原余额", 100.0, balanceOf("支付宝"), 0.0001)
    }

    @Test
    fun `同账户改金额后只按差额补正不重复扣减`() = runBlocking {
        seedAccount("支付宝", 100.0)
        val id = manager.addTransaction(tx(amount = 30.0))

        manager.updateTransaction(manager.getTransactionById(id)!!.copy(amount = 50.0))

        assertEquals("应为 100-50，而不是 100-30-50", 50.0, balanceOf("支付宝"), 0.0001)
    }

    @Test
    fun `换账户时旧账户回冲新账户计入`() = runBlocking {
        seedAccount("支付宝", 100.0)
        seedAccount("现金钱包", 50.0)
        val id = manager.addTransaction(tx(amount = 30.0, account = "支付宝"))
        assertEquals(70.0, balanceOf("支付宝"), 0.0001)

        manager.updateTransaction(manager.getTransactionById(id)!!.copy(accountName = "现金钱包"))

        assertEquals("旧账户回冲", 100.0, balanceOf("支付宝"), 0.0001)
        assertEquals("新账户计入", 20.0, balanceOf("现金钱包"), 0.0001)
    }

    @Test
    fun `负数支出（退款抵扣）反向计入余额`() = runBlocking {
        seedAccount("支付宝", 100.0)

        manager.addTransaction(tx(amount = -30.0))

        assertEquals("退回的 30 应加回余额", 130.0, balanceOf("支付宝"), 0.0001)
    }

    @Test
    fun `账户名为空或账户不存在时跳过联动`() = runBlocking {
        seedAccount("支付宝", 100.0)

        manager.addTransaction(tx(amount = 30.0, account = ""))
        manager.addTransaction(tx(amount = 30.0, account = "已删除的卡"))

        assertEquals("账户名对不上不得改动任何余额", 100.0, balanceOf("支付宝"), 0.0001)
    }

    @Test
    fun `非收入类型（转账退款）按支出方向计入余额`() = runBlocking {
        seedAccount("支付宝", 100.0)

        manager.addTransaction(tx(amount = 50.0, type = 2))
        manager.addTransaction(tx(amount = 20.0, type = 3))

        assertEquals("只有收入类型加余额，其余按金额扣减", 30.0, balanceOf("支付宝"), 0.0001)
    }
}
