package com.bsfeng.monitor.ui.quota

import android.content.Context
import com.bsfeng.monitor.domain.quota.QuotaUnit
import java.math.BigDecimal

/**
 * 额度预警偏好 —— 与 `QuotaAlertNotifier` 读写同一份 prefs / 同一组键。
 *
 * 为什么不在 UI 里另起一份：预警的消费方是通知器（阈值命中才发），设置页只是写入口；
 * 各存一份会出现「设置页显示 ¥50、通知按 ¥20 发」的双口径。数据层补齐 setter 后
 * 这个类可以直接删掉，换成 `QuotaAlertNotifier` 的公开写入方法。
 */
internal class QuotaAlertPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var enabled: Boolean
        get() = prefs.getBoolean(KEY_ENABLED, true)
        set(value) = prefs.edit().putBoolean(KEY_ENABLED, value).apply()

    fun lowBalance(unit: QuotaUnit): BigDecimal {
        val fallback = if (unit == QuotaUnit.USD) DEFAULT_USD else DEFAULT_CNY
        val raw = prefs.getString(keyOf(unit), fallback) ?: fallback
        return runCatching { BigDecimal(raw) }.getOrDefault(BigDecimal(fallback))
    }

    fun setLowBalance(unit: QuotaUnit, value: BigDecimal) {
        prefs.edit().putString(keyOf(unit), value.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()).apply()
    }

    private fun keyOf(unit: QuotaUnit): String =
        if (unit == QuotaUnit.USD) KEY_LOW_USD else KEY_LOW_CNY

    private companion object {
        const val PREFS_NAME = "quota_alerts"
        const val KEY_ENABLED = "alerts_enabled"
        const val KEY_LOW_CNY = "low_balance_cny"
        const val KEY_LOW_USD = "low_balance_usd"
        const val DEFAULT_CNY = "50"
        const val DEFAULT_USD = "5"
    }
}
