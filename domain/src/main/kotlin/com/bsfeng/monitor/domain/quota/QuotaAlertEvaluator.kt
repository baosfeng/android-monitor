package com.bsfeng.monitor.domain.quota

import java.math.BigDecimal

/**
 * 额度阈值提醒 —— 纯逻辑，可在 JVM 单测里直接断言。
 *
 * 规则：
 * - 百分比类额度（含「金额 + 总额」）按剩余比例走 5 / 4 / 3 / 2 / 1 五档，一次跨多档只命中最低档；
 * - 账户余额类（金额且无总额，如 DeepSeek）没有比例可言，走「低于设定金额提醒一次」；
 * - 同一额度同一周期同一档只提醒一次；周期变化或余额回升后清零，下次下降可再次提醒。
 */
object QuotaAlertEvaluator {

    /** 阈值（降序，单位 %）。 */
    val THRESHOLDS: List<Int> = listOf(5, 4, 3, 2, 1)

    /**
     * 当前剩余比例命中的阈值；5% 以上返回 null。
     *
     * 用 Double 计算百分比并留 1e-4 容差：Float 下 3.0/100*100 会漂成 3.0000005，
     * 卡在档位边界时会把「刚好 3%」误判成没跨过 3 档。
     */
    fun reachedThreshold(remainingFraction: Float): Int? {
        if (remainingFraction < 0f) return null
        val percent = remainingFraction.toDouble() * 100.0
        return THRESHOLDS.lastOrNull { it >= percent - 1e-4 }
    }

    /** 单个额度的提醒状态（持久化到偏好）。 */
    data class AlertState(
        /** 周期标识（重置时间 epoch 秒；null = 服务端没给周期）。 */
        val cycleId: Long? = null,
        /** 已提醒过的百分比阈值（0 = 未提醒）。 */
        val notifiedThreshold: Int = 0,
        /** 余额型额度的低余额提醒是否已发过。 */
        val lowBalanceNotified: Boolean = false,
    )

    /** 一条待发送的提醒。 */
    data class Alert(
        /** 状态键：`providerId.itemId`。 */
        val key: String,
        val providerId: String,
        val itemTitle: String,
        /** 命中的百分比阈值（5..1）；余额型低余额提醒为 [LOW_BALANCE_THRESHOLD]。 */
        val threshold: Int,
        /** 实际剩余百分比（向下取整；余额型为 0）。 */
        val remainingPercent: Int,
        /** 余额型提醒时的剩余金额。 */
        val remainingAmount: BigDecimal? = null,
        val resetsAtEpochSecond: Long? = null,
    ) {
        /** 是否「即将用尽」档。 */
        val isCritical: Boolean get() = threshold <= 1 || threshold == LOW_BALANCE_THRESHOLD

        /** 通知标题：`Ollama · 会话额度即将用尽`。 */
        fun title(providerName: String): String = when (threshold) {
            LOW_BALANCE_THRESHOLD -> "$providerName · 余额不足"
            else -> "$providerName · ${itemTitle}额度${if (isCritical) "即将用尽" else "偏低"}"
        }

        /** 通知正文：`剩余 3%，10/19 08:00 重置` / `余额 ¥42.00`。 */
        fun bodyText(unit: QuotaUnit): String = when (threshold) {
            LOW_BALANCE_THRESHOLD -> "余额 ${unit.symbol}${remainingAmount?.stripTrailingZeros()?.toPlainString().orEmpty()}"
            else -> if (resetsAtEpochSecond != null) "剩余 $remainingPercent%，${resetText()}" else "剩余 $remainingPercent%"
        }

        private fun resetText(): String {
            val ts = resetsAtEpochSecond ?: return ""
            val date = java.time.Instant.ofEpochSecond(ts)
                .atZone(java.time.ZoneId.systemDefault())
            return "${date.monthValue}/${date.dayOfMonth} ${"%02d:%02d".format(date.hour, date.minute)} 重置"
        }
    }

    /** 余额型低余额提醒的伪阈值。 */
    const val LOW_BALANCE_THRESHOLD: Int = -1

    /** 一次评估的结果。 */
    data class Result(
        val alerts: List<Alert>,
        val state: Map<String, AlertState>,
    )

    /**
     * 评估一次快照。
     *
     * @param lowBalanceThresholds 各币种的低余额阈值（只对「金额且无总额」的额度生效）
     */
    fun evaluate(
        providerId: String,
        items: List<QuotaItem>,
        state: Map<String, AlertState>,
        lowBalanceThresholds: Map<QuotaUnit, BigDecimal> = emptyMap(),
    ): Result {
        val alerts = mutableListOf<Alert>()
        val updated = state.toMutableMap()

        for (item in items) {
            val key = "$providerId.${item.id}"
            val previous = state[key]
            val cycleId = item.resetsAtEpochSecond ?: item.period?.endEpochSecond
            var current = AlertState(cycleId = cycleId)
            if (previous != null && previous.cycleId == cycleId) {
                current = current.copy(
                    notifiedThreshold = previous.notifiedThreshold,
                    lowBalanceNotified = previous.lowBalanceNotified,
                )
            }

            if (item.isPlainBalance) {
                val limit = lowBalanceThresholds[item.unit]
                if (limit == null || item.remaining > limit) {
                    // 没有阈值或余额充足：复位，等下次跌破再提醒
                    if (current.lowBalanceNotified) {
                        current = current.copy(lowBalanceNotified = false)
                        updated[key] = current
                    }
                    continue
                }
                if (!current.lowBalanceNotified) {
                    alerts += Alert(
                        key = key,
                        providerId = providerId,
                        itemTitle = item.title,
                        threshold = LOW_BALANCE_THRESHOLD,
                        remainingPercent = 0,
                        remainingAmount = item.remaining,
                    )
                    current = current.copy(lowBalanceNotified = true)
                    updated[key] = current
                }
                continue
            }

            val fraction = item.remainingFraction ?: continue
            val threshold = reachedThreshold(fraction)
            if (threshold == null) {
                // 回升到 5% 以上：清零，下次下降可再次提醒
                if (current.notifiedThreshold != 0) {
                    updated[key] = current.copy(notifiedThreshold = 0)
                }
                continue
            }
            if (current.notifiedThreshold == 0 || threshold < current.notifiedThreshold) {
                alerts += Alert(
                    key = key,
                    providerId = providerId,
                    itemTitle = item.title,
                    threshold = threshold,
                    remainingPercent = (fraction * 100f).toInt(),
                    resetsAtEpochSecond = item.resetsAtEpochSecond,
                )
                updated[key] = current.copy(notifiedThreshold = threshold)
            } else {
                updated[key] = current
            }
        }
        return Result(alerts, updated)
    }
}
