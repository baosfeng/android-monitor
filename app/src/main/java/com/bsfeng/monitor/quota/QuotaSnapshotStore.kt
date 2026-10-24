package com.bsfeng.monitor.quota

import android.content.Context
import com.bsfeng.monitor.domain.quota.QuotaBucket
import com.bsfeng.monitor.domain.quota.QuotaGranularity
import com.bsfeng.monitor.domain.quota.QuotaGroup
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaMetrics
import com.bsfeng.monitor.domain.quota.QuotaPeriod
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.domain.quota.QuotaSnapshot
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.domain.quota.QuotaUsage
import java.math.BigDecimal
import org.json.JSONArray
import org.json.JSONObject

/**
 * 上次成功快照的本地缓存 —— 冷启动先进页面时先回显它，再后台刷新。
 *
 * 为什么要它：首次进额度页会发起请求，网络慢的那一两秒里如果只有内存状态，
 * 用户看到的是整页骨架屏；把上一次的结果落盘，进页面立刻有内容，刷新只是「数字在动」。
 *
 * 只存**成功**的结果（失败时保留旧快照，不覆盖），并且只存最近一次，不做历史。
 */
interface QuotaSnapshotCache {
    fun load(providerId: String): QuotaSnapshot?
    fun save(snapshot: QuotaSnapshot)
    fun clear()
}

/** 无缓存实现（单测 / 未装配时用）。 */
object NoQuotaSnapshotCache : QuotaSnapshotCache {
    override fun load(providerId: String): QuotaSnapshot? = null
    override fun save(snapshot: QuotaSnapshot) = Unit
    override fun clear() = Unit
}

/** SharedPreferences 实现：一个 provider 一个键，值是该快照的 JSON。 */
class QuotaSnapshotStore(context: Context) : QuotaSnapshotCache {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun load(providerId: String): QuotaSnapshot? {
        val raw = prefs.getString(KEY_PREFIX + providerId, null) ?: return null
        return runCatching { QuotaSnapshotJson.decode(raw) }.getOrNull()
    }

    override fun save(snapshot: QuotaSnapshot) {
        if (snapshot.items.isEmpty() && snapshot.usage == null) return
        prefs.edit()
            .putString(KEY_PREFIX + snapshot.providerId, QuotaSnapshotJson.encode(snapshot))
            .apply()
    }

    override fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "quota_snapshots"
        const val KEY_PREFIX = "snapshot:"
    }
}

/**
 * 快照 JSON 编解码。
 *
 * 用系统 `org.json`（项目规范），字段名与领域模型一一对应；
 * 解码遇到坏数据整体返回 null，让调用方回落到「没有缓存」，不半渲染一张残缺卡片。
 */
object QuotaSnapshotJson {

    fun encode(snapshot: QuotaSnapshot): String = JSONObject().apply {
        put("providerId", snapshot.providerId)
        put("fetchedAt", snapshot.fetchedAtEpochSecond)
        put("items", JSONArray().apply {
            snapshot.items.forEach { put(itemToJson(it)) }
        })
        snapshot.usage?.let { put("usage", usageToJson(it)) }
    }.toString()

    fun decode(raw: String): QuotaSnapshot? = runCatching {
        val root = JSONObject(raw)
        val providerId = root.getString("providerId")
        val items = root.optJSONArray("items")?.let { array ->
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let(::itemFromJson)
            }
        }.orEmpty()
        val usage = root.optJSONObject("usage")?.let(::usageFromJson)
        QuotaSnapshot(
            providerId = providerId,
            items = items,
            usage = usage,
            fetchedAtEpochSecond = root.optLong("fetchedAt", 0L),
        )
    }.getOrNull()

    // MARK: - 额度项

    private fun itemToJson(item: QuotaItem): JSONObject = JSONObject().apply {
        put("id", item.id)
        put("title", item.title)
        put("kind", item.kind.name)
        put("remaining", item.remaining.toPlainString())
        item.total?.let { put("total", it.toPlainString()) }
        put("unit", item.unit.name)
        item.period?.let { period ->
            put("period", JSONObject().put("start", period.startEpochSecond).put("end", period.endEpochSecond))
        }
        item.resetsAtEpochSecond?.let { put("resetsAt", it) }
        put("primary", item.primary)
        item.detailText?.let { put("detail", it) }
        item.badge?.let { put("badge", it) }
    }

    private fun itemFromJson(json: JSONObject): QuotaItem? = runCatching {
        QuotaItem(
            id = json.getString("id"),
            title = json.getString("title"),
            kind = QuotaKind.valueOf(json.getString("kind")),
            remaining = BigDecimal(json.getString("remaining")),
            total = json.optString("total").takeIf { it.isNotEmpty() }?.let(::BigDecimal),
            unit = QuotaUnit.valueOf(json.getString("unit")),
            period = json.optJSONObject("period")?.let { QuotaPeriod(it.getLong("start"), it.getLong("end")) },
            resetsAtEpochSecond = if (json.has("resetsAt")) json.getLong("resetsAt") else null,
            primary = json.optBoolean("primary", false),
            detailText = json.optString("detail").takeIf { it.isNotEmpty() },
            badge = json.optString("badge").takeIf { it.isNotEmpty() },
        )
    }.getOrNull()

    // MARK: - 用量

    private fun usageToJson(usage: QuotaUsage): JSONObject = JSONObject().apply {
        put("range", usage.range.name)
        put("granularity", usage.granularity.name)
        put("start", usage.startEpochSecond)
        put("end", usage.endEpochSecond)
        put("totals", metricsToJson(usage.totals))
        usage.costUnit?.let { put("costUnit", it.name) }
        put("buckets", JSONArray().apply {
            usage.buckets.forEach { bucket ->
                put(
                    JSONObject().apply {
                        put("start", bucket.startEpochSecond)
                        put("end", bucket.endEpochSecond)
                        put("partial", bucket.partial)
                        put("metrics", metricsToJson(bucket.metrics))
                    },
                )
            }
        })
        put("groups", groupsToJson(usage.groups))
    }

    private fun usageFromJson(json: JSONObject): QuotaUsage? = runCatching {
        QuotaUsage(
            range = QuotaRange.valueOf(json.getString("range")),
            granularity = QuotaGranularity.valueOf(json.getString("granularity")),
            startEpochSecond = json.getLong("start"),
            endEpochSecond = json.getLong("end"),
            totals = metricsFromJson(json.optJSONObject("totals")),
            buckets = json.optJSONArray("buckets")?.let { array ->
                (0 until array.length()).mapNotNull { index ->
                    array.optJSONObject(index)?.let { bucket ->
                        QuotaBucket(
                            startEpochSecond = bucket.getLong("start"),
                            endEpochSecond = bucket.getLong("end"),
                            partial = bucket.optBoolean("partial", false),
                            metrics = metricsFromJson(bucket.optJSONObject("metrics")),
                        )
                    }
                }
            }.orEmpty(),
            groups = groupsFromJson(json.optJSONArray("groups")),
            costUnit = json.optString("costUnit").takeIf { it.isNotEmpty() }?.let(QuotaUnit::valueOf),
        )
    }.getOrNull()

    private fun metricsToJson(metrics: QuotaMetrics): JSONObject = JSONObject().apply {
        put("requests", metrics.requestCount)
        metrics.inputTokens?.let { put("input", it) }
        metrics.cachedInputTokens?.let { put("cached", it) }
        metrics.outputTokens?.let { put("output", it) }
        metrics.cost?.let { put("cost", it.toPlainString()) }
    }

    private fun metricsFromJson(json: JSONObject?): QuotaMetrics {
        if (json == null) return QuotaMetrics()
        return QuotaMetrics(
            requestCount = json.optInt("requests", 0),
            inputTokens = if (json.has("input")) json.getLong("input") else null,
            cachedInputTokens = if (json.has("cached")) json.getLong("cached") else null,
            outputTokens = if (json.has("output")) json.getLong("output") else null,
            cost = json.optString("cost").takeIf { it.isNotEmpty() }?.let(::BigDecimal),
        )
    }

    private fun groupsToJson(groups: List<QuotaGroup>): JSONArray = JSONArray().apply {
        groups.forEach { group ->
            put(JSONObject().put("name", group.name).put("metrics", metricsToJson(group.metrics)))
        }
    }

    private fun groupsFromJson(array: JSONArray?): List<QuotaGroup> {
        if (array == null) return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let { json ->
                QuotaGroup(
                    name = json.optString("name"),
                    metrics = metricsFromJson(json.optJSONObject("metrics")),
                )
            }
        }
    }
}
