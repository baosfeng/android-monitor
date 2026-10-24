package com.bsfeng.monitor.quota.provider

import com.bsfeng.monitor.domain.quota.QuotaError
import com.bsfeng.monitor.domain.quota.QuotaErrorKind
import com.bsfeng.monitor.domain.quota.QuotaGranularity
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.domain.quota.QuotaUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.math.BigDecimal
import java.time.Instant

/** Ollama provider：解析、错误归一、请求形状。全部走假 transport，不发真请求。 */
class OllamaQuotaProviderTest {

    private class FakeTransport(private val respond: () -> QuotaHttpResponse) : QuotaTransport {
        var calls = 0
        var lastUrl: String? = null
        var lastHeaders: Map<String, String> = emptyMap()
        override fun get(url: String, headers: Map<String, String>): QuotaHttpResponse {
            calls++
            lastUrl = url
            lastHeaders = headers
            return respond()
        }
    }

    private fun provider(transport: QuotaTransport) =
        OllamaQuotaProvider(http = QuotaHttp(transport))

    private fun failure(transport: QuotaTransport, block: suspend OllamaQuotaProvider.() -> Unit): QuotaError {
        val thrown = runCatching { runBlocking { provider(transport).block() } }.exceptionOrNull()
        assertTrue("期望 QuotaError，实际 $thrown", thrown is QuotaError)
        return thrown as QuotaError
    }

    @Test
    fun `请求带非 Dalvik 的 UA 否则被 Cloudflare 挡成 403`() {
        // 回归：Android 的 HttpURLConnection 默认发 Dalvik/…，ollama.com 前置规则把它挡成 403，
        // 表现为「查询失败」但服务端其实没鉴权失败（裸 curl 是 401）。
        val transport = FakeTransport { QuotaHttpResponse(200, USD_BALANCE) }
        runBlocking { provider(transport).fetchItems("key-1") }

        val ua = transport.lastHeaders["User-Agent"]
        assertNotNull("必须显式带 UA", ua)
        assertFalse("不能是 Dalvik 前缀", ua!!.startsWith("Dalvik"))
    }

    // ------------------------- 额度：UDS 型 -------------------------

    @Test
    fun `USD 型包含额度解析成内含额度与已购余额`() {
        val transport = FakeTransport { QuotaHttpResponse(200, USD_BALANCE) }
        val items = runBlocking { provider(transport).fetchItems("sk-test") }

        assertEquals(2, items.size)
        val included = items[0]
        assertEquals("included", included.id)
        assertEquals(QuotaKind.MONEY, included.kind)
        assertEquals(QuotaUnit.USD, included.unit)
        assertEquals(0, included.remaining.compareTo(BigDecimal("12.34")))
        assertEquals(0, included.total!!.compareTo(BigDecimal("20.00")))
        assertTrue(included.primary)
        assertEquals(Instant.parse("2026-09-15T00:00:00Z").epochSecond, included.period!!.startEpochSecond)
        assertEquals(Instant.parse("2026-10-15T00:00:00Z").epochSecond, included.resetsAtEpochSecond)

        val purchased = items[1]
        assertEquals("purchased", purchased.id)
        assertEquals(QuotaUnit.USD, purchased.unit)
        assertNull(purchased.total)
        assertFalse(purchased.primary)
        assertTrue(purchased.isPlainBalance)
    }

    @Test
    fun `内含额度为 0 时不除零`() {
        val body = """{"included":{"balance_usd":5.00,"allowance_usd":0},"purchased":{"balance_usd":0}}"""
        val items = runBlocking { provider(FakeTransport { QuotaHttpResponse(200, body) }).fetchItems("sk-test") }

        assertEquals(2, items.size)
        assertNull(items[0].remainingFraction)
        assertNull(items[1].remainingFraction)
        assertTrue(items[1].isPlainBalance)
    }

    // ------------------------- 额度：旧版百分比型 -------------------------

    @Test
    fun `旧版百分比型解析会话与每周剩余百分比`() {
        val transport = FakeTransport { QuotaHttpResponse(200, PERCENT_BALANCE) }
        val items = runBlocking { provider(transport).fetchItems("sk-test") }

        assertEquals(2, items.size)
        val session = items.first { it.id == "session" }
        val weekly = items.first { it.id == "weekly" }
        assertEquals(QuotaKind.PERCENT, session.kind)
        assertEquals(QuotaUnit.PERCENT, session.unit)
        assertEquals(0, session.remaining.compareTo(BigDecimal("42")))
        assertNull(session.total)
        assertEquals(0.42f, session.remainingFraction!!, 0.001f)
        assertEquals(Instant.parse("2026-10-23T10:00:00Z").epochSecond, session.resetsAtEpochSecond)
        assertTrue(session.primary)

        // 0% 是合法极值，不参与除零也不能被当成缺失
        assertEquals(0, weekly.remaining.compareTo(BigDecimal.ZERO))
        assertFalse(weekly.primary)
        assertEquals(0f, weekly.remainingFraction!!, 0.001f)
    }

    @Test
    fun `两种形态互斥时另一种为空列表`() {
        val usdOnly = runBlocking {
            provider(FakeTransport { QuotaHttpResponse(200, USD_BALANCE) }).fetchItems("sk-test")
        }
        assertTrue(usdOnly.none { it.kind == QuotaKind.PERCENT })
        val percentOnly = runBlocking {
            provider(FakeTransport { QuotaHttpResponse(200, PERCENT_BALANCE) }).fetchItems("sk-test")
        }
        assertTrue(percentOnly.none { it.kind == QuotaKind.MONEY })
    }

    @Test
    fun `认不出的余额结构返回空列表而不是抛错`() {
        listOf("""{}""", """{"included":{}}""", """{"included":{"unknown":1},"purchased":{}}""").forEach { body ->
            val items = runBlocking {
                provider(FakeTransport { QuotaHttpResponse(200, body) }).fetchItems("sk-test")
            }
            assertTrue("body=$body 期望空列表，实际 $items", items.isEmpty())
        }
    }

    // ------------------------- 用量 -------------------------

    @Test
    fun `用量解析总量分桶与按模型分组`() {
        val transport = FakeTransport { QuotaHttpResponse(200, USAGE_24H) }
        val usage = runBlocking {
            provider(transport).fetchUsage("sk-test", QuotaRange.LAST_24_HOURS)
        }!!

        assertEquals(QuotaRange.LAST_24_HOURS, usage.range)
        assertEquals(QuotaGranularity.HOUR, usage.granularity)
        assertEquals(Instant.parse("2026-10-22T23:00:00Z").epochSecond, usage.startEpochSecond)
        assertEquals(Instant.parse("2026-10-23T23:00:00Z").epochSecond, usage.endEpochSecond)

        assertEquals(34, usage.totals.requestCount)
        assertEquals(1200L, usage.totals.inputTokens)
        assertEquals(800L, usage.totals.cachedInputTokens)
        assertEquals(300L, usage.totals.outputTokens)
        assertEquals(0, usage.totals.cost!!.compareTo(BigDecimal("0.42")))

        // 服务端返回的顺序是乱序，provider 负责按开始时间升序
        assertEquals(2, usage.buckets.size)
        assertEquals(Instant.parse("2026-10-23T00:00:00Z").epochSecond, usage.buckets[0].startEpochSecond)
        assertEquals(Instant.parse("2026-10-23T01:00:00Z").epochSecond, usage.buckets[0].endEpochSecond)
        assertTrue(usage.buckets[0].partial)
        assertEquals(14, usage.buckets[0].metrics.requestCount)
        assertFalse(usage.buckets[1].partial)
        assertEquals(20, usage.buckets[1].metrics.requestCount)

        assertEquals(2, usage.groups.size)
        assertEquals("llama3.2:latest", usage.groups[0].name)
        assertEquals(30, usage.groups[0].metrics.requestCount)
        assertEquals("qwen3:8b", usage.groups[1].name)
    }

    @Test
    fun `七天范围缺 granularity 时回退为天粒度`() {
        val body = """{"buckets":[]}"""
        val usage = runBlocking {
            provider(FakeTransport { QuotaHttpResponse(200, body) }).fetchUsage("sk-test", QuotaRange.LAST_7_DAYS)
        }!!
        assertEquals(QuotaGranularity.DAY, usage.granularity)
        assertEquals(0, usage.totals.requestCount)
        assertNull(usage.totals.inputTokens)
        assertTrue(usage.buckets.isEmpty())
        assertTrue(usage.groups.isEmpty())
    }

    @Test
    fun `缺 totals 时用分桶求和兜底`() {
        val body = """{"granularity":"day","buckets":[
            {"from":"2026-10-21T00:00:00Z","until":"2026-10-22T00:00:00Z","request_count":3,"usage_usd":0.1},
            {"from":"2026-10-22T00:00:00Z","until":"2026-10-23T00:00:00Z","request_count":4,"usage_usd":0.2}
        ]}"""
        val usage = runBlocking {
            provider(FakeTransport { QuotaHttpResponse(200, body) }).fetchUsage("sk-test", QuotaRange.LAST_7_DAYS)
        }!!
        assertEquals(7, usage.totals.requestCount)
        assertEquals(0, usage.totals.cost!!.compareTo(BigDecimal("0.3")))
    }

    @Test
    fun `用量请求带上范围与分组参数并复用同一把 key`() {
        val transport = FakeTransport { QuotaHttpResponse(200, """{"buckets":[]}""") }
        runBlocking { provider(transport).fetchUsage(" sk-usage ", QuotaRange.LAST_30_DAYS) }

        val url = transport.lastUrl!!
        assertTrue(url, url.startsWith("https://ollama.com/api/usage?"))
        assertTrue(url, url.contains("range=30d"))
        assertTrue(url, url.contains("scope=self"))
        assertTrue(url, url.contains("group_by=model"))
        assertEquals("Bearer sk-usage", transport.lastHeaders["Authorization"])
    }

    @Test
    fun `余额请求指向 balance 且带 Bearer`() {
        val transport = FakeTransport { QuotaHttpResponse(200, USD_BALANCE) }
        runBlocking { provider(transport).fetchItems("sk-test") }
        assertEquals("https://ollama.com/api/balance", transport.lastUrl)
        assertEquals("Bearer sk-test", transport.lastHeaders["Authorization"])
    }

    // ------------------------- 异常 -------------------------

    @Test
    fun `429 归为限流并带回退秒数`() {
        val transport = FakeTransport {
            QuotaHttpResponse(429, """{"error":"rate limit exceeded"}""", retryAfterSeconds = 30)
        }
        val error = failure(transport) { fetchItems("sk-test") }
        assertEquals(QuotaErrorKind.RATE_LIMITED, error.kind)
        assertEquals(30L, error.retryAfterSeconds)
    }

    @Test
    fun `401 归为凭据无效`() {
        val transport = FakeTransport { QuotaHttpResponse(401, """{"error":"unauthorized"}""") }
        assertEquals(QuotaErrorKind.UNAUTHORIZED, failure(transport) { fetchItems("sk-test") }.kind)
    }

    @Test
    fun `503 归为服务不可用`() {
        val transport = FakeTransport { QuotaHttpResponse(503, "<html>maintenance</html>") }
        val error = failure(transport) { fetchUsage("sk-test", QuotaRange.LAST_24_HOURS) }
        assertEquals(QuotaErrorKind.SERVICE_UNAVAILABLE, error.kind)
    }

    @Test
    fun `200 但不是 JSON 归为解析失败`() {
        val transport = FakeTransport { QuotaHttpResponse(200, "<html>not json</html>") }
        assertEquals(QuotaErrorKind.INVALID_RESPONSE, failure(transport) { fetchItems("sk-test") }.kind)
    }

    @Test
    fun `连接失败归为传输错误`() {
        val transport = FakeTransport { throw IOException("unexpected end of stream") }
        val error = failure(transport) { fetchItems("sk-test") }
        assertEquals(QuotaErrorKind.TRANSPORT, error.kind)
        assertNotNull(error.message)
    }

    @Test
    fun `空凭据不发请求`() {
        val transport = FakeTransport { QuotaHttpResponse(200, USD_BALANCE) }
        val error = failure(transport) { fetchItems("   ") }
        assertEquals(QuotaErrorKind.MISSING_CREDENTIAL, error.kind)
        assertEquals(0, transport.calls)
    }

    @Test
    fun `错误消息不回显凭据原文`() {
        val transport = FakeTransport { QuotaHttpResponse(401, """{"error":"bad key sk-secret-123"}""") }
        val error = failure(transport) { fetchItems("sk-secret-123") }
        assertFalse(error.message.orEmpty().contains("sk-secret-123"))
        assertFalse(transport.lastUrl.orEmpty().contains("sk-secret-123"))
    }

    private companion object {
        val USD_BALANCE = """
            {
              "included": {
                "balance_usd": 12.34,
                "allowance_usd": 20.0,
                "period": {"from": "2026-09-15T00:00:00Z", "until": "2026-10-15T00:00:00Z"}
              },
              "purchased": {"balance_usd": 3.5}
            }
        """.trimIndent()

        val PERCENT_BALANCE = """
            {
              "included": {
                "session": {"remaining_percent": 42, "resets_at": "2026-10-23T10:00:00Z"},
                "weekly": {"remaining_percent": 0, "resets_at": "2026-10-26T00:00:00Z"}
              }
            }
        """.trimIndent()

        val USAGE_24H = """
            {
              "range": "24h",
              "scope": "self",
              "granularity": "hour",
              "from": "2026-10-22T23:00:00Z",
              "until": "2026-10-23T23:00:00Z",
              "totals": {
                "request_count": 34, "usage_usd": 0.42,
                "input_tokens": 1200, "cached_input_tokens": 800, "output_tokens": 300
              },
              "buckets": [
                {"from": "2026-10-23T01:00:00Z", "until": "2026-10-23T02:00:00Z", "partial": false,
                 "request_count": 20, "usage_usd": 0.2, "input_tokens": 700,
                 "cached_input_tokens": 500, "output_tokens": 200},
                {"from": "2026-10-23T00:00:00Z", "until": "2026-10-23T01:00:00Z", "partial": true,
                 "request_count": 14, "usage_usd": 0.22, "input_tokens": 500,
                 "cached_input_tokens": 300, "output_tokens": 100}
              ],
              "groups": [
                {"model": "llama3.2:latest", "request_count": 30, "usage_usd": 0.4,
                 "input_tokens": 1100, "cached_input_tokens": 700, "output_tokens": 280},
                {"model": "qwen3:8b", "request_count": 4, "usage_usd": 0.02,
                 "input_tokens": 100, "cached_input_tokens": 100, "output_tokens": 20}
              ]
            }
        """.trimIndent()
    }
}
