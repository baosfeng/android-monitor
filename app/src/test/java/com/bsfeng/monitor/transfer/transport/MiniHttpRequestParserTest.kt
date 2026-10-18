package com.bsfeng.monitor.transfer.transport

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream

/**
 * [HttpRequestParser] / [TransferRouter] 测试（纯 JVM，直接喂字节流，不需要真 socket）。
 *
 * 覆盖协议 §4 的五个路由、请求行/头/query 解析，以及未定义行为（非法请求行、超长头、
 * `Content-Length` 非数字）返回 null。
 *
 * 用 Robolectric 只是为了拿到真实 `org.json`（[ApiResponse] 断言），解析本身不依赖 Android。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MiniHttpRequestParserTest {

    private fun head(raw: String) = HttpRequestParser.parseHead(
        ByteArrayInputStream(raw.toByteArray(Charsets.UTF_8)),
    )

    @Test
    fun `解析标准 GET 请求行与 query`() {
        val parsed = head(
            "GET /api/v1/offer/status?session=abc&file=f1 HTTP/1.1\r\n" +
                "Host: 192.168.1.10:53319\r\n" +
                "X-Transfer-Token: deadbeef\r\n" +
                "\r\n",
        )
        requireNotNull(parsed)
        assertEquals("GET", parsed.method)
        assertEquals("/api/v1/offer/status", parsed.path)
        assertEquals("abc", parsed.query["session"])
        assertEquals("f1", parsed.query["file"])
        assertEquals("192.168.1.10:53319", parsed.header("Host"))
        // 头名大小写不敏感
        assertEquals("deadbeef", parsed.header("x-transfer-token"))
        assertEquals(0L, parsed.contentLength)
    }

    @Test
    fun `解析 PUT 上传请求并保留 content-length`() {
        val parsed = head(
            "PUT /api/v1/upload?session=s1&file=f1&offset=1024 HTTP/1.1\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "X-Transfer-Token: tok\r\n" +
                "Content-Length: 2048\r\n" +
                "\r\n",
        )
        requireNotNull(parsed)
        assertEquals("PUT", parsed.method)
        assertEquals("/api/v1/upload", parsed.path)
        assertEquals("s1", parsed.query["session"])
        assertEquals("f1", parsed.query["file"])
        assertEquals("1024", parsed.query["offset"])
        assertEquals(2048L, parsed.contentLength)
        assertEquals("tok", parsed.header("X-Transfer-Token"))
    }

    @Test
    fun `query 值按 URL 解码 支持中文文件名`() {
        val parsed = head(
            "PUT /api/v1/upload?file=%E7%85%A7%E7%89%87.jpg&offset=0 HTTP/1.1\r\n\r\n",
        )
        requireNotNull(parsed)
        assertEquals("照片.jpg", parsed.query["file"])
    }

    @Test
    fun `parseHead 不消费请求体 后续字节仍可读`() {
        val stream = ByteArrayInputStream(
            ("POST /api/v1/offer HTTP/1.1\r\nContent-Length: 5\r\n\r\nhello" +
                "trailing").toByteArray(Charsets.UTF_8),
        )
        val parsed = HttpRequestParser.parseHead(stream)
        requireNotNull(parsed)
        assertEquals(5L, parsed.contentLength)
        val body = stream.readBytes()
        assertEquals("hellotrailing", String(body, Charsets.UTF_8))
    }

    @Test
    fun `缺少空行或流结束时返回 null`() {
        assertNull(head("GET /api/v1/info HTTP/1.1\r\nHost: x\r\n"))
        assertNull(head(""))
    }

    @Test
    fun `非法请求行返回 null`() {
        assertNull(head("GET\r\n\r\n"))
        assertNull(head("GET /api/v1/info\r\n\r\n"))
        assertNull(head("GET /api/v1/info HTTP/2.0\r\n\r\n"))
        assertNull(head("TRACE /api/v1/info HTTP/1.1\r\n\r\n"))
        assertNull(head("GET api/v1/info HTTP/1.1\r\n\r\n"))
    }

    @Test
    fun `content-length 非数字返回 null`() {
        assertNull(head("PUT /api/v1/upload HTTP/1.1\r\nContent-Length: abc\r\n\r\n"))
        assertNull(head("PUT /api/v1/upload HTTP/1.1\r\nContent-Length: -5\r\n\r\n"))
    }

    @Test
    fun `请求头超过上限返回 null`() {
        val huge = "GET /api/v1/info HTTP/1.1\r\nX-Big: " + "A".repeat(HttpRequestParser.MAX_HEAD_BYTES) + "\r\n\r\n"
        assertNull(head(huge))
    }

    @Test
    fun `重复请求头取首次出现值`() {
        val parsed = head(
            "GET /api/v1/info HTTP/1.1\r\nX-Pair-Key: first\r\nX-Pair-Key: second\r\n\r\n",
        )
        requireNotNull(parsed)
        assertEquals("first", parsed.header("X-Pair-Key"))
    }

    @Test
    fun `query 参数缺失回落默认值`() {
        val parsed = head("PUT /api/v1/upload?session=s1 HTTP/1.1\r\n\r\n")
        requireNotNull(parsed)
        assertEquals("0", parsed.queryParam("offset", "0"))
        assertEquals("", parsed.queryParam("file", ""))
    }

    @Test
    fun `五个协议路由都能匹配 未知路由返回 null`() {
        assertEquals(TransferRoute.INFO, TransferRouter.match("/api/v1/info"))
        assertEquals(TransferRoute.OFFER, TransferRouter.match("/api/v1/offer"))
        assertEquals(TransferRoute.OFFER_STATUS, TransferRouter.match("/api/v1/offer/status"))
        assertEquals(TransferRoute.UPLOAD, TransferRouter.match("/api/v1/upload"))
        assertEquals(TransferRoute.CANCEL, TransferRouter.match("/api/v1/cancel"))
        // 末尾多余斜杠容忍
        assertEquals(TransferRoute.INFO, TransferRouter.match("/api/v1/info/"))
        assertNull(TransferRouter.match("/api/v1/unknown"))
        assertNull(TransferRouter.match("/api/v2/info"))
        assertNull(TransferRouter.match("info"))
        assertNull(TransferRouter.match(""))
    }

    @Test
    fun `LimitedInputStream 只读声明长度`() {
        val source = ByteArrayInputStream("0123456789".toByteArray())
        val limited = LimitedInputStream(source, 4L)
        val read = ByteArray(16)
        val count = limited.read(read, 0, read.size)
        assertEquals(4, count)
        assertEquals("0123", String(read, 0, count))
        assertEquals(0L, limited.remaining())
        assertEquals(-1, limited.read())
    }

    @Test
    fun `ApiResponse 解析与错误码`() {
        val ok = ApiResponse.parse("""{"code":0,"message":"成功","data":{"sessionId":"s1"}}""")
        requireNotNull(ok)
        assertTrue(ok.isOk)
        assertEquals("s1", ok.string("sessionId"))

        assertNull(ApiResponse.parse("not json"))
        assertNull(ApiResponse.parse("""{"message":"缺 code"}"""))
        assertNull(ApiResponse.parse(""))
        assertEquals(4005, ApiResponse.parse("""{"code":4005,"message":"x"}""")?.code)
    }
}
