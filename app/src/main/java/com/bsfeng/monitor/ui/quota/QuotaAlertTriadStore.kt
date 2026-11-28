package com.bsfeng.monitor.ui.quota

import android.content.Context
import androidx.core.content.edit
import com.bsfeng.monitor.domain.quota.QuotaAlertEvaluator
import com.bsfeng.monitor.domain.quota.QuotaInstance
import com.bsfeng.monitor.domain.quota.QuotaInstances
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.quota.QuotaRepository
import org.json.JSONArray
import org.json.JSONObject
import java.math.BigDecimal
import kotlin.math.roundToInt

/**
 * 三态区的数据来源：恢复事件持久化 + 从当前快照实时推导低 / 耗尽。
 *
 * 与 [QuotaAlertPrefs]、[com.bsfeng.monitor.quota.QuotaAlertNotifier] 共用同一份 prefs
 * （`quota_alerts`）：恢复事件只由通知器在评估后写一次，主屏与设置页读同一份，
 * 各存一份会出现「主屏说 22 分钟前恢复、通知却查不到这条」的双口径。
 */
internal data class RecoveryEvent(
    val instanceId: String,
    val itemId: String,
    val itemTitle: String,
    val fromPercent: Int,
    val toPercent: Int,
    val occurredAtEpochSecond: Long,
    val resetsAtEpochSecond: Long? = null,
    /** 余额型恢复后的金额文案（`¥200.00`）；百分比型为 null。 */
    val amountText: String? = null,
)

internal class QuotaAlertTriadStore(
    context: Context,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 最近 [MAX_EVENTS] 条恢复事件，按发生时间倒序。 */
    fun recent(): List<RecoveryEvent> {
        val raw = prefs.getString(KEY_RECOVERIES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val obj = array.optJSONObject(index) ?: return@mapNotNull null
                RecoveryEvent(
                    instanceId = obj.optString("iid"),
                    itemId = obj.optString("item"),
                    itemTitle = obj.optString("title"),
                    fromPercent = obj.optInt("from", 0),
                    toPercent = obj.optInt("to", 0),
                    occurredAtEpochSecond = obj.optLong("at", 0L),
                    resetsAtEpochSecond = if (obj.has("reset")) obj.optLong("reset") else null,
                    amountText = obj.optString("amount").takeIf { it.isNotEmpty() },
                )
            }.sortedByDescending { it.occurredAtEpochSecond }
        }.getOrDefault(emptyList())
    }

    /** 追加一批新的恢复事件；只保留最近 [MAX_EVENTS] 条（旧事件对「本轮恢复」没有意义）。 */
    fun append(recoveries: List<QuotaAlertEvaluator.Recovery>) {
        if (recoveries.isEmpty()) return
        val at = nowSeconds()
        val fresh = recoveries.map { recovery ->
            RecoveryEvent(
                instanceId = recovery.instanceId,
                itemId = recovery.key.substringAfterLast('.'),
                itemTitle = recovery.itemTitle,
                fromPercent = recovery.fromPercent,
                toPercent = recovery.toPercent,
                occurredAtEpochSecond = at,
                resetsAtEpochSecond = recovery.resetsAtEpochSecond,
                amountText = recovery.remainingAmount?.let { amount ->
                    recovery.unit?.let { QuotaFormat.amount(amount, it) }
                },
            )
        }
        val merged = (fresh + recent()).take(MAX_EVENTS)
        val array = JSONArray()
        merged.forEach { event ->
            array.put(
                JSONObject().apply {
                    put("iid", event.instanceId)
                    put("item", event.itemId)
                    put("title", event.itemTitle)
                    put("from", event.fromPercent)
                    put("to", event.toPercent)
                    put("at", event.occurredAtEpochSecond)
                    event.resetsAtEpochSecond?.let { put("reset", it) }
                    event.amountText?.let { put("amount", it) }
                },
            )
        }
        prefs.edit { putString(KEY_RECOVERIES, array.toString()) }
    }

    private companion object {
        /** 与 [QuotaAlertPrefs] / 通知器同一份 prefs（键不同，文件相同）。 */
        const val PREFS_NAME = "quota_alerts"
        const val KEY_RECOVERIES = "recoveries"

        /** 主屏最多回溯 12 条：够覆盖「本轮恢复」且不会让 prefs 无限长。 */
        const val MAX_EVENTS = 12
    }
}

/** 主屏「快见底」阈值：与产品口径一致（剩余 ≤20% 转黄，0% 转红）。 */
internal const val QUOTA_TRIAD_LOW_FRACTION: Float = 0.20f

/** 每种三态最多展示的行数：主屏是汇总入口，全量明细在账号卡里。 */
private const val MAX_ROWS_PER_KIND = 2

/**
 * 从快照 + 恢复事件推导三态区（纯函数，可 JVM 单测）。
 *
 * @param providerNameOf 类型 id → 展示名（`Ollama`）；账号名由实例 label 拼在后面
 */
internal fun buildAlertTriad(
    states: Map<String, QuotaRepository.ProviderState>,
    instances: List<QuotaInstance>,
    recoveries: List<RecoveryEvent>,
    lowBalanceThresholds: Map<QuotaUnit, BigDecimal>,
    providerNameOf: (String) -> String,
    nowEpochSecond: Long,
): QuotaAlertTriad {
    val multiByType = instances.groupingBy { it.providerTypeId }.eachCount()
    val typeOf = instances.associate { it.instanceId to it.providerTypeId }

    fun displayNameOf(instanceId: String): String {
        val typeId = typeOf[instanceId] ?: return instanceId
        val type = providerNameOf(typeId)
        val instance = instances.firstOrNull { it.instanceId == instanceId }
        val label = instance?.label?.trim().orEmpty()
        return when {
            label.isNotEmpty() -> type + " " + label
            (multiByType[typeId] ?: 0) > 1 -> type + " 账号 " + (instance?.seq ?: 1)
            else -> type
        }
    }

    // 1) 已恢复：同一条额度只留最近一次；当前又跌破阈值时丢弃（否则与「快见底」自相矛盾）
    val recovered = recoveries
        .sortedByDescending { it.occurredAtEpochSecond }
        .distinctBy { it.instanceId to it.itemId }
        .filter { event ->
            val item = states[event.instanceId]?.items?.firstOrNull { it.id == event.itemId }
            item == null || !isBelowAlert(item, lowBalanceThresholds)
        }
        .take(MAX_ROWS_PER_KIND)
        .map { event ->
            val amountText = event.amountText
            QuotaAlertTriadRow(
                kind = QuotaAlertTriadKind.RECOVERED,
                instanceId = event.instanceId,
                displayName = displayNameOf(event.instanceId),
                itemTitle = event.itemTitle,
                fraction = event.toPercent / 100f,
                fractionText = amountText ?: event.toPercent.toString() + "%",
                detailText = recoverDetailText(event),
                occurredAtEpochSecond = event.occurredAtEpochSecond,
                recoveredFrom = event.fromPercent / 100f,
            )
        }

    // 2) 快见底 / 已耗尽：从当前快照推导；按实例顺序遍历，行序稳定（不会每次刷新换位置）
    val low = mutableListOf<QuotaAlertTriadRow>()
    val dead = mutableListOf<QuotaAlertTriadRow>()
    instances.forEach { instance ->
        val state = states[instance.instanceId] ?: return@forEach
        state.items.forEach { item ->
            val row = liveTriadRow(
                item = item,
                instanceId = instance.instanceId,
                displayName = displayNameOf(instance.instanceId),
                lowBalanceThresholds = lowBalanceThresholds,
                nowEpochSecond = nowEpochSecond,
                fetchedAtEpochSecond = state.fetchedAtEpochSecond,
            ) ?: return@forEach
            if (row.kind == QuotaAlertTriadKind.DEAD) dead += row else low += row
        }
    }
    return QuotaAlertTriad(
        rows = recovered +
            low.sortedBy { it.fraction }.take(MAX_ROWS_PER_KIND) +
            dead.sortedBy { it.fraction }.take(MAX_ROWS_PER_KIND),
    )
}

/** 该项当前是否已低于提醒阈值（恢复行与实时行互斥的判据）。 */
private fun isBelowAlert(item: QuotaItem, lowBalanceThresholds: Map<QuotaUnit, BigDecimal>): Boolean {
    if (item.isPlainBalance) {
        val limit = lowBalanceThresholds[item.unit] ?: return false
        return item.remaining <= limit
    }
    val fraction = item.remainingFraction ?: return false
    return fraction <= QUOTA_TRIAD_LOW_FRACTION
}

/** 一条实时行；不在三态里（或数据不足）时返回 null。 */
private fun liveTriadRow(
    item: QuotaItem,
    instanceId: String,
    displayName: String,
    lowBalanceThresholds: Map<QuotaUnit, BigDecimal>,
    nowEpochSecond: Long,
    fetchedAtEpochSecond: Long,
): QuotaAlertTriadRow? {
    if (item.isPlainBalance) {
        val limit = lowBalanceThresholds[item.unit] ?: return null
        if (item.remaining > limit) return null
        val exhausted = item.remaining.signum() <= 0
        return QuotaAlertTriadRow(
            kind = if (exhausted) QuotaAlertTriadKind.DEAD else QuotaAlertTriadKind.LOW,
            instanceId = instanceId,
            displayName = displayName,
            itemTitle = item.title,
            fraction = 0f,
            fractionText = QuotaFormat.amount(item.remaining, item.unit),
            detailText = if (exhausted) {
                "余额耗尽 · 充值后恢复"
            } else {
                "低于阈值 " + QuotaFormat.amount(limit, item.unit) + " · 充值后重新计数"
            },
            occurredAtEpochSecond = fetchedAtEpochSecond,
        )
    }
    val fraction = item.remainingFraction ?: return null
    if (fraction > QUOTA_TRIAD_LOW_FRACTION) return null
    val dead = fraction <= 0f
    val reset = item.resetsAtEpochSecond ?: item.period?.endEpochSecond
    val resetText = alertResetShort(reset, nowEpochSecond)
    val detail = when {
        dead && resetText != null -> resetText + " 自动恢复 · 期间可切主号"
        dead -> "等周期重置 · 期间可切主号"
        else -> {
            val eta = exhaustEtaText(item, nowEpochSecond)
            listOfNotNull(resetText?.let { it + " 重置" }, eta?.let { "按当前速率" + it })
                .joinToString(" · ")
        }
    }
    return QuotaAlertTriadRow(
        kind = if (dead) QuotaAlertTriadKind.DEAD else QuotaAlertTriadKind.LOW,
        instanceId = instanceId,
        displayName = displayName,
        itemTitle = item.title,
        fraction = fraction,
        fractionText = (fraction * 100f).roundToInt().toString() + "%",
        detailText = detail,
        occurredAtEpochSecond = fetchedAtEpochSecond,
    )
}

/** 恢复行副行：`4% → 96% · 周一 00:00 重置 · 已发「已恢复」`。 */
private fun recoverDetailText(event: RecoveryEvent): String {
    val change = if (event.amountText != null) {
        "余额回升到 " + event.amountText
    } else {
        event.fromPercent.toString() + "% → " + event.toPercent.toString() + "%"
    }
    val reset = alertResetShort(event.resetsAtEpochSecond, event.occurredAtEpochSecond)
    return listOfNotNull(change, reset?.let { it + " 重置" }, "已发「已恢复」").joinToString(" · ")
}

/**
 * 按当前速率估算耗尽时间（原型「按当前速率约 4 天耗尽」）。
 *
 * 返回 null 的三种情况：没有周期、周期刚开始（样本不足）、按当前速率撑得到重置
 * （那时重置本身就是恢复，不该预告耗尽）。
 */
internal fun exhaustEtaText(item: QuotaItem, nowEpochSecond: Long): String? {
    val period = item.period ?: return null
    val fraction = item.remainingFraction ?: return null
    val elapsed = nowEpochSecond - period.startEpochSecond
    if (elapsed <= 0L) return null
    val used = 1f - fraction
    if (used <= 0.001f) return null
    val etaSeconds = (fraction.toDouble() / (used.toDouble() / elapsed.toDouble())).toLong()
    val left = period.endEpochSecond - nowEpochSecond
    if (left <= 0L || etaSeconds >= left) return null
    val days = etaSeconds / 86_400
    val hours = etaSeconds / 3_600
    return when {
        days >= 1 -> "约 " + days + " 天耗尽"
        hours >= 1 -> "约 " + hours + " 小时耗尽"
        else -> "约 " + (etaSeconds / 60).coerceAtLeast(1L) + " 分钟耗尽"
    }
}
