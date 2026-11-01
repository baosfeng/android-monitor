package com.bsfeng.monitor.domain.totp

import java.net.URLDecoder
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * otpauth:// URI 的解析与生成（Key Uri Format）。
 *
 * 解析的是**不可信输入**（扫码、转发、剪贴板），因此：
 * - 全程不抛异常，任何拒绝都走 [OtpauthParseResult.Failure] 并带上机器可读的 reason，
 *   由 UI 翻译成「链接太长」「密钥格式不对」「不支持 7 位码」这类可操作提示；
 * - 先卡长度再解析，避免超长串把后面每一步都拖一遍；
 * - 只认 otpauth scheme：http/https 链接虽然也长得像，但那是钓鱼入口，直接拒。
 *
 * 已知取舍：[TotpAccount] 没有 counter 字段，所以 hotp 链接能解析成功但计数器不进模型，
 * 以「每 periodSec 换一次码」的 TOTP 口径入库；真要做 HOTP 需要模型扩展，不在本期范围。
 */
object OtpauthUri {

    const val REASON_EMPTY = "uri_empty"
    const val REASON_TOO_LONG = "uri_too_long"
    const val REASON_BAD_SCHEME = "uri_bad_scheme"
    const val REASON_BAD_TYPE = "uri_bad_type"
    const val REASON_BAD_ENCODING = "uri_bad_encoding"
    const val REASON_LABEL_MISSING = "uri_label_missing"
    const val REASON_SECRET_MISSING = "secret_missing"
    const val REASON_SECRET_INVALID = "secret_invalid"
    const val REASON_DIGITS_UNSUPPORTED = "digits_unsupported"
    const val REASON_PERIOD_UNSUPPORTED = "period_unsupported"
    const val REASON_ALGORITHM_UNSUPPORTED = "algorithm_unsupported"

    private const val SCHEME = "otpauth://"
    private const val TYPE_TOTP = "totp"
    private const val TYPE_HOTP = "hotp"

    /** 上限取 512：够装下 issuer + 邮箱 + 32 字节密钥，又能在解析前挡掉粘贴错的大段文本。 */
    private const val MAX_URI_LENGTH = 512

    private const val KEY_SECRET = "secret"
    private const val KEY_ISSUER = "issuer"
    private const val KEY_ALGORITHM = "algorithm"
    private const val KEY_DIGITS = "digits"
    private const val KEY_PERIOD = "period"

    fun parse(uri: String): OtpauthParseResult {
        val raw = uri.trim()
        if (raw.isEmpty()) return failure(REASON_EMPTY)
        if (raw.length > MAX_URI_LENGTH) return failure(REASON_TOO_LONG)
        if (!raw.startsWith(SCHEME, ignoreCase = true)) return failure(REASON_BAD_SCHEME)

        val rest = raw.substring(SCHEME.length)
        val boundary = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) rest.length else it }
        val type = rest.substring(0, boundary).lowercase()
        if (type != TYPE_TOTP && type != TYPE_HOTP) return failure(REASON_BAD_TYPE)

        val afterType = rest.substring(boundary)
        val queryAt = afterType.indexOf('?')
        val rawLabel = if (queryAt < 0) afterType else afterType.substring(0, queryAt)
        val rawQuery = if (queryAt < 0) "" else afterType.substring(queryAt + 1)

        val params = parseQuery(rawQuery) ?: return failure(REASON_BAD_ENCODING)

        val secretText = params[KEY_SECRET] ?: return failure(REASON_SECRET_MISSING)
        val secretBytes = Base32.decodeOrNull(secretText) ?: return failure(REASON_SECRET_INVALID)
        if (secretBytes.isEmpty()) return failure(REASON_SECRET_INVALID)

        val digits = params[KEY_DIGITS].let { value ->
            if (value == null) {
                TotpParams.DEFAULT_DIGITS
            } else {
                value.trim().toIntOrNull()?.takeIf { isSupportedDigits(it) }
                    ?: return failure(REASON_DIGITS_UNSUPPORTED)
            }
        }
        val period = params[KEY_PERIOD].let { value ->
            if (value == null) {
                TotpParams.DEFAULT_PERIOD_SEC
            } else {
                value.trim().toIntOrNull()?.takeIf { isSupportedPeriod(it) }
                    ?: return failure(REASON_PERIOD_UNSUPPORTED)
            }
        }
        val algorithm = params[KEY_ALGORITHM].let { value ->
            if (value == null) {
                TotpParams.ALGORITHM_SHA1
            } else {
                normalizeTotpAlgorithm(value) ?: return failure(REASON_ALGORITHM_UNSUPPORTED)
            }
        }

        val label = decode(rawLabel.trim().trimStart('/').trim())
            ?: return failure(REASON_BAD_ENCODING)
        val hasIssuerPrefix = ':' in label
        val labelIssuer = if (hasIssuerPrefix) label.substringBefore(':').trim() else ""
        val accountName = if (hasIssuerPrefix) label.substringAfter(':').trim() else label.trim()
        if (accountName.isEmpty()) return failure(REASON_LABEL_MISSING)

        val issuerParam = params[KEY_ISSUER]?.trim().orEmpty()
        val issuer = if (issuerParam.isNotEmpty()) issuerParam else labelIssuer

        return OtpauthParseResult.Success(
            TotpAccount(
                issuer = issuer,
                accountName = accountName,
                // 归一化成无填充大写，保证同一账户重复导入不会因写法差异产生重复记录
                secretBase32 = Base32.encode(secretBytes),
                params = TotpParams(algorithm = algorithm, digits = digits, periodSec = period),
            ),
        )
    }

    /**
     * 生成标准 otpauth://totp 链接（备份导出、二维码分享用）。
     *
     * 默认值不写进 query：SHA1/6 位/30 秒是绝大多数客户端的默认，省掉能显著缩短二维码，
     * 而解析端对缺失参数的处理与本函数一致，往返仍然等价。
     */
    fun build(account: TotpAccount): String {
        val issuer = account.issuer.trim()
        val name = account.accountName.trim()
        val label = if (issuer.isEmpty()) name else issuer + ":" + name
        val out = StringBuilder(SCHEME)
            .append(TYPE_TOTP)
            .append('/')
            .append(encode(label))
            .append('?')
            .append(KEY_SECRET)
            .append('=')
            .append(normalizeSecret(account.secretBase32))
        if (issuer.isNotEmpty()) {
            out.append('&').append(KEY_ISSUER).append('=').append(encode(issuer))
        }
        normalizeTotpAlgorithm(account.params.algorithm)?.let { algorithm ->
            if (algorithm != TotpParams.ALGORITHM_SHA1) {
                out.append('&').append(KEY_ALGORITHM).append('=').append(algorithm)
            }
        }
        if (account.params.digits != TotpParams.DEFAULT_DIGITS) {
            out.append('&').append(KEY_DIGITS).append('=').append(account.params.digits)
        }
        if (account.params.periodSec != TotpParams.DEFAULT_PERIOD_SEC) {
            out.append('&').append(KEY_PERIOD).append('=').append(account.params.periodSec)
        }
        return out.toString()
    }

    private fun failure(reason: String) = OtpauthParseResult.Failure(reason)

    /** 重复参数以第一个为准：后面若被追加了覆盖值（例如 &secret= 注入），不会被采纳。 */
    private fun parseQuery(query: String): Map<String, String>? {
        if (query.isEmpty()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        for (pair in query.substringBefore('#').split('&')) {
            if (pair.isEmpty()) continue
            val eq = pair.indexOf('=')
            val rawKey = if (eq < 0) pair else pair.substring(0, eq)
            val rawValue = if (eq < 0) "" else pair.substring(eq + 1)
            val key = decode(rawKey)?.trim()?.lowercase() ?: return null
            val value = decode(rawValue) ?: return null
            if (key.isNotEmpty() && !out.containsKey(key)) out[key] = value
        }
        return out
    }

    private fun normalizeSecret(secretBase32: String): String =
        Base32.decodeOrNull(secretBase32)?.let(Base32::encode)
            ?: secretBase32.filterNot { it.isWhitespace() || it == '-' || it == '=' }.uppercase()

    private fun decode(text: String): String? = try {
        URLDecoder.decode(text, StandardCharsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** 空格编码成 %20 而不是 +：label 位于 path 段，+ 在 path 里是字面量。 */
    private fun encode(text: String): String =
        URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20")
}
