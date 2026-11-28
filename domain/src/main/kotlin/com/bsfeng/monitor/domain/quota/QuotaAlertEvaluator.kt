package com.bsfeng.monitor.domain.quota

import java.math.BigDecimal

/**
 * 预警状态键：`instanceId.itemId`。
 *
 * 用实例键而不是类型 id：同一 provider 挂两个账号时，两条同 id 的额度项若共用一个状态键，
 * 后评估的账号会把前一个的「已提醒档位」写掉，表现是「另一个账号永远不提醒」。
 */
fun alertStateKey(instanceId: String, itemId: String): String = "$instanceId.$itemId"

/**
 * 额度阈值提醒 —— 纯逻辑，可在 JVM 单测里直接断言。
 *
 * 规则：
 * - 百分比类额度（含「金额 + 总额」）按剩余比例走 5 / 4 / 3 / 2 / 1 五档，一次跨多档只命中最低档；
 * - 账户余额类（金额且无总额，如 DeepSeek）没有比例可言，走「低于设定金额提醒一次」；
 * - 同一额度同一周期同一档只提醒一次；周期变化或余额回升后清零，下次下降可再次提醒。
 * - 同一周期内「提醒过」之后回升到阈值以上 → 产出一条 [Recovery]（`fromPercent` 是上次提醒的档位）；
 *   周期切换只静默清零，不报恢复 —— 新周期天然 100%，报「已恢复」是噪音。
 * - 状态键按**实例**隔离，同类型多账号各记各的。
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
        /** 状态键：`instanceId.itemId`（实例键，同类型多账号互不覆盖）。 */
        val key: String,
        /** 实例键；通知 ID 由它稳定派生，标题用它对应的账号名。 */
        val instanceId: String,
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

    /**
     * 一条「已恢复」事件：同一周期内从提醒过的低位回升到阈值以上。
     *
     * 只由「上次真的提醒过」的状态派生，所以同一次回升只产生一条；周期切换不产生
     * （新周期天然 100%，报「已恢复」是噪音，见 [evaluate]）。
     */
    data class Recovery(
        /** 状态键：`instanceId.itemId`。 */
        val key: String,
        val instanceId: String,
        val itemTitle: String,
        /** 恢复前提醒过的档位（5..1）；余额型为 0。不是精确百分比。 */
        val fromPercent: Int,
        /** 本次回升后的剩余百分比（向下取整）；余额型为 0。 */
        val toPercent: Int,
        /** 余额型恢复时的当前金额。 */
        val remainingAmount: BigDecimal? = null,
        /** 余额型恢复时的币种（金额展示要用；百分比型为 null）。 */
        val unit: QuotaUnit? = null,
        val resetsAtEpochSecond: Long? = null,
    )

    /** 一次评估的结果。 */
    data class Result(
        val alerts: List<Alert>,
        val state: Map<String, AlertState>,
        /** 本周期内新产生的「已恢复」事件；与 [alerts] 互斥（不会同一项既降档又恢复）。 */
        val recoveries: List<Recovery> = emptyList(),
    )

    /**
     * 评估一次快照。
     *
     * @param lowBalanceThresholds 各币种的低余额阈值（只对「金额且无总额」的额度生效）
     */
    fun evaluate(
        instanceId: String,
        items: List<QuotaItem>,
        state: Map<String, AlertState>,
        lowBalanceThresholds: Map<QuotaUnit, BigDecimal> = emptyMap(),
    ): Result {
        val alerts = mutableListOf<Alert>()
        val recoveries = mutableListOf<Recovery>()
        val updated = state.toMutableMap()

        for (item in items) {
            val key = alertStateKey(instanceId, item.id)
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
                    // 余额回升到阈值以上：先记一条「已恢复」（仅当本周期真的提醒过），再复位
                    if (current.lowBalanceNotified) {
                        recoveries += Recovery(
                            key = key,
                            instanceId = instanceId,
                            itemTitle = item.title,
                            fromPercent = 0,
                            toPercent = 0,
                            remainingAmount = item.remaining,
                            unit = item.unit,
                        )
                        current = current.copy(lowBalanceNotified = false)
                        updated[key] = current
                    }
                    continue
                }
                if (!current.lowBalanceNotified) {
                    alerts += Alert(
                        key = key,
                        instanceId = instanceId,
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
                // 回升到 5% 以上：本周期内提醒过才是一条「已恢复」，然后清零、下次下降可再提醒。
                // 周期切换时 current 是全新状态（notifiedThreshold = 0），所以不会误报。
                if (current.notifiedThreshold != 0) {
                    recoveries += Recovery(
                        key = key,
                        instanceId = instanceId,
                        itemTitle = item.title,
                        fromPercent = current.notifiedThreshold,
                        toPercent = (fraction * 100f).toInt().coerceIn(0, 100),
                        resetsAtEpochSecond = item.resetsAtEpochSecond,
                    )
                    updated[key] = current.copy(notifiedThreshold = 0)
                }
                continue
            }
            if (current.notifiedThreshold == 0 || threshold < current.notifiedThreshold) {
                alerts += Alert(
                    key = key,
                    instanceId = instanceId,
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
        return Result(alerts, updated, recoveries)
    }
}
