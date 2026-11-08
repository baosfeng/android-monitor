package com.bsfeng.monitor.finance

// 来源：原 finance/FinanceManager.kt 的交易余额副作用；职责：把一笔交易按签名作用于账户余额（含转账双边）。

import com.bsfeng.monitor.finance.model.Transaction

/**
 * 交易 → 账户余额的副作用计算。
 *
 * 拆分自 [FinanceManager]，计算顺序与原实现一致：先按旧值回冲、再按新值计入。
 */
internal class FinanceAccountBalance(private val dao: FinanceDao) {

    /** 交易对账户余额的影响（收入为正、支出为负；退款交易 amount 本身为负，故回冲为正）。 */
    fun balanceEffectOf(tx: Transaction): Double = if (tx.isIncome) tx.amount else -tx.amount

    /**
     * 把一笔交易对余额的影响作用于账户（[sign] = 1 计入、-1 回冲）。
     *
     * 转账（[Transaction.isTransfer]）是**两个账户的一进一出**：转出账户扣、转入账户加。
     * 转入账户缺失（老记录 / 用户未选）时只动转出侧，不让差额凭空落到别的账户。
     */
    fun applyTransactionEffect(tx: Transaction, sign: Double) {
        if (tx.isTransfer) {
            applyBalanceDelta(tx.accountName, -tx.amount * sign)
            applyBalanceDelta(tx.toAccountName, tx.amount * sign)
            return
        }
        applyBalanceDelta(tx.accountName, balanceEffectOf(tx) * sign)
    }

    /**
     * 账户余额随交易增减（走 SQL 相对增量，见 [FinanceDao.addToAccountBalance]）。
     *
     * 口径：种子账户从 0 起算，导入账户以熊猫记账 t_card.money 为快照起点，
     * 之后由本 App 记录的交易按签名金额增减；账户名对不上（空串 / 账户已删）时跳过。
     *
     * 本方法是**同步阻塞**方法，因为调用方必须把它和「写交易」放在同一个数据库事务里
     * （[FinanceManager] 的写入口负责事务边界）；挂在 suspend 上会诱导调用方在事务外单独 await 它，
     * 那样一次记账就退化成「先提交交易、再提交余额」两步。
     */
    private fun applyBalanceDelta(accountName: String, delta: Double) {
        dao.addToAccountBalance(accountName, delta)
    }
}
