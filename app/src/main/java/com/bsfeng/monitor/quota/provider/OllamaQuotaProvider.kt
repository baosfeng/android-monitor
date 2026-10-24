package com.bsfeng.monitor.quota.provider

import com.bsfeng.monitor.domain.quota.QuotaBucket
import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import com.bsfeng.monitor.domain.quota.QuotaGranularity
import com.bsfeng.monitor.domain.quota.QuotaGroup
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaMetrics
import com.bsfeng.monitor.domain.quota.QuotaPeriod
import com.bsfeng.monitor.domain.quota.QuotaProvider
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.domain.quota.QuotaUsage
import java.math.BigDecimal

/**
 * Ollama 额度 provider：`/api/balance` 取额度，`/api/usage` 取用量（Bearer 同一把 API Key）。
 *
 * 额度有两种互斥形态，**两种都解析**（由用户在页面上选展示口径，provider 不替用户选）：
 * - 新版 USD 型：`included{balance_usd, allowance_usd, period{from,until}}` → 内含额度（primary）+ `purchased{balance_usd}`；
 * - 旧版百分比型：`included{session{remaining_percent,resets_at}, weekly{...}}` → PERCENT 项。
 *
 * 金额 `allowance_usd` 为 0 时 [QuotaItem.remainingFraction] 自己返回 null，这里不做除法。
 */
class OllamaQuotaProvider(
    private val http: QuotaHttp = QuotaHttp(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    /**
     * 必须显式带 UA：Android 的 HttpURLConnection 默认发 `Dalvik/…`，
     * 而 ollama.com 前置的 Cloudflare 规则会把 Dalvik UA 直接挡成 403（HTML 错误页），
     * 换任意非 Dalvik 的 UA（实测 `android-monitor/1.0`）就恢复正常鉴权语义。
     */
    private val userAgent: String = DEFAULT_USER_AGENT,
) : QuotaProvider {

    override val id: String = "ollama"
    override val displayName: String = "Ollama"
    override val balanceCredentialKind: QuotaCredentialKind = QuotaCredentialKind.API_KEY
    override val usageCredentialKind: QuotaCredentialKind? = QuotaCredentialKind.API_KEY
    override val supportsModelBreakdown: Boolean = true
    override val credentialHint: String = "Ollama API Key"
    override val credentialHelp: String = "在 ollama.com 的账号设置里创建 Key"
    override val usageCredentialHint: String = "Ollama API Key（与额度同一把）"
    override val usageCredentialHelp: String = "用量接口和余额接口共用同一把 Key"

    override suspend fun fetchItems(credential: String): List<QuotaItem> {
        val key = QuotaHttp.normalizeCredential(credential)
        return parseBalance(http.getJson("$baseUrl/api/balance", QuotaHttp.bearer(key, userAgent)))
    }

    override suspend fun fetchUsage(credential: String, range: QuotaRange): QuotaUsage {
        val key = QuotaHttp.normalizeCredential(credential)
        val url = "$baseUrl/api/usage?range=${range.queryValue}&scope=self&group_by=model"
        return parseUsage(http.getJson(url, QuotaHttp.bearer(key, userAgent)), range)
    }

    companion object {

        const val DEFAULT_BASE_URL: String = "https://ollama.com"

        /** 中性 UA；只要不是 `Dalvik/…` 就不会被 Cloudflare 拦成 403。 */
        const val DEFAULT_USER_AGENT: String = "android-monitor/1.0 (Android; quota-monitor)"

        private val ZERO = BigDecimal.ZERO
        private val HUNDRED = BigDecimal.valueOf(100)
        private const val HOUR_SECONDS = 3_600L
        private const val DAY_SECONDS = 86_400L

        /** 解析 `/api/balance`；两种形态都尝试，认不出的字段忽略，全认不出返回空列表。 */
        internal fun parseBalance(root: JsonNode): List<QuotaItem> {
            val included = root.obj("included")
            return normalizePrimary(
                buildList {
                    addAll(parseUsdIncluded(included))
                    addAll(parsePercentIncluded(included))
                    parsePurchased(root.obj("purchased"))?.let { add(it) }
                },
            )
        }

        /** 恰好留一条 primary：优先第一条自称 primary 的，否则第一条；其余降级为次级行。 */
        private fun normalizePrimary(items: List<QuotaItem>): List<QuotaItem> {
            if (items.isEmpty()) return items
            val primaryIndex = items.indexOfFirst { it.primary }.takeIf { it >= 0 } ?: 0
            return items.mapIndexed { index, item ->
                val primary = index == primaryIndex
                if (item.primary == primary) item else item.copy(primary = primary)
            }
        }

        private fun parseUsdIncluded(included: JsonNode.Obj?): List<QuotaItem> {
            val balance = included?.dec("balance_usd") ?: return emptyList()
            val allowance = included.dec("allowance_usd")
            val period = parsePeriod(included.obj("period"))
            return listOf(
                QuotaItem(
                    id = "included",
                    title = "内含额度",
                    kind = QuotaKind.MONEY,
                    remaining = balance,
                    total = allowance,
                    unit = QuotaUnit.USD,
                    period = period,
                    resetsAtEpochSecond = period?.endEpochSecond ?: included.epochSecond("resets_at"),
                    primary = true,
                ),
            )
        }

        private fun parsePercentIncluded(included: JsonNode.Obj?): List<QuotaItem> {
            if (included == null) return emptyList()
            return PERCENT_SLOTS.mapNotNull { (field, title) ->
                val node = included.obj(field) ?: return@mapNotNull null
                val remaining = node.dec("remaining_percent") ?: return@mapNotNull null
                QuotaItem(
                    id = field,
                    title = title,
                    kind = QuotaKind.PERCENT,
                    remaining = remaining.coerceIn(ZERO, HUNDRED),
                    total = null,
                    unit = QuotaUnit.PERCENT,
                    resetsAtEpochSecond = node.epochSecond("resets_at"),
                    primary = true,
                )
            }
        }

        private fun parsePurchased(purchased: JsonNode.Obj?): QuotaItem? {
            val balance = purchased?.dec("balance_usd") ?: return null
            return QuotaItem(
                id = "purchased",
                title = "已购余额",
                kind = QuotaKind.MONEY,
                remaining = balance,
                total = null,
                unit = QuotaUnit.USD,
            )
        }

        private fun parsePeriod(period: JsonNode.Obj?): QuotaPeriod? {
            if (period == null) return null
            val from = period.epochSecond("from") ?: return null
            val until = period.epochSecond("until") ?: return null
            return if (until > from) QuotaPeriod(from, until) else null
        }

        /** 解析 `/api/usage`；缺 totals 时用 buckets 求和兜底，桶按开始时间升序。 */
        internal fun parseUsage(root: JsonNode, range: QuotaRange): QuotaUsage {
            val granularity = when (root.str("granularity")?.lowercase()) {
                "hour" -> QuotaGranularity.HOUR
                "day" -> QuotaGranularity.DAY
                else -> defaultGranularity(range)
            }
            val bucketSeconds = if (granularity == QuotaGranularity.HOUR) HOUR_SECONDS else DAY_SECONDS
            val buckets = root.items("buckets").mapNotNull { node ->
                val start = node.epochSecond("from") ?: return@mapNotNull null
                val end = node.epochSecond("until") ?: (start + bucketSeconds)
                QuotaBucket(
                    startEpochSecond = start,
                    endEpochSecond = end,
                    partial = node.bool("partial") == true,
                    metrics = parseMetrics(node),
                )
            }.sortedBy { it.startEpochSecond }

            val totals = root.obj("totals")?.let { parseMetrics(it) }
                ?: sumBuckets(buckets)

            val groupNodes = root.items("groups").ifEmpty { root.obj("totals")?.items("groups").orEmpty() }
            val groups = groupNodes.mapNotNull { node ->
                val name = node.str("model")?.trim().orEmpty()
                if (name.isEmpty()) return@mapNotNull null
                QuotaGroup(name, parseMetrics(node))
            }

            val start = root.epochSecond("from") ?: buckets.firstOrNull()?.startEpochSecond ?: 0L
            val end = root.epochSecond("until") ?: buckets.lastOrNull()?.endEpochSecond ?: start
            return QuotaUsage(
                range = range,
                granularity = granularity,
                startEpochSecond = start,
                endEpochSecond = end,
                totals = totals,
                buckets = buckets,
                groups = groups,
                // Ollama 的金额字段一律 USD
                costUnit = QuotaUnit.USD,
            )
        }

        internal fun parseMetrics(node: JsonNode): QuotaMetrics = QuotaMetrics(
            requestCount = node.int("request_count") ?: 0,
            inputTokens = node.long("input_tokens"),
            cachedInputTokens = node.long("cached_input_tokens"),
            outputTokens = node.long("output_tokens"),
            // 旧版百分比计划不返回金额，这里自然为 null
            cost = node.dec("usage_usd"),
        )

        private fun sumBuckets(buckets: List<QuotaBucket>): QuotaMetrics {
            if (buckets.isEmpty()) return QuotaMetrics()
            var requests = 0
            var input = 0L
            var cached = 0L
            var output = 0L
            var cost = ZERO
            var hasTokens = false
            buckets.forEach { bucket ->
                val m = bucket.metrics
                requests += m.requestCount
                input += m.inputTokens ?: 0
                cached += m.cachedInputTokens ?: 0
                output += m.outputTokens ?: 0
                hasTokens = hasTokens || m.inputTokens != null || m.outputTokens != null
                cost += m.cost ?: ZERO
            }
            return QuotaMetrics(
                requestCount = requests,
                inputTokens = if (hasTokens) input else null,
                cachedInputTokens = if (hasTokens) cached else null,
                outputTokens = if (hasTokens) output else null,
                cost = cost,
            )
        }

        private fun defaultGranularity(range: QuotaRange): QuotaGranularity =
            if (range == QuotaRange.LAST_24_HOURS) QuotaGranularity.HOUR else QuotaGranularity.DAY

        private val PERCENT_SLOTS = listOf("session" to "会话额度", "weekly" to "每周额度")
    }
}
