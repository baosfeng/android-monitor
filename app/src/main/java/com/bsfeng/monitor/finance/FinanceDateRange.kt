package com.bsfeng.monitor.finance

// 来源：原 finance/FinanceDao.kt 的 getNextMonthStart；职责：月份字符串的区间计算（供各 DAO 共用，保证边界一致）。

import java.util.Locale

/** 账目日期区间的纯计算工具 */
internal object FinanceDateRange {

    /** 获取下个月起始日期字符串 yyyy-MM-dd */
    fun nextMonthStart(yearMonth: String): String {
        val parts = yearMonth.split("-")
        val year = parts[0].toInt()
        val month = parts[1].toInt()
        return if (month == 12) "${year + 1}-01-01"
        else String.format(Locale.CHINA, "%04d-%02d-01", year, month + 1)
    }
}
