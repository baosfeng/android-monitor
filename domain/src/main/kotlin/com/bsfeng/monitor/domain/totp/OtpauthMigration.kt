package com.bsfeng.monitor.domain.totp

import java.nio.charset.StandardCharsets
import java.util.Base64

/**
 * Google Authenticator 迁移二维码（`otpauth-migration://offline?data=...`）的解析。
 *
 * 迁移串是 protobuf 的 MigrationPayload，一个二维码里通常有好几条账户，所以这里是**批量**入口：
 * 单条不合法只跳过并给中文原因，不中断整批（用户扫一次就想把能导的都导进来）。
 *
 * data 是标准 Base64（带 padding，URI 里常被百分号编码成 %3D）；无 padding 与 base64url 也吃。
 * 全程不抛异常：解析失败统一走 [OtpauthBatchResult.skipped] 的中文原因。
 */
object OtpauthMigration {

    /** data 解出来的二进制上限：1–2 KB 是常态，32 KB 之外更可能是扫错了整段文本。 */
    const val MAX_DATA_BYTES = 32768

    private const val SCHEME = "otpauth-migration://"
    private const val PARAM_DATA = "data"
    private const val SUPPORTED_VERSION = 1

    /** 枚举取值 → domain 口径；0 = UNSPECIFIED，按官方语义落到默认值。 */
    private val ALGORITHMS = mapOf(
        0L to TotpParams.ALGORITHM_SHA1,
        1L to TotpParams.ALGORITHM_SHA1,
        2L to TotpParams.ALGORITHM_SHA256,
        3L to TotpParams.ALGORITHM_SHA512,
        4L to TotpParams.ALGORITHM_MD5,
    )
    private val DIGITS = mapOf(
        0L to TotpParams.DEFAULT_DIGITS,
        1L to 6,
        2L to 8,
    )
    private val TYPES = mapOf(
        0L to TotpParams.TYPE_TOTP,
        1L to TotpParams.TYPE_HOTP,
        2L to TotpParams.TYPE_TOTP,
    )

    /**
     * 解析迁移链接：返回能落库的账户与跳过原因。
     *
     * 结构损坏（不是迁移链接 / data 解不出 / version 不认 / protobuf 坏）给一条整体的
     * [OtpauthUri.REASON_MIGRATION_INVALID] 中文原因，accounts 为空。
     */
    fun parse(uri: String): OtpauthBatchResult {
        val raw = uri.trim()
        if (!raw.startsWith(SCHEME, ignoreCase = true)) return failure()
        // scheme 之后是 "offline?data=..."：先截到第一个 '?' 之后，否则 "offline?data" 会被当成参数名
        val rest = raw.substring(SCHEME.length)
        val queryAt = rest.indexOf('?')
        if (queryAt < 0) return failure()
        val data = queryValue(rest.substring(queryAt + 1), PARAM_DATA) ?: return failure()
        val bytes = decodeData(data) ?: return failure()
        if (bytes.isEmpty() || bytes.size > MAX_DATA_BYTES) return failure()

        val wire = ProtoWire.of(bytes)
        val entries = mutableListOf<ProtoWire.Field.Bytes>()
        var version = 0L
        while (wire.hasMore) {
            val field = wire.next() ?: return failure()
            when {
                field.tag == 1 && field is ProtoWire.Field.Bytes -> entries += field
                field.tag == 2 && field is ProtoWire.Field.Varint -> version = field.value
                // 其余字段（batch_size / batch_index / batch_id 及未来新增）一律跳过
            }
        }
        if (version != SUPPORTED_VERSION.toLong()) return failure()

        val accounts = mutableListOf<TotpAccount>()
        val skipped = mutableListOf<String>()
        entries.forEach { entry ->
            when (val parsed = parseEntry(ProtoWire.of(entry))) {
                is OtpauthParseResult.Success -> accounts += parsed.account
                is OtpauthParseResult.Failure -> skipped += OtpauthUri.reasonMessage(parsed.reason)
            }
        }
        return OtpauthBatchResult(accounts, skipped)
    }

    /** 单条 OtpParameters：secret/1、name/2、issuer/3、algorithm/4、digits/5、type/6、counter/7。 */
    private fun parseEntry(wire: ProtoWire): OtpauthParseResult {
        var secret: ByteArray? = null
        var name = ""
        var issuer = ""
        var algorithm = 0L
        var digits = 0L
        var type = 0L
        var counter = 0L
        while (wire.hasMore) {
            val field = wire.next() ?: return OtpauthParseResult.Failure(OtpauthUri.REASON_MIGRATION_INVALID)
            val tag = field.tag
            if (field is ProtoWire.Field.Bytes) {
                when (tag) {
                    1 -> secret = field.value
                    2 -> name = field.value.toString(StandardCharsets.UTF_8).trim()
                    3 -> issuer = field.value.toString(StandardCharsets.UTF_8).trim()
                }
                continue
            }
            if (field !is ProtoWire.Field.Varint) continue
            when (tag) {
                4 -> algorithm = field.value
                5 -> digits = field.value
                6 -> type = field.value
                7 -> counter = field.value
            }
        }

        val key = secret ?: return OtpauthParseResult.Failure(OtpauthUri.REASON_SECRET_MISSING)
        if (key.isEmpty()) return OtpauthParseResult.Failure(OtpauthUri.REASON_SECRET_INVALID)
        // 未知枚举值按「参数非法」拒绝，不猜用户意图
        val resolvedDigits = DIGITS[digits] ?: return OtpauthParseResult.Failure(OtpauthUri.REASON_DIGITS_UNSUPPORTED)
        val resolvedAlgorithm = ALGORITHMS[algorithm]
            ?: return OtpauthParseResult.Failure(OtpauthUri.REASON_ALGORITHM_UNSUPPORTED)
        val resolvedType = TYPES[type] ?: return OtpauthParseResult.Failure(OtpauthUri.REASON_BAD_TYPE)

        // name 缺但 issuer 在 → 用 issuer 顶替；两者都空才拒绝（与 otpauth 链接同一口径）
        val accountName = name.ifEmpty { issuer }
        if (accountName.isEmpty()) return OtpauthParseResult.Failure(OtpauthUri.REASON_LABEL_MISSING)

        return OtpauthParseResult.Success(
            TotpAccount(
                issuer = issuer,
                accountName = accountName,
                secretBase32 = Base32.encode(key),
                params = TotpParams(
                    algorithm = resolvedAlgorithm,
                    digits = resolvedDigits,
                    periodSec = TotpParams.DEFAULT_PERIOD_SEC,
                    type = resolvedType,
                ),
                variant = detectTotpVariant(issuer, accountName, resolvedDigits),
                counter = if (TotpParams.isHotpType(resolvedType)) counter.coerceAtLeast(0L) else 0L,
            ),
        )
    }

    /** query 里取指定的百分号解码后参数；迁移串只认 data，其余忽略。 */
    private fun queryValue(query: String, key: String): String? {
        for (pair in query.substringBefore('#').split('&')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            if (pair.substring(0, eq).trim().lowercase() != key) continue
            return try {
                java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8)
            } catch (e: IllegalArgumentException) {
                null
            }
        }
        return null
    }

    /** 标准 Base64 优先（官方 sample 带 padding），再 base64url，最后补 padding 重试。 */
    private fun decodeData(text: String): ByteArray? {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.isEmpty()) return null
        for (decoder in listOf(Base64.getDecoder(), Base64.getUrlDecoder())) {
            for (candidate in listOf(compact, compact + "=".repeat((4 - compact.length % 4) % 4))) {
                try {
                    val bytes = decoder.decode(candidate)
                    if (bytes.isNotEmpty()) return bytes
                } catch (e: IllegalArgumentException) {
                    // 换下一个字母表 / 补 padding 再试
                }
            }
        }
        return null
    }

    private fun failure() = OtpauthBatchResult(
        emptyList(),
        listOf(OtpauthUri.reasonMessage(OtpauthUri.REASON_MIGRATION_INVALID)),
    )
}