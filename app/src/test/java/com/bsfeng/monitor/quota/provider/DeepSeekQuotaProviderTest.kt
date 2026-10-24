package com.bsfeng.monitor.quota.provider

import com.bsfeng.monitor.domain.quota.QuotaError
import com.bsfeng.monitor.domain.quota.QuotaErrorKind
import com.bsfeng.monitor.domain.quota.QuotaGranularity
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.domain.quota.QuotaUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** DeepSeek provider：余额（API Key）与用量（平台令牌）两条链路，全部走假 transport。 */
class DeepSeekQuotaProviderTest {

    private class RoutingTransport(
        private val amount: () -> QuotaHttpResponse,
        private val cost: () -> QuotaHttpResponse = { QuotaHttpResponse(500, "no cost stub") },
        private val balance: () -> QuotaHttpResponse = { QuotaHttpResponse(200, BALANCE_BODY) },
    ) : QuotaTransport {
        val requests = mutableListOf<Pair<String, Map<String, String>>>()
        override fun get(url: String, headers: Map<String, String>): QuotaHttpResponse {
            requests += url to headers
            return when {
                url.contains("/user/balance") -> balance()
                url.contains("/amount") -> amount()
                else -> cost()
            }
        }
        fun urls(): List<String> = requests.map { it.first }
        fun headers(): Map<String, String> = requests.first().second
    }

    private fun provider(
        transport: QuotaTransport,
        now: Long = NOW,
        zoneOffset: Int = 28_800,
    ) = DeepSeekQuotaProvider(
        http = QuotaHttp(transport),
        nowEpochSecond = { now },
        zoneOffsetSeconds = { zoneOffset },
    )

    private fun failure(transport: QuotaTransport, block: suspend DeepSeekQuotaProvider.() -> Unit): QuotaError {
        val thrown = runCatching { runBlocking { provider(transport).block() } }.exceptionOrNull()
        assertTrue("期望 QuotaError，实际 $thrown", thrown is QuotaError)
        return thrown as QuotaError
    }

    private fun parse(body: String): JsonNode = QuotaJson.parse(body)!!

    // ------------------------- 余额 -------------------------

    @Test
    fun `余额金额是字符串且多币种分行`() {
        val items = DeepSeekQuotaProvider.parseBalance(parse(BALANCE_BODY))

        assertEquals(2, items.size)
        val cny = items[0]
        assertEquals("balance:cny", cny.id)
        assertEquals(QuotaUnit.CNY, cny.unit)
        assertEquals(0, cny.remaining.compareTo(BigDecimal("110.00")))
        assertNull(cny.total)
        assertTrue(cny.isPlainBalance)
        assertTrue(cny.primary)

        val usd = items[1]
        assertEquals("balance:usd", usd.id)
        assertEquals(QuotaUnit.USD, usd.unit)
        assertEquals(0, usd.remaining.compareTo(BigDecimal("5.25")))
        assertFalse(usd.primary)
    }

    @Test
    fun `is_available 为 false 时剩余置 0 但条目保留`() {
        val body = """{"is_available":false,"balance_infos":[
            {"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"}]}"""
        val items = DeepSeekQuotaProvider.parseBalance(parse(body))

        assertEquals(1, items.size)
        assertEquals("balance:cny", items[0].id)
        assertEquals(0, items[0].remaining.compareTo(BigDecimal.ZERO))
        assertEquals(QuotaUnit.CNY, items[0].unit)
    }

    @Test
    fun `余额空列表与缺字段都能兜住`() {
        assertTrue(DeepSeekQuotaProvider.parseBalance(parse("""{"is_available":true,"balance_infos":[]}""")).isEmpty())
        val items = DeepSeekQuotaProvider.parseBalance(parse("""{"balance_infos":[{"currency":"JPY"}]}"""))
        assertEquals(1, items.size)
        assertEquals("balance:jpy", items[0].id)
        assertEquals(0, items[0].remaining.compareTo(BigDecimal.ZERO))
        assertEquals(QuotaUnit.USD, items[0].unit)
    }

    @Test
    fun `额度项为空 因为平台登录令牌查不到余额`() {
        // 余额只有官方 api.deepseek.com/user/balance 提供且要 sk- Key；
        // 平台内部没有余额接口，所以这里不发请求、返回空列表，额度页只渲染用量卡。
        val transport = RoutingTransport(amount = { QuotaHttpResponse(200, USAGE_BIZ) })
        val items = runBlocking { provider(transport).fetchItems("session-token") }

        assertTrue(items.isEmpty())
        assertTrue("不应发起任何请求", transport.urls().isEmpty())
    }

    @Test
    fun `区间端点对齐到桶边界 否则平台判 INVALID_PARAM`() {
        // 回归：传「当前那一秒」时平台返回 biz_code=1，必须对齐整点（24h）/ 本地日界（7d、30d）
        val now = 1_792_777_121L // 不是整点
        val (s24, e24) = DeepSeekQuotaProvider.alignedRange(QuotaRange.LAST_24_HOURS, now, 28_800)
        assertEquals(0L, e24 % 3_600L - 28_800 % 3_600L)
        assertEquals(86_400L, e24 - s24)

        val (s7, e7) = DeepSeekQuotaProvider.alignedRange(QuotaRange.LAST_7_DAYS, now, 28_800)
        assertEquals(7 * 86_400L, e7 - s7)
        assertEquals(0L, (e7 + 28_800L) % 86_400L)
    }

    // ------------------------- 用量 -------------------------

    @Test
    fun `用量按模型跨 api key 聚合且桶按时间升序`() {
        val usage = DeepSeekQuotaProvider.parseUsage(parse(USAGE_BIZ), QuotaRange.LAST_24_HOURS, 1L, 2L, NOW)

        assertEquals(QuotaGranularity.HOUR, usage.granularity)
        assertEquals(1_792_782_000L, usage.startEpochSecond)
        assertEquals(1_792_789_200L, usage.endEpochSecond)

        assertEquals(2, usage.buckets.size)
        val first = usage.buckets[0]
        assertEquals(1_792_782_000L, first.startEpochSecond)
        assertEquals(1_792_785_600L, first.endEpochSecond)
        assertEquals(9, first.metrics.requestCount)
        assertEquals(753L, first.metrics.inputTokens) // HIT 301 + MISS 452
        assertEquals(301L, first.metrics.cachedInputTokens)
        assertEquals(157L, first.metrics.outputTokens)

        val second = usage.buckets[1]
        assertEquals(1_792_785_600L, second.startEpochSecond)
        assertEquals(2, second.metrics.requestCount)
        assertEquals(50L, second.metrics.inputTokens)
        assertEquals(10L, second.metrics.outputTokens)

        assertEquals(11, usage.totals.requestCount)
        assertEquals(803L, usage.totals.inputTokens)
        assertEquals(321L, usage.totals.cachedInputTokens)
        assertEquals(167L, usage.totals.outputTokens)

        assertEquals(listOf("deepseek-chat", "deepseek-reasoner"), usage.groups.map { it.name })
        val chat = usage.groups[0].metrics
        assertEquals(10, chat.requestCount)
        assertEquals(800L, chat.inputTokens)
        assertEquals(320L, chat.cachedInputTokens)
        assertEquals(160L, chat.outputTokens)
        assertEquals(1, usage.groups[1].metrics.requestCount)
    }

    @Test
    fun `进行中的桶标记 partial`() {
        val ongoing = DeepSeekQuotaProvider.parseUsage(
            parse(USAGE_BIZ),
            QuotaRange.LAST_24_HOURS,
            1L,
            2L,
            nowEpochSecond = 1_792_785_600L, // 第二个桶还没走完
        )
        assertFalse(ongoing.buckets[0].partial)
        assertTrue(ongoing.buckets[1].partial)
    }

    @Test
    fun `空 series 返回空桶空组且不抛错`() {
        val usage = DeepSeekQuotaProvider.parseUsage(
            parse("""{"bucket":3600,"start":100,"end":200}"""),
            QuotaRange.LAST_24_HOURS,
            requestedStart = 100L,
            requestedEnd = 200L,
            nowEpochSecond = 200L,
        )
        assertTrue(usage.buckets.isEmpty())
        assertTrue(usage.groups.isEmpty())
        assertEquals(0, usage.totals.requestCount)
        assertEquals(100L, usage.startEpochSecond)
        assertEquals(200L, usage.endEpochSecond)
        assertEquals(QuotaGranularity.HOUR, usage.granularity)
    }

    @Test
    fun `区间不超过 31 天并带时区偏移与 User-Agent`() {
        val transport = RoutingTransport(
            amount = { QuotaHttpResponse(200, USAGE_RESPONSE) },
            cost = { QuotaHttpResponse(200, COST_RESPONSE) },
        )
        runBlocking { provider(transport).fetchUsage("tok-123", QuotaRange.LAST_30_DAYS) }

        val url = transport.urls().first()
        assertTrue(url, url.startsWith("https://platform.deepseek.com/api/v0/usage/by_api_key/amount?"))
        // 端点必须对齐桶边界：不对齐时平台返回 biz_code=1
        val (start, end) = DeepSeekQuotaProvider.alignedRange(QuotaRange.LAST_30_DAYS, NOW, 28_800)
        assertTrue(url, url.contains("start=$start"))
        assertTrue(url, url.contains("end=$end"))
        assertTrue(url, url.contains("tz=28800"))

        val headers = transport.headers()
        assertEquals("Bearer tok-123", headers["Authorization"])
        assertFalse(headers["User-Agent"].isNullOrBlank())
    }

    // ------------------------- 花费 -------------------------

    @Test
    fun `花费按币种求和填进 totals 且优先 CNY`() {
        val transport = RoutingTransport(
            amount = { QuotaHttpResponse(200, USAGE_RESPONSE) },
            cost = { QuotaHttpResponse(200, COST_RESPONSE) },
        )
        val usage = runBlocking { provider(transport).fetchUsage("tok-123", QuotaRange.LAST_24_HOURS) }
        assertEquals(0, usage.totals.cost!!.compareTo(BigDecimal("0.40")))
    }

    @Test
    fun `花费接口失败不影响用量`() {
        val bad = RoutingTransport(
            amount = { QuotaHttpResponse(200, USAGE_RESPONSE) },
            cost = { QuotaHttpResponse(500, "upstream error") },
        )
        val usage = runBlocking { provider(bad).fetchUsage("tok-123", QuotaRange.LAST_24_HOURS) }
        assertEquals(11, usage.totals.requestCount)
        assertNull(usage.totals.cost)

        val notJson = RoutingTransport(
            amount = { QuotaHttpResponse(200, USAGE_RESPONSE) },
            cost = { QuotaHttpResponse(200, "<html>waf</html>") },
        )
        val stillUsage = runBlocking { provider(notJson).fetchUsage("tok-123", QuotaRange.LAST_24_HOURS) }
        assertEquals(11, stillUsage.totals.requestCount)
        assertNull(stillUsage.totals.cost)
    }

    // ------------------------- 业务错误 -------------------------

    @Test
    fun `code 40003 归为凭据无效`() {
        val transport = RoutingTransport(amount = { QuotaHttpResponse(200, """{"code":40003,"msg":"Invalid Token","data":null}""") })
        assertEquals(QuotaErrorKind.UNAUTHORIZED, failure(transport) { fetchUsage("tok-bad", QuotaRange.LAST_24_HOURS) }.kind)
    }

    @Test
    fun `code 40002 归为缺少凭据`() {
        val transport = RoutingTransport(amount = { QuotaHttpResponse(200, """{"code":40002,"msg":"Missing Token"}""") })
        // 非空但服务端认为没带 token，同样归 MISSING_CREDENTIAL
        assertEquals(
            QuotaErrorKind.MISSING_CREDENTIAL,
            failure(transport) { fetchUsage("tok-123", QuotaRange.LAST_24_HOURS) }.kind,
        )
    }

    @Test
    fun `biz_code 1 归为参数不合法`() {
        val body = """{"code":0,"msg":"","data":{"biz_code":1,"biz_msg":"INVALID_PARAM"}}"""
        val error = failure(RoutingTransport(amount = { QuotaHttpResponse(200, body) })) {
            fetchUsage("tok-123", QuotaRange.LAST_24_HOURS)
        }
        assertEquals(QuotaErrorKind.INVALID_PARAM, error.kind)
        assertTrue(error.message.orEmpty().contains("INVALID_PARAM"))
    }

    @Test
    fun `缺 biz_data 或非 JSON 归为解析失败`() {
        val missing = RoutingTransport(amount = { QuotaHttpResponse(200, """{"code":0,"data":{"biz_code":0}}""") })
        assertEquals(QuotaErrorKind.INVALID_RESPONSE, failure(missing) { fetchUsage("tok-123", QuotaRange.LAST_24_HOURS) }.kind)

        val notJson = RoutingTransport(amount = { QuotaHttpResponse(200, "<html>waf challenge</html>") })
        assertEquals(QuotaErrorKind.INVALID_RESPONSE, failure(notJson) { fetchUsage("tok-123", QuotaRange.LAST_24_HOURS) }.kind)
    }

    // ------------------------- HTTP 层 -------------------------

    @Test
    fun `429 带 HTML 时归为限流而不是解析失败`() {
        val transport = RoutingTransport(
            amount = { QuotaHttpResponse(429, "<html><body>Too Many Requests</body></html>", retryAfterSeconds = 12) },
        )
        val error = failure(transport) { fetchUsage("tok-123", QuotaRange.LAST_24_HOURS) }
        assertEquals(QuotaErrorKind.RATE_LIMITED, error.kind)
        assertEquals(12L, error.retryAfterSeconds)
        assertTrue("错误提示要压成一行", !error.message.orEmpty().contains("\n"))
    }

    @Test
    fun `401 归为凭据无效且不回显令牌`() {
        val transport = RoutingTransport(amount = { QuotaHttpResponse(401, """{"error":"bad token tok-secret"}""") })
        val error = failure(transport) { fetchUsage("tok-secret", QuotaRange.LAST_24_HOURS) }
        assertEquals(QuotaErrorKind.UNAUTHORIZED, error.kind)
        assertFalse(error.message.orEmpty().contains("tok-secret"))
    }

    private companion object {
        const val NOW = 1_792_789_200L

        val BALANCE_BODY = """
            {"is_available":true,"balance_infos":[
              {"currency":"CNY","total_balance":"110.00","granted_balance":"10.00","topped_up_balance":"100.00"},
              {"currency":"USD","total_balance":"5.25","granted_balance":"0.00","topped_up_balance":"5.25"}
            ]}
        """.trimIndent()

        val USAGE_BIZ = """
            {"bucket":3600,"start":1792782000,"end":1792789200,
             "models":["deepseek-chat","deepseek-reasoner"],
             "series":[
               {"api_key":{"name":"key-a","sensitive_id":"sk-aaaa","valid":true},"model":"deepseek-chat",
                "buckets":[
                  {"time":1792785600,"usage":{"REQUEST":2,"RESPONSE_TOKEN":10,"PROMPT_CACHE_HIT_TOKEN":20,"PROMPT_CACHE_MISS_TOKEN":30}},
                  {"time":1792782000,"usage":{"REQUEST":5,"RESPONSE_TOKEN":100,"PROMPT_CACHE_HIT_TOKEN":200,"PROMPT_CACHE_MISS_TOKEN":300}}]},
               {"api_key":{"name":"key-b","sensitive_id":"sk-bbbb","valid":true},"model":"deepseek-chat",
                "buckets":[
                  {"time":1792782000,"usage":{"REQUEST":3,"RESPONSE_TOKEN":50,"PROMPT_CACHE_HIT_TOKEN":100,"PROMPT_CACHE_MISS_TOKEN":150}}]},
               {"api_key":{"name":"key-c","sensitive_id":"sk-cccc","valid":true},"model":"deepseek-reasoner",
                "buckets":[
                  {"time":1792782000,"usage":{"REQUEST":1,"RESPONSE_TOKEN":7,"PROMPT_CACHE_HIT_TOKEN":1,"PROMPT_CACHE_MISS_TOKEN":2}}]}
             ]}
        """.trimIndent()

        val USAGE_RESPONSE = """{"code":0,"msg":"","data":{"biz_code":0,"biz_msg":"","biz_data":$USAGE_BIZ}}"""

        val COST_RESPONSE = """
            {"code":0,"msg":"","data":{"biz_code":0,"biz_msg":"","biz_data":{"data":[
              {"currency":"USD","series":[{"model":"deepseek-chat","buckets":[{"time":1792782000,"cost":"1.11"}]}]},
              {"currency":"CNY","series":[
                {"model":"deepseek-chat","buckets":[{"time":1792782000,"cost":"0.36"},{"time":1792785600,"cost":"0.04"}]},
                {"model":"deepseek-reasoner","buckets":[{"time":1792782000,"cost":"0.00"}]}]}
            ]}}}
        """.trimIndent()
    }
}
