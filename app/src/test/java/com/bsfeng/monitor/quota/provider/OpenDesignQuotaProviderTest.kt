package com.bsfeng.monitor.quota.provider

import com.bsfeng.monitor.domain.quota.QuotaError
import com.bsfeng.monitor.domain.quota.QuotaErrorKind
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaUnit
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigDecimal

/** open-design：Cookie 鉴权、窗口百分比换算、错误归一。全部走假 transport。 */
class OpenDesignQuotaProviderTest {

    private class RoutingTransport(
        private val workspaces: (String) -> QuotaHttpResponse = { QuotaHttpResponse(200, WORKSPACES) },
        private val usage: (String) -> QuotaHttpResponse = { QuotaHttpResponse(200, USAGE) },
    ) : QuotaTransport {
        val urls = mutableListOf<String>()
        var headers: Map<String, String> = emptyMap()

        override fun get(url: String, headers: Map<String, String>): QuotaHttpResponse {
            urls += url
            this.headers = headers
            return if (url.endsWith("/api/v1/workspaces")) workspaces(url) else usage(url)
        }
    }

    private fun provider(transport: QuotaTransport) = OpenDesignQuotaProvider(http = QuotaHttp(transport))

    private fun parseJson(body: String): JsonNode = QuotaJson.parse(body)!!

    // ------------------------- 解析 -------------------------

    @Test
    fun `窗口按剩余百分比换算并把 5 小时排在最前`() {
        val items = OpenDesignQuotaProvider.parseUsageWindows(parseJson(USAGE))

        assertEquals(2, items.size)
        val first = items[0]
        assertEquals(QuotaKind.PERCENT, first.kind)
        assertEquals(QuotaUnit.PERCENT, first.unit)
        assertEquals("5 小时额度", first.title)
        // 49257 / 50000 = 98.514%
        assertEquals(0, first.remaining.compareTo(BigDecimal("98.51")))
        assertTrue("5 小时窗口应为主项", first.primary)
        // 接口给的原生口径要露出来：credits 用量 + 档位
        assertEquals("Go", first.badge)
        assertTrue(first.detailText!!.contains("743 / 50000 credits"))
        // 明细里不放格式化后的时刻：那玩意会随时区/缓存固化，重置时间由 resetsAt 单独渲染
        assertFalse(first.detailText!!.contains("窗口起"))
        assertNull("次级窗口不带档位标", items[1].badge)

        val weekly = items[1]
        assertEquals("每周额度", weekly.title)
        assertEquals(0, weekly.remaining.compareTo(BigDecimal("48.67")))
        assertNotNull(weekly.resetsAtEpochSecond)
    }

    @Test
    fun `空窗口返回空列表而不是抛错`() {
        assertTrue(OpenDesignQuotaProvider.parseUsageWindows(parseJson("""{"windows":[]}""")).isEmpty())
        assertTrue(OpenDesignQuotaProvider.parseUsageWindows(parseJson("""{}""")).isEmpty())
    }

    @Test
    fun `缺剩余字段时用 limit 减 used 兜底`() {
        val json = """{"windows":[{"durationSeconds":18000,"usedCredits":"1000",
            "limitCredits":"5000","resetsAt":"2026-10-23T20:02:22.830Z"}]}"""
        val items = OpenDesignQuotaProvider.parseUsageWindows(parseJson(json))
        assertEquals(1, items.size)
        assertEquals(0, items[0].remaining.compareTo(BigDecimal("80.00")))
    }

    @Test
    fun `limit 为零时跳过而不是除零`() {
        val json = """{"windows":[{"durationSeconds":18000,"usedCredits":"0",
            "limitCredits":"0","remainingCredits":"0"}]}"""
        assertTrue(OpenDesignQuotaProvider.parseUsageWindows(parseJson(json)).isEmpty())
    }

    // ------------------------- 请求 -------------------------

    @Test
    fun `先查 workspace 再用 Cookie 取额度`() {
        val transport = RoutingTransport()
        val items = runBlocking { provider(transport).fetchItems("session-token-value") }

        assertEquals(2, transport.urls.size)
        assertEquals("https://amr-api.open-design.ai/api/v1/workspaces", transport.urls[0])
        assertEquals(
            "https://amr-api.open-design.ai/api/v1/workspaces/fbpzndory1pwn0jgb6r68ed8/billing/coding-plan-usage",
            transport.urls[1],
        )
        assertEquals(2, items.size)
        // 只给了 token 值时补成完整 Cookie
        assertTrue(transport.headers["Cookie"]!!.startsWith("__Secure-better-auth.session_token="))
        assertFalse("不能带 Dalvik UA", transport.headers["User-Agent"]!!.startsWith("Dalvik"))
    }

    @Test
    fun `完整 Cookie 原样透传`() {
        val transport = RoutingTransport()
        runBlocking {
            provider(transport).fetchItems("__Secure-better-auth.session_token=abc; od_attr=xyz")
        }
        assertEquals("__Secure-better-auth.session_token=abc; od_attr=xyz", transport.headers["Cookie"])
    }

    // ------------------------- 错误 -------------------------

    @Test
    fun `401 归为凭据无效`() {
        val transport = RoutingTransport(workspaces = { QuotaHttpResponse(401, """{"error":"unauthenticated"}""") })
        val thrown = runCatching { runBlocking { provider(transport).fetchItems("x") } }.exceptionOrNull()
        assertTrue(thrown is QuotaError)
        assertEquals(QuotaErrorKind.UNAUTHORIZED, (thrown as QuotaError).kind)
    }

    @Test
    fun `没有 workspace 时给一句人话而不是解析失败`() {
        val transport = RoutingTransport(workspaces = { QuotaHttpResponse(200, """{"items":[]}""") })
        val thrown = runCatching { runBlocking { provider(transport).fetchItems("x") } }.exceptionOrNull()
        assertTrue(thrown is QuotaError)
        assertEquals(QuotaErrorKind.FORBIDDEN, (thrown as QuotaError).kind)
    }

    private companion object {
        val WORKSPACES = """
            {"items":[{"workspaceId":"fbpzndory1pwn0jgb6r68ed8","workspaceName":"demo"}]}
        """.trimIndent()

        val USAGE = """
            {"workspaceId":"fbpzndory1pwn0jgb6r68ed8","eligible":true,"tier":"go",
             "windows":[
               {"durationSeconds":18000,"usedCredits":"743","limitCredits":"50000",
                "remainingCredits":"49257","resetsAt":"2026-10-23T20:02:22.830Z"},
               {"durationSeconds":604800,"usedCredits":"76998","limitCredits":"150000",
                "remainingCredits":"73002","resetsAt":"2026-10-27T12:02:22.830Z"}
             ]}
        """.trimIndent()
    }
}
