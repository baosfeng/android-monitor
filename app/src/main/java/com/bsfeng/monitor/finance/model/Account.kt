package com.bsfeng.monitor.finance.model

/**
 * 账户（支付方式）。
 *
 * @param type 0=现金, 1=银行卡, 2=第三方支付
 */
data class Account(
    val id: Long = 0,
    val name: String = "",
    val type: Int = 2,
    val balance: Double = 0.0,
    val sortOrder: Int = 0
) {
    val isCash: Boolean get() = type == 0
    val isBankCard: Boolean get() = type == 1
    val isThirdParty: Boolean get() = type == 2
    val displayBalance: String get() = String.format("%.2f", balance)

    companion object {
        /** 熊猫记账导入时默认创建的账户 */
        val defaultAccounts = listOf(
            Account(name = "现金钱包", type = 0),
            Account(name = "支付宝", type = 2),
            Account(name = "微信钱包", type = 2)
        )
    }
}
