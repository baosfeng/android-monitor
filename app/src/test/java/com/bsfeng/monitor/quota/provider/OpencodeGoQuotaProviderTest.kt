package com.bsfeng.monitor.quota.provider

import com.bsfeng.monitor.domain.quota.QuotaError
import com.bsfeng.monitor.domain.quota.QuotaErrorKind
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.domain.quota.QuotaUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** opencode-go：三窗口已用比例 → 剩余、新旧字段名兼容、错误归一。全部走假 transport。 */
class OpencodeGoQuotaProviderTest {

    private class FakeTransport(
        private val response: () -> QuotaHttpResponse = { QuotaHttpResponse(200, USAGE_BODY) },
    ) : QuotaTransport {
        val urls = mutableListOf<String>()
        var headers: Map<String, String> = emptyMap()

        override fun get(url: String, headers: Map<String, String>): QuotaHttpResponse {
            urls += url
            this.headers = headers
            return response()
        }
    }

    private fun provider(transport: QuotaTransport, now: Long = NOW) =
        OpencodeGoQuotaProvider(http = QuotaHttp(transport), nowEpochSecond = { now })

    private fun failure(transport: QuotaTransport, credential: String = "sk-test"): QuotaError {
        val thrown = runCatching { runBlocking { provider(transport).fetchItems(credential) } }
            .exceptionOrNull()
        assertTrue("期望 QuotaError，实际 $thrown", thrown is QuotaError)
        return thrown as QuotaError
    }

    private fun parse(body: String, now: Long = NOW): List<com.bsfeng.monitor.domain.quota.QuotaItem> =
        OpencodeGoQuotaProvider.parseUsage(QuotaJson.parse(body)!!, now)

    // ------------------------- 解析 -------------------------

    @Test
    fun `三个窗口把已用比例换成剩余且五小时为主项`() {
        val items = parse(USAGE_BODY)

        assertEquals(listOf("5 小时额度", "每周额度", "每月额度"), items.map { it.title })
        assertEquals(listOf("window:rolling", "window:weekly", "window:monthly"), items.map { it.id })
        items.forEach {
            assertEquals(QuotaKind.PERCENT, it.kind)
            assertEquals(QuotaUnit.PERCENT, it.unit)
            assertNull("百分比额度没有绝对总量", it.total)
        }
        // 12.3 / 45.6 / 78.9 是「已用」，剩余分别是 87.7 / 54.4 / 21.1
        assertEquals(0, items[0].remaining.compareTo(BigDecimal("87.7")))
        assertEquals(0, items[1].remaining.compareTo(BigDecimal("54.4")))
        assertEquals(0, items[2].remaining.compareTo(BigDecimal("21.1")))

        assertEquals(listOf(true, false, false), items.map { it.primary })
        // 2026-08-16T20:00:00Z / 2026-08-20T00:00:00Z / 2026-09-01T00:00:00Z
        assertEquals(1_786_910_400L, items[0].resetsAtEpochSecond)
        assertEquals(1_787_184_000L, items[1].resetsAtEpochSecond)
        assertEquals(1_788_220_800L, items[2].resetsAtEpochSecond)
        assertNull("正常窗口不带角标", items[0].badge)
    }

    @Test
    fun `已用 100 时剩余为 0 且不算余额型`() {
        val items = parse("""{"usage":{"rolling":{"status":"ok","percent":100}}}""")

        assertEquals(1, items.size)
        assertEquals(0, items[0].remaining.compareTo(BigDecimal.ZERO))
        assertEquals(0f, items[0].remainingFraction)
        assertFalse("百分比限额不是余额型", items[0].isPlainBalance)
    }

    @Test
    fun `rate-limited 窗口剩余置 0 并带已限流角标`() {
        val items = parse(
            """{"usage":{"rolling":{"status":"rate-limited","percent":100,"resetsAt":"2026-08-16T20:00:00Z"},
               "weekly":{"status":"ok","percent":45.6,"resetsAt":"2026-08-20T00:00:00Z"}}}""",
        )

        assertEquals(2, items.size)
        assertEquals(0, items[0].remaining.compareTo(BigDecimal.ZERO))
        assertEquals("已限流", items[0].badge)
        // 已限流时百分比本身也告警，但拿到的 percent 是脏数据（例如 0）也照样按耗尽算
        assertNull(items[1].badge)
    }

    @Test
    fun `已限流窗口即使 percent 落后于真实用量也按耗尽处理`() {
        val items = parse("""{"usage":{"monthly":{"status":"rate-limited","percent":3}}}""")

        assertEquals(0, items[0].remaining.compareTo(BigDecimal.ZERO))
        assertEquals("已限流", items[0].badge)
        assertEquals(0f, items[0].remainingFraction)
    }

    @Test
    fun `旧字段名 rollingUsage 与 usagePercent 和 resetInSec 也认`() {
        val items = parse(
            """{"rollingUsage":{"usagePercent":12.3,"resetInSec":3600},
               "weeklyUsage":{"usagePercent":45.6,"resetInSec":7200},
               "monthlyUsage":{"usagePercent":78.9,"resetInSec":10800}}""",
        )

        assertEquals(listOf("5 小时额度", "每周额度", "每月额度"), items.map { it.title })
        assertEquals(0, items[0].remaining.compareTo(BigDecimal("87.7")))
        assertEquals(0, items[1].remaining.compareTo(BigDecimal("54.4")))
        assertEquals(0, items[2].remaining.compareTo(BigDecimal("21.1")))
        // resetInSec 是相对秒数：按抓取时刻换算成绝对时刻
        assertEquals(NOW + 3_600L, items[0].resetsAtEpochSecond)
        assertEquals(NOW + 10_800L, items[2].resetsAtEpochSecond)
    }

    @Test
    fun `顶层平铺与包在 usage 里两种形态等价`() {
        val nested = parse("""{"usage":{"rolling":{"percent":12.3,"resetsAt":"2026-08-16T20:00:00Z"}}}""")
        val flat = parse("""{"rolling":{"percent":12.3,"resetsAt":"2026-08-16T20:00:00Z"}}""")

        assertEquals(nested, flat)
    }

    @Test
    fun `缺一个窗口只返回两条不整块报错`() {
        val items = parse(
            """{"usage":{"rolling":{"percent":12.3},"monthly":{"percent":78.9}}}""",
        )

        assertEquals(2, items.size)
        assertEquals(listOf("5 小时额度", "每月额度"), items.map { it.title })
        assertEquals(listOf(true, false), items.map { it.primary })
    }

    @Test
    fun `五小时窗口缺失时主项退给第一个存在的窗口`() {
        val items = parse("""{"usage":{"weekly":{"percent":45.6},"monthly":{"percent":78.9}}}""")

        assertEquals(listOf("每周额度", "每月额度"), items.map { it.title })
        assertEquals(listOf(true, false), items.map { it.primary })
    }

    @Test
    fun `三个窗口全缺抛解析失败`() {
        val thrown = runCatching { parse("""{"usage":{}}""") }.exceptionOrNull()

        assertTrue(thrown is QuotaError)
        assertEquals(QuotaErrorKind.INVALID_RESPONSE, (thrown as QuotaError).kind)
    }

    @Test
    fun `没有 percent 字段的窗口视为缺失`() {
        val thrown = runCatching {
            parse("""{"usage":{"rolling":{"status":"ok"},"weekly":{"percent":45.6}}}""")
        }.exceptionOrNull()

        assertNull("还有一条可用就不该整块失败", thrown)
        assertEquals(1, parse("""{"usage":{"rolling":{"status":"ok"},"weekly":{"percent":45.6}}}""").size)
    }

    @Test
    fun `percent 越界夹到 0 与 100`() {
        val negative = parse("""{"usage":{"rolling":{"percent":-10}}}""")
        val over = parse("""{"usage":{"rolling":{"percent":150}}}""")

        // -10%（已用为负）说明数据脏：剩余按上限 100 处理
        assertEquals(0, negative[0].remaining.compareTo(BigDecimal("100")))
        // 已用 150%：剩余 0
        assertEquals(0, over[0].remaining.compareTo(BigDecimal.ZERO))
        assertEquals(0f, over[0].remainingFraction)
    }

    // ------------------------- 请求 -------------------------

    @Test
    fun `请求地址与请求头正确`() {
        val transport = FakeTransport()
        val items = runBlocking { provider(transport).fetchItems("  Bearer sk-test\n") }

        assertEquals(listOf("https://opencode.ai/zen/go/v1/usage"), transport.urls)
        assertEquals("Bearer sk-test", transport.headers["Authorization"])
        assertTrue("UA 不能为空", transport.headers["User-Agent"].orEmpty().isNotBlank())
        assertFalse("不能带 Dalvik UA", transport.headers["User-Agent"]!!.startsWith("Dalvik"))
        assertEquals(3, items.size)
    }

    @Test
    fun `不提供用量明细`() {
        val transport = FakeTransport()
        val usage = runBlocking { provider(transport).fetchUsage("sk-test", QuotaRange.LAST_7_DAYS) }

        assertNull("接口没有用量数据，固定返回 null", usage)
        assertTrue("没有用量接口就不该发请求", transport.urls.isEmpty())
        assertFalse(provider(transport).supportsUsage)
        assertFalse(provider(transport).supportsModelBreakdown)
    }

    @Test
    fun `provider 元信息`() {
        val provider = provider(FakeTransport())

        assertEquals("opencode-go", provider.id)
        assertEquals("opencode-go", provider.displayName)
        assertEquals(com.bsfeng.monitor.domain.quota.QuotaCredentialKind.API_KEY, provider.balanceCredentialKind)
        assertNull(provider.usageCredentialKind)
    }

    /**
     * 刷新间隔默认值走 [com.bsfeng.monitor.quota.QuotaSettings.defaultMinutesFor]：
     * opencode-go 没公布限流，默认比其它 provider 松一档。
     */
    @Test
    fun `默认刷新间隔 opencode-go 更保守且是可选档位`() {
        assertEquals(5, com.bsfeng.monitor.quota.QuotaSettings.defaultMinutesFor("opencode-go"))
        assertEquals(1, com.bsfeng.monitor.quota.QuotaSettings.defaultMinutesFor("ollama"))
        assertEquals(1, com.bsfeng.monitor.quota.QuotaSettings.DEFAULT_REFRESH_MINUTES)
        assertTrue(
            "默认值必须出现在设置页可选档位里，否则 chip 上没有选中项",
            com.bsfeng.monitor.quota.QuotaSettings.defaultMinutesFor("opencode-go") in
                com.bsfeng.monitor.quota.QuotaSettings.MINUTE_OPTIONS,
        )
        assertTrue(15 in com.bsfeng.monitor.quota.QuotaSettings.MINUTE_OPTIONS)
    }

    // ------------------------- 错误 -------------------------

    @Test
    fun `401 归为凭据无效`() {
        val transport = FakeTransport {
            QuotaHttpResponse(401, """{"type":"error","error":{"type":"AuthError","message":"Unauthorized"}}""")
        }

        assertEquals(QuotaErrorKind.UNAUTHORIZED, failure(transport).kind)
    }

    @Test
    fun `403 换成人话提示没有 Go 订阅`() {
        val transport = FakeTransport {
            QuotaHttpResponse(
                403,
                """{"type":"error","error":{"type":"EntitlementError",
                   "message":"OpenCode Go subscription required."}}""",
            )
        }

        val error = failure(transport)
        assertEquals(QuotaErrorKind.FORBIDDEN, error.kind)
        assertEquals("该 Key 没有 OpenCode Go 订阅", error.userMessage)
    }

    @Test
    fun `429 归为限流且保留退避秒数`() {
        val transport = FakeTransport { QuotaHttpResponse(429, "", retryAfterSeconds = 30L) }

        val error = failure(transport)
        assertEquals(QuotaErrorKind.RATE_LIMITED, error.kind)
        assertEquals(30L, error.retryAfterSeconds)
    }

    @Test
    fun `5xx 归为服务不可用且不误报成订阅问题`() {
        val transport = FakeTransport { QuotaHttpResponse(503, "upstream unavailable") }

        assertEquals(QuotaErrorKind.SERVICE_UNAVAILABLE, failure(transport).kind)
    }

    @Test
    fun `空凭据不发请求直接报缺少凭据`() {
        val transport = FakeTransport()

        assertEquals(QuotaErrorKind.MISSING_CREDENTIAL, failure(transport, "   ").kind)
        assertTrue(transport.urls.isEmpty())
    }

    private companion object {
        /** 固定「当前时刻」，让 resetInSec 的换算结果可断言。 */
        const val NOW = 1_768_000_000L

        val USAGE_BODY = """
            {"usage":{
              "rolling":{"status":"ok","percent":12.3,"resetsAt":"2026-08-16T20:00:00Z"},
              "weekly":{"status":"ok","percent":45.6,"resetsAt":"2026-08-20T00:00:00Z"},
              "monthly":{"status":"ok","percent":78.9,"resetsAt":"2026-09-01T00:00:00Z"}
            }}
        """.trimIndent()
    }
}
