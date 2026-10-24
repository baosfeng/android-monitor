package com.bsfeng.monitor.quota.provider

import com.bsfeng.monitor.domain.quota.QuotaError
import com.bsfeng.monitor.domain.quota.QuotaErrorKind
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** 一次 GET 的归一化结果：错误体也保留，供上层从 `{"error":...}` 里取原因。 */
data class QuotaHttpResponse(
    val statusCode: Int,
    val body: String = "",
    /** 429 的 `Retry-After`（秒，已归一为 0..3600）；服务端没给为 null。 */
    val retryAfterSeconds: Long? = null,
)

/**
 * 传输层抽象：生产用 [UrlConnectionTransport]，单测注入假实现，两个 provider 因此可以纯 JVM 测试。
 *
 * 抛 [IOException] 表示连接 / 读取失败（DNS、超时、断网）。
 */
fun interface QuotaTransport {
    fun get(url: String, headers: Map<String, String>): QuotaHttpResponse
}

/** `HttpURLConnection` 实现，风格与 `UpdateChecker` 一致（系统内置、零依赖）。 */
object UrlConnectionTransport : QuotaTransport {

    override fun get(url: String, headers: Map<String, String>): QuotaHttpResponse {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = "GET"
            connection.instanceFollowRedirects = true
            connection.setRequestProperty("Accept", "application/json")
            headers.forEach { (name, value) -> connection.setRequestProperty(name, value) }
            val status = connection.responseCode
            val stream = if (status in 200..299) connection.inputStream else connection.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            return QuotaHttpResponse(status, body, parseRetryAfterHeader(connection.getHeaderField("Retry-After")))
        } finally {
            connection.disconnect()
        }
    }

    private const val CONNECT_TIMEOUT_MS = 10_000
    private const val READ_TIMEOUT_MS = 20_000
}

/**
 * 额度的共享 HTTP 封装：GET + 请求头 + 超时 + 状态码 / 异常归一成 [QuotaError]。
 *
 * 口径：
 * - 2xx 才继续；401 / 403 / 404 / 429 / 5xx 分别映成 [QuotaErrorKind]，其余 4xx 归 INVALID_PARAM；
 * - 429 读 `Retry-After`（秒或 HTTP-date，夹到 0..3600 秒），供调用方退避；
 * - 正文不是合法 JSON → INVALID_RESPONSE；连接失败 → TRANSPORT；
 * - 任何异常消息与 URL 都不含凭据原文（凭据只走请求头，不打印、不拼进消息）。
 */
class QuotaHttp(
    private val transport: QuotaTransport = UrlConnectionTransport,
    /** 日志出口；默认丢弃，装配层注入 logcat（直接写 android.util.Log 会让纯 JVM 单测炸）。 */
    private val log: (String) -> Unit = {},
) {

    /** GET 并解析 JSON；HTTP 错误或正文非法直接抛 [QuotaError]。 */
    fun getJson(url: String, headers: Map<String, String>): JsonNode =
        parseBody(execute(url, headers).body)

    /** GET 只取正文（DeepSeek 平台接口的错误恒为 HTTP 200，需要上层按 body 判断）。 */
    fun getText(url: String, headers: Map<String, String>): String = execute(url, headers).body

    /** 执行请求并按状态码归一；2xx 原样返回（含正文）。 */
    fun execute(url: String, headers: Map<String, String>): QuotaHttpResponse {
        val response = try {
            transport.get(url, headers)
        } catch (e: IOException) {
            log("GET ${safeUrl(url)} 失败：${e.javaClass.simpleName} ${e.message.orEmpty()}")
            throw QuotaError(QuotaErrorKind.TRANSPORT, e.summary())
        } catch (e: RuntimeException) {
            // 非法 URL 之外的运行时异常（例如被中断）同样归为传输失败，不向上层泄漏堆栈
            log("GET ${safeUrl(url)} 异常：${e.javaClass.simpleName} ${e.message.orEmpty()}")
            throw QuotaError(QuotaErrorKind.TRANSPORT, e.summary())
        }
        log("GET ${safeUrl(url)} -> ${response.statusCode} ${response.body.length}B")
        if (response.statusCode !in 200..299) throw statusError(response, bearerToken(headers))
        return response
    }

    private fun parseBody(body: String): JsonNode =
        QuotaJson.parse(body) ?: throw QuotaError(QuotaErrorKind.INVALID_RESPONSE, "响应不是合法 JSON")

    private fun statusError(response: QuotaHttpResponse, credential: String?): QuotaError {
        val kind = when (val status = response.statusCode) {
            401 -> QuotaErrorKind.UNAUTHORIZED
            403 -> QuotaErrorKind.FORBIDDEN
            404 -> QuotaErrorKind.INVALID_PARAM
            429 -> QuotaErrorKind.RATE_LIMITED
            in 500..599 -> QuotaErrorKind.SERVICE_UNAVAILABLE
            else -> if (status in 400..499) QuotaErrorKind.INVALID_PARAM else QuotaErrorKind.SERVICE_UNAVAILABLE
        }
        // 服务端可能把 key 回显在错误体里：进消息前先打码，避免日志 / 界面泄漏
        val detail = redact(errorMessage(response.body) ?: "HTTP ${response.statusCode}", credential)
        return QuotaError(
            kind = kind,
            message = detail,
            retryAfterSeconds = if (kind == QuotaErrorKind.RATE_LIMITED) response.retryAfterSeconds else null,
        )
    }

    /** 日志里只留 host + path：查询串带时间范围，也避免以后有人把凭据拼进 URL。 */
    private fun safeUrl(url: String): String =
        runCatching {
            val u = java.net.URI(url)
            "${u.host}${u.path}"
        }.getOrDefault("<invalid-url>")

    companion object {

        /** Bearer 请求头；[userAgent] 非空时一并带上（DeepSeek 平台接口缺 UA 会被 WAF 挡成 429）。 */
        fun bearer(credential: String, userAgent: String? = null): Map<String, String> = buildMap {
            put("Authorization", "Bearer $credential")
            put("Accept", "application/json")
            if (!userAgent.isNullOrBlank()) put("User-Agent", userAgent)
        }

        /** Cookie 请求头：open-design 这类控制台内部接口只认 Cookie，不认 Bearer。 */
        fun cookie(credential: String, userAgent: String? = null): Map<String, String> = buildMap {
            put("Cookie", normalizeCookie(credential))
            put("Accept", "application/json")
            if (!userAgent.isNullOrBlank()) put("User-Agent", userAgent)
        }

        /**
         * 归一化 Cookie：用户可能贴整段 `a=b; c=d`，也可能只贴 session token 值。
         *
         * 只有值、没有 `=` 时补成 `__Secure-better-auth.session_token=值` —— 这是 open-design 唯一需要的那个；
         * 顺带去掉误贴的 `Cookie:` 前缀与换行。
         */
        fun normalizeCookie(raw: String): String {
            val text = raw.trim()
                .removePrefix("Cookie:")
                .replace("\r", "")
                .replace("\n", " ")
                .trim()
            if (text.isEmpty()) throw QuotaError(QuotaErrorKind.MISSING_CREDENTIAL)
            return if (text.contains('=')) text
            else "__Secure-better-auth.session_token=$text"
        }

        /** 去空白、去掉用户误贴的 `Bearer ` 前缀；空凭据直接抛 MISSING_CREDENTIAL。 */
        fun normalizeCredential(raw: String): String {
            val trimmed = raw.trim().replace(BEARER_PREFIX, "").trim()
            if (trimmed.isEmpty()) throw QuotaError(QuotaErrorKind.MISSING_CREDENTIAL)
            return trimmed
        }

        /** 从错误体里取一行原因：优先 JSON 的 error/message/msg，否则压平原文。 */
        fun errorMessage(body: String): String? {
            val node = QuotaJson.parse(body)
            val fromJson = node?.str("error") ?: node?.str("message") ?: node?.str("msg")
            return QuotaJson.oneLine(fromJson ?: body)
        }

        /** 把文本里出现的凭据原文替换成 `****`；凭据为空时原样返回。 */
        fun redact(text: String, credential: String?): String {
            val secret = credential?.trim().orEmpty()
            return if (secret.isEmpty()) text else text.replace(secret, MASKED)
        }

        /** 取请求头里的 Bearer 值（用于打码，不用于任何展示）。 */
        fun bearerToken(headers: Map<String, String>): String? = headers["Authorization"]
            ?.trim()
            ?.replace(BEARER_PREFIX, "")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

        /** `Retry-After` 支持秒数与 HTTP-date；超出 1 小时按 1 小时封顶，异常值返回 null。 */
        fun parseRetryAfter(raw: String?, nowMillis: Long = System.currentTimeMillis()): Long? =
            parseRetryAfterHeader(raw, nowMillis)

        private val BEARER_PREFIX = Regex("^bearer\\s+", RegexOption.IGNORE_CASE)
        private const val MASKED = "****"

        private fun Throwable.summary(): String =
            QuotaJson.oneLine(message?.takeIf { it.isNotBlank() } ?: javaClass.simpleName) ?: "网络异常"
    }
}

/** `Retry-After` 归一：秒数原样（夹到 0..3600），HTTP-date 换算成剩余秒数。 */
internal fun parseRetryAfterHeader(raw: String?, nowMillis: Long = System.currentTimeMillis()): Long? {
    val text = raw?.trim().orEmpty()
    if (text.isEmpty()) return null
    text.toLongOrNull()?.let { return it.coerceIn(0L, MAX_RETRY_AFTER_SECONDS) }
    val deadline = runCatching {
        ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME)
    }.getOrNull() ?: return null
    val delta = deadline.toEpochSecond() - nowMillis / 1000L
    return delta.coerceIn(1L, MAX_RETRY_AFTER_SECONDS)
}

private const val MAX_RETRY_AFTER_SECONDS = 3_600L
