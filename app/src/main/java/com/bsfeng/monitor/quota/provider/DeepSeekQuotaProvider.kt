package com.bsfeng.monitor.quota.provider

import com.bsfeng.monitor.domain.quota.QuotaBucket
import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import com.bsfeng.monitor.domain.quota.QuotaError
import com.bsfeng.monitor.domain.quota.QuotaErrorKind
import com.bsfeng.monitor.domain.quota.QuotaGranularity
import com.bsfeng.monitor.domain.quota.QuotaGroup
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaMetrics
import com.bsfeng.monitor.domain.quota.QuotaProvider
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.domain.quota.QuotaUsage
import java.math.BigDecimal
import java.util.TimeZone

/**
 * DeepSeek 额度 provider：**只用一个凭据** —— 平台登录令牌。
 *
 * - 用量 `platform.deepseek.com/api/v0/usage/by_api_key/amount|cost` 用登录令牌（会话 token，会过期）；
 *   必须带非空 User-Agent（缺了被 WAF 挡成 429 + HTML），错误恒为 HTTP 200 靠 body 的 `code` / `biz_code` 判断，
 *   单次区间上限 31 天（这里最多查 30 天）。
 * - 余额拿不到：账户余额只有官方 `api.deepseek.com/user/balance` 提供，且要 `sk-` API Key，
 *   而平台内部**没有**余额接口（users/balance、billing/summary 等实测全 404）。
 *   所以这里不伪装余额，`fetchItems` 返回空列表，额度页只展示用量。
 */
class DeepSeekQuotaProvider(
    private val http: QuotaHttp = QuotaHttp(),
    private val nowEpochSecond: () -> Long = { System.currentTimeMillis() / 1000L },
    private val zoneOffsetSeconds: () -> Int = {
        TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
    },
    private val balanceBaseUrl: String = DEFAULT_BALANCE_BASE_URL,
    private val platformBaseUrl: String = DEFAULT_PLATFORM_BASE_URL,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : QuotaProvider {

    override val id: String = "deepseek"
    override val displayName: String = "DeepSeek"
    /** 余额与用量共用同一个登录令牌，设置页只显示一个输入框。 */
    override val balanceCredentialKind: QuotaCredentialKind = QuotaCredentialKind.PLATFORM_TOKEN
    override val usageCredentialKind: QuotaCredentialKind? = QuotaCredentialKind.PLATFORM_TOKEN
    override val supportsModelBreakdown: Boolean = true
    override val credentialHint: String = "平台登录令牌"
    override val credentialHelp: String = "浏览器登录 platform.deepseek.com 后，从任意请求的 authorization 头复制；" +
        "会过期，失效后重贴"
    override val usageCredentialHint: String = "平台登录令牌"
    override val usageCredentialHelp: String = "与上面同一个令牌，只填一次即可"

    /** 平台内部没有余额接口，这里只查用量；返回空列表让额度页只渲染用量卡。 */
    override suspend fun fetchItems(credential: String): List<QuotaItem> = emptyList()

    override suspend fun fetchUsage(credential: String, range: QuotaRange): QuotaUsage {
        val token = QuotaHttp.normalizeCredential(credential)
        val tz = zoneOffsetSeconds()
        // 平台要求区间端点是桶边界：传「当前那一秒」会被判 INVALID_PARAM。
        // 24 小时对齐到整点，7/30 天对齐到本地日界（tz 决定天的边界）。
        val (start, end) = alignedRange(range, nowEpochSecond(), tz)
        val usage = parseUsage(fetchBiz(amountUrl(start, end, tz), token), range, start, end, end)
        // 花费是可选信息：任何失败（含 429）都保留用量，不打断主流程
        val costBiz = runCatching { fetchBiz(costUrl(start, end, tz), token) }.getOrNull() ?: return usage
        return runCatching { applyCost(usage, costBiz) }.getOrDefault(usage)
    }

    private fun amountUrl(start: Long, end: Long, tz: Int): String =
        "$platformBaseUrl/api/v0/usage/by_api_key/amount?start=$start&end=$end&tz=$tz"

    private fun costUrl(start: Long, end: Long, tz: Int): String =
        "$platformBaseUrl/api/v0/usage/by_api_key/cost?start=$start&end=$end&tz=$tz"

    /** 平台接口：HTTP 200 里的业务错误按 `code` / `biz_code` 归一，取到 `data.biz_data` 为止。 */
    private fun fetchBiz(url: String, token: String): JsonNode.Obj {
        val body = http.getText(url, QuotaHttp.bearer(token, userAgent))
        val root = QuotaJson.parse(body) ?: throw QuotaError(QuotaErrorKind.INVALID_RESPONSE, "响应不是合法 JSON")
        val code = root.int("code") ?: 0
        if (code != 0) throw bizError(code, root.str("msg"))
        val data = root.obj("data") ?: throw QuotaError(QuotaErrorKind.INVALID_RESPONSE, "缺少 data")
        val bizCode = data.int("biz_code") ?: 0
        if (bizCode != 0) throw bizError(bizCode, data.str("biz_msg"))
        return data.obj("biz_data") ?: throw QuotaError(QuotaErrorKind.INVALID_RESPONSE, "缺少 data.biz_data")
    }

    private fun bizError(code: Int, message: String?): QuotaError = when (code) {
        40002 -> QuotaError(QuotaErrorKind.MISSING_CREDENTIAL, message)
        40003 -> QuotaError(QuotaErrorKind.UNAUTHORIZED, message)
        else -> QuotaError(QuotaErrorKind.INVALID_PARAM, QuotaJson.oneLine(message) ?: "code=$code")
    }

    companion object {

        const val DEFAULT_BALANCE_BASE_URL: String = "https://api.deepseek.com"
        const val DEFAULT_PLATFORM_BASE_URL: String = "https://platform.deepseek.com"

        /** 平台接口必须带非空 User-Agent，否则 WAF 直接 429 + HTML。 */
        const val DEFAULT_USER_AGENT: String = "android-monitor/1.0 (Android; quota-monitor)"

        private const val UNKNOWN_MODEL = "未知模型"
        /** 把区间端点对齐到服务端的桶边界；不对齐时接口返回 biz_code=1 INVALID_PARAM。 */
        internal fun alignedRange(range: QuotaRange, now: Long, tz: Int): Pair<Long, Long> {
            val step = if (range == QuotaRange.LAST_24_HOURS) HOUR_SECONDS else DAY_SECONDS
            val local = now + tz
            val end = (local / step) * step - tz
            return (end - range.seconds) to end
        }

        private const val HOUR_SECONDS = 3_600L
        private const val DAY_SECONDS = 86_400L

        /**
         * 解析 `/user/balance`：每个 `balance_infos` 条目一条余额项，第一条 primary，不跨币种相加。
         *
         * `is_available=false` 时 remaining 置 0（让低余额预警生效），条目本身（币种 / id / 单位）保留；
         * 领域模型没有「原始金额」字段，原值只在 `is_available=true` 时体现。
         */
        internal fun parseBalance(root: JsonNode): List<QuotaItem> {
            val available = root.bool("is_available") ?: true
            return root.items("balance_infos").mapIndexedNotNull { index, info ->
                val currency = info.str("currency")?.trim().orEmpty().uppercase()
                val amount = info.dec("total_balance") ?: BigDecimal.ZERO
                QuotaItem(
                    id = "balance:" + currency.lowercase().ifEmpty { "unknown" },
                    title = if (currency.isEmpty()) "余额" else "$currency 余额",
                    kind = QuotaKind.MONEY,
                    remaining = if (available) amount else BigDecimal.ZERO,
                    total = null,
                    unit = currencyUnit(currency),
                    primary = index == 0,
                )
            }
        }

        private fun currencyUnit(currency: String): QuotaUnit = when (currency) {
            "CNY" -> QuotaUnit.CNY
            "USD" -> QuotaUnit.USD
            else -> QuotaUnit.USD
        }

        /**
         * 解析 `amount` 的 `data.biz_data`：桶按时间升序，`series` 按 model 跨 api_key 聚合，
         * totals 为全区间求和；`input_tokens` = 缓存命中 + 未命中，`cached_input_tokens` = 命中。
         */
        internal fun parseUsage(
            biz: JsonNode,
            range: QuotaRange,
            requestedStart: Long,
            requestedEnd: Long,
            nowEpochSecond: Long,
        ): QuotaUsage {
            val bucketSeconds = biz.long("bucket")?.takeIf { it > 0 } ?: defaultBucketSeconds(range)
            val granularity = when {
                bucketSeconds == HOUR_SECONDS -> QuotaGranularity.HOUR
                bucketSeconds == DAY_SECONDS -> QuotaGranularity.DAY
                else -> defaultGranularity(range)
            }
            val perTime = LinkedHashMap<Long, TokenSum>()
            val perModel = LinkedHashMap<String, TokenSum>()
            var totals = TokenSum()
            for (series in biz.items("series")) {
                val model = series.str("model")?.trim().orEmpty().ifEmpty { UNKNOWN_MODEL }
                for (bucket in series.items("buckets")) {
                    val time = bucket.epochSecond("time") ?: continue
                    val metrics = parseUsageMetrics(bucket.obj("usage"))
                    perTime[time] = (perTime[time] ?: TokenSum()).plus(metrics)
                    perModel[model] = (perModel[model] ?: TokenSum()).plus(metrics)
                    totals = totals.plus(metrics)
                }
            }
            val buckets = perTime.entries.sortedBy { it.key }.map { (start, sum) ->
                val end = start + bucketSeconds
                QuotaBucket(
                    startEpochSecond = start,
                    endEpochSecond = end,
                    partial = end > nowEpochSecond,
                    metrics = sum.toMetrics(),
                )
            }
            return QuotaUsage(
                range = range,
                granularity = granularity,
                startEpochSecond = biz.epochSecond("start") ?: requestedStart,
                endEpochSecond = biz.epochSecond("end") ?: requestedEnd,
                totals = totals.toMetrics(),
                buckets = buckets,
                groups = perModel.map { (name, sum) -> QuotaGroup(name, sum.toMetrics()) },
            )
        }

        /** 单个桶的 `usage`：REQUEST / RESPONSE_TOKEN / PROMPT_CACHE_HIT_TOKEN / PROMPT_CACHE_MISS_TOKEN。 */
        internal fun parseUsageMetrics(usage: JsonNode?): QuotaMetrics {
            val cached = usage.long("PROMPT_CACHE_HIT_TOKEN") ?: 0L
            val missed = usage.long("PROMPT_CACHE_MISS_TOKEN") ?: 0L
            return QuotaMetrics(
                requestCount = usage.int("REQUEST") ?: 0,
                inputTokens = cached + missed,
                cachedInputTokens = cached,
                outputTokens = usage.long("RESPONSE_TOKEN") ?: 0L,
            )
        }

        /** 把 `cost` 响应的对应币种金额求和填进 `totals.cost`；优先 CNY，其次第一条币种。 */
        internal fun applyCost(usage: QuotaUsage, biz: JsonNode): QuotaUsage {
            val entries = biz.items("data")
            if (entries.isEmpty()) return usage
            val preferred = entries.firstOrNull { it.str("currency")?.trim().equals("CNY", true) }
                ?: entries.first()
            var sum = BigDecimal.ZERO
            var found = false
            for (series in preferred.items("series")) {
                for (bucket in series.items("buckets")) {
                    val cost = bucket.dec("cost") ?: continue
                    sum += cost
                    found = true
                }
            }
            val unit = if (preferred.str("currency")?.trim().equals("USD", true) == true) {
                QuotaUnit.USD
            } else {
                QuotaUnit.CNY
            }
            return if (found) {
                usage.copy(totals = usage.totals.copy(cost = sum), costUnit = unit)
            } else {
                usage.copy(costUnit = unit)
            }
        }

        private fun defaultBucketSeconds(range: QuotaRange): Long =
            if (range == QuotaRange.LAST_24_HOURS) HOUR_SECONDS else DAY_SECONDS

        private fun defaultGranularity(range: QuotaRange): QuotaGranularity =
            if (range == QuotaRange.LAST_24_HOURS) QuotaGranularity.HOUR else QuotaGranularity.DAY

        /** 可加和的计数；token 缺失按 0 计（DeepSeek 每个桶都给全字段）。 */
        private data class TokenSum(
            val requests: Int = 0,
            val input: Long = 0L,
            val cached: Long = 0L,
            val output: Long = 0L,
        ) {
            fun plus(metrics: QuotaMetrics): TokenSum = TokenSum(
                requests = requests + metrics.requestCount,
                input = input + (metrics.inputTokens ?: 0L),
                cached = cached + (metrics.cachedInputTokens ?: 0L),
                output = output + (metrics.outputTokens ?: 0L),
            )

            fun toMetrics(): QuotaMetrics = QuotaMetrics(
                requestCount = requests,
                inputTokens = input,
                cachedInputTokens = cached,
                outputTokens = output,
            )
        }
    }
}
