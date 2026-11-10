package com.bsfeng.monitor.domain.totp

import java.nio.charset.StandardCharsets

/**
 * otpauth:// URI 的解析与生成（Key Uri Format）。
 *
 * 解析的是**不可信输入**（扫码、转发、剪贴板），因此：
 * - 全程不抛异常，任何拒绝都走 [OtpauthParseResult.Failure] 并带上机器可读的 reason，
 *   中文提示由 [reasonMessage] 统一翻译（domain 只产机器码，不产英文句子）；
 * - 先卡长度再解析，避免超长串把后面每一步都拖一遍；
 * - 只认 otpauth / otpauth-migration 两种 scheme：http/https 链接虽然也长得像，但那是钓鱼入口，直接拒。
 *
 * 支持面按主流验证器的实际口径：位数 1–10、周期 1–86400 秒、算法 SHA1/SHA256/SHA512/MD5、
 * TOTP 与 HOTP 两种类型、Steam 变体；密钥原文走 [SecretCodec]（Base32 优先，Base64 兜底）。
 *
 * 已知取舍：迁移链接（otpauth-migration://）里常有多条账户，[parse] 只返回第一条——
 * 批量导入请用 [parseBatch]，否则其余条目会被静默丢掉。
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
    const val REASON_MIGRATION_INVALID = "migration_invalid"

    private const val SCHEME = "otpauth://"
    private const val MIGRATION_SCHEME = "otpauth-migration://"
    private const val TYPE_TOTP = "totp"
    private const val TYPE_HOTP = "hotp"

    /** Steam Guard 的另一种真实导出形态是 host 直接写 steam（Aegis 按 `case "steam"` 分支处理）；解析端认，生成端不产。 */
    private const val TYPE_STEAM = "steam"

    /** Steam 字母表码长恒为 5，与 [TotpAlgorithm.steam] 的输出长度一致。 */
    private const val STEAM_DIGITS = 5

    private const val STEAM_ISSUER_LABEL = "Steam"

    /**
     * 上限取 4096：Google 迁移串常 1–2 KB，512 会直接拒掉；
     * 4 KB 之外的内容更可能是误扫整段文本，没必要为它跑完整条解析。
     */
    const val MAX_URI_LENGTH = 4096

    private const val KEY_SECRET = "secret"
    private const val KEY_ISSUER = "issuer"
    private const val KEY_ALGORITHM = "algorithm"
    private const val KEY_DIGITS = "digits"
    private const val KEY_PERIOD = "period"
    private const val KEY_COUNTER = "counter"

    /** reason → 用户可读的中文提示。查不到时给一句兜底，不把机器码漏到界面。 */
    fun reasonMessage(reason: String): String = when (reason) {
        REASON_EMPTY -> "链接是空的"
        REASON_TOO_LONG -> "二维码内容太长（超过 4 KB）"
        REASON_BAD_SCHEME -> "不是 otpauth 链接（http 链接是钓鱼入口，已拒绝）"
        REASON_BAD_TYPE -> "只支持 TOTP / HOTP"
        REASON_BAD_ENCODING -> "链接里的百分号编码坏了"
        REASON_LABEL_MISSING -> "链接里没有账户名"
        REASON_SECRET_MISSING -> "链接里没有密钥"
        REASON_SECRET_INVALID -> "密钥格式不对：Base32 只允许 A–Z 与 2–7"
        REASON_DIGITS_UNSUPPORTED -> "位数不在 1–10 之间"
        REASON_PERIOD_UNSUPPORTED -> "周期不在 1–86400 秒之间"
        REASON_ALGORITHM_UNSUPPORTED -> "不支持这个算法（只认 SHA1 / SHA256 / SHA512 / MD5）"
        REASON_MIGRATION_INVALID -> "Google 迁移二维码损坏或版本不支持"
        else -> "这个二维码暂不支持"
    }

    /** 单条解析入口：迁移链接只取第一条成功项（多条请走 [parseBatch]）。 */
    fun parse(uri: String): OtpauthParseResult {
        val raw = uri.trim()
        if (raw.isEmpty()) return failure(REASON_EMPTY)
        if (raw.length > MAX_URI_LENGTH) return failure(REASON_TOO_LONG)
        if (raw.startsWith(MIGRATION_SCHEME, ignoreCase = true)) {
            val batch = OtpauthMigration.parse(raw)
            val first = batch.accounts.firstOrNull() ?: return failure(REASON_MIGRATION_INVALID)
            return OtpauthParseResult.Success(first)
        }
        if (!raw.startsWith(SCHEME, ignoreCase = true)) return failure(REASON_BAD_SCHEME)

        val rest = raw.substring(SCHEME.length)
        val boundary = rest.indexOfFirst { it == '/' || it == '?' || it == '#' }
            .let { if (it < 0) rest.length else it }
        val type = rest.substring(0, boundary).lowercase()
        if (type != TYPE_TOTP && type != TYPE_HOTP && type != TYPE_STEAM) return failure(REASON_BAD_TYPE)

        val afterType = rest.substring(boundary)
        val queryAt = afterType.indexOf('?')
        val rawLabel = if (queryAt < 0) afterType else afterType.substring(0, queryAt)
        val rawQuery = if (queryAt < 0) "" else afterType.substring(queryAt + 1)

        return parseSingle(rawLabel, rawQuery, isHotp = type == TYPE_HOTP, isSteam = type == TYPE_STEAM)
    }

    /**
     * 批量解析入口：普通 otpauth 链接得到 1 条，迁移链接得到 N 条；
     * 失败原因全部进 skipped（中文），调用方可以一次列给用户看。
     */
    fun parseBatch(uri: String): OtpauthBatchResult {
        val raw = uri.trim()
        if (raw.isEmpty()) return OtpauthBatchResult(emptyList(), listOf(reasonMessage(REASON_EMPTY)))
        if (raw.startsWith(MIGRATION_SCHEME, ignoreCase = true)) return OtpauthMigration.parse(raw)
        return when (val single = parse(raw)) {
            is OtpauthParseResult.Success -> OtpauthBatchResult(listOf(single.account), emptyList())
            is OtpauthParseResult.Failure -> OtpauthBatchResult(emptyList(), listOf(reasonMessage(single.reason)))
        }
    }

    private fun parseSingle(
        rawLabel: String,
        rawQuery: String,
        isHotp: Boolean,
        isSteam: Boolean,
    ): OtpauthParseResult {
        val params = parseQuery(rawQuery) ?: return failure(REASON_BAD_ENCODING)

        val secretText = params[KEY_SECRET] ?: return failure(REASON_SECRET_MISSING)
        val secretBytes = when (val decoded = SecretCodec.decodeOrNull(secretText)) {
            is SecretDecoded.Ok -> decoded.bytes
            is SecretDecoded.Empty -> return failure(REASON_SECRET_INVALID)
            is SecretDecoded.Invalid -> return failure(REASON_SECRET_INVALID)
        }

        // 位数先按链接原文解析（steam host 不看 digit 参数，Steam 码长恒为 5）；
        // 是否归一成 5 由变体判定决定，见下面 digits 的收敛
        val declaredDigits = if (isSteam) {
            STEAM_DIGITS
        } else params[KEY_DIGITS].let { value ->
            if (value == null) {
                TotpParams.DEFAULT_DIGITS
            } else {
                value.trim().toIntOrNull()?.takeIf { TotpParams.isSupportedDigits(it) }
                    ?: return failure(REASON_DIGITS_UNSUPPORTED)
            }
        }
        val period = params[KEY_PERIOD].let { value ->
            if (value == null) {
                TotpParams.DEFAULT_PERIOD_SEC
            } else {
                value.trim().toIntOrNull()?.takeIf { TotpParams.isSupportedPeriod(it) }
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
        // counter 只在 HOTP 有意义；非数字按 0，不让一个坏参数把整条链接拒掉
        val counter = params[KEY_COUNTER]?.trim()?.toLongOrNull()?.coerceAtLeast(0L) ?: 0L

        val label = decode(rawLabel.trim().trimStart('/').trim())
            ?: return failure(REASON_BAD_ENCODING)
        val hasIssuerPrefix = ':' in label
        val labelIssuer = if (hasIssuerPrefix) label.substringBefore(':').trim() else ""
        val accountName = if (hasIssuerPrefix) label.substringAfter(':').trim() else label.trim()
        val issuerParam = params[KEY_ISSUER]?.trim().orEmpty()
        val issuer = if (issuerParam.isNotEmpty()) issuerParam else labelIssuer
        // 账户名缺失但 issuer 在：用 issuer 顶替，比让用户对着「链接里没有账户名」重扫有用
        val resolvedName = accountName.ifEmpty { issuer }
        if (resolvedName.isEmpty()) return failure(REASON_LABEL_MISSING)

        // host 写 steam 时直接判 Steam 变体，不再依赖 label/issuer 里的字样
        val variant = if (isSteam) {
            TotpAccount.VARIANT_STEAM
        } else {
            detectTotpVariant(issuer, resolvedName, declaredDigits)
        }
        // 不变量：variant == STEAM ⟹ digits == 5。Steam 的码是 5 个字母，位数由字母表决定；
        // 不收敛的话详情页会显示「6 位」却出字母码，用户改成 8 位后码仍然是 5 个字母
        val digits = if (variant == TotpAccount.VARIANT_STEAM) STEAM_DIGITS else declaredDigits

        return OtpauthParseResult.Success(
            TotpAccount(
                issuer = issuer,
                accountName = resolvedName,
                // 归一化成无填充大写 Base32：base64 来源（Steam）也转成 Base32 存，存储口径统一
                secretBase32 = Base32.encode(secretBytes),
                params = TotpParams(
                    algorithm = algorithm,
                    digits = digits,
                    periodSec = period,
                    type = if (isHotp) TotpParams.TYPE_HOTP else TotpParams.TYPE_TOTP,
                ),
                variant = variant,
                counter = if (isHotp) counter else 0L,
            ),
        )
    }

    /**
     * 生成 otpauth:// 链接（备份导出、二维码分享用）。
     *
     * 默认值不写进 query：SHA1/6 位/30 秒/TOTP 是绝大多数客户端的默认，省掉能显著缩短二维码，
     * 而解析端对缺失参数的处理与本函数一致，往返仍然等价。HOTP 必须写 type 与 counter。
     *
     * **只产标准形态**：Steam 也写成 `otpauth://totp/<issuer>:<name>?secret=...&digits=5`，
     * `otpauth://steam/...` 只在解析端兼容——多数验证器不认那个 host，导出成它等于把二维码做废。
     * Steam 账户的 issuer 统一规范成 `Steam`：解析端判变体靠「issuer/label 含 steam」的强信号，
     * digits=5 只是弱信号，原 issuer 若是 `Valve` 之类，导出再导入就会退化成 STANDARD。
     */
    fun build(account: TotpAccount): String {
        val isSteamVariant = account.variant == TotpAccount.VARIANT_STEAM
        val rawIssuer = account.issuer.trim()
        val issuer = if (isSteamVariant && !rawIssuer.contains("steam", ignoreCase = true)) {
            STEAM_ISSUER_LABEL
        } else {
            rawIssuer
        }
        val name = account.accountName.trim()
        val label = if (issuer.isEmpty()) name else issuer + ":" + name
        val isHotp = TotpParams.isHotpType(account.params.type)
        // Steam 的码恒为 5 个字母，位数由字母表决定，不沿用账户里可能被改坏的 digits
        val digits = if (isSteamVariant) STEAM_DIGITS else account.params.digits
        val out = StringBuilder(SCHEME)
            .append(if (isHotp) TYPE_HOTP else TYPE_TOTP)
            .append('/')
            .append(encode(label))
            .append('?')
            .append(KEY_SECRET)
            .append('=')
            .append(normalizeSecret(account.secretBase32))
        if (isHotp) {
            out.append('&').append(KEY_COUNTER).append('=').append(account.counter.coerceAtLeast(0L))
        }
        if (issuer.isNotEmpty()) {
            out.append('&').append(KEY_ISSUER).append('=').append(encode(issuer))
        }
        normalizeTotpAlgorithm(account.params.algorithm)?.let { algorithm ->
            if (algorithm != TotpParams.ALGORITHM_SHA1) {
                out.append('&').append(KEY_ALGORITHM).append('=').append(algorithm)
            }
        }
        if (digits != TotpParams.DEFAULT_DIGITS) {
            out.append('&').append(KEY_DIGITS).append('=').append(digits)
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
            val key = percentDecode(rawKey, keepPlus = false)?.trim()?.lowercase() ?: return null
            // 值里的字面 + 保留：base64 密钥（Steam）会出现 +，把它当空格会毁掉密钥
            val value = percentDecode(rawValue, keepPlus = true) ?: return null
            if (key.isNotEmpty() && !out.containsKey(key)) out[key] = value
        }
        return out
    }

    /** 密钥统一编码成无填充大写 Base32；解不开就只去掉空白，交给下游再报错。 */
    private fun normalizeSecret(secretBase32: String): String =
        SecretCodec.decodeToBytes(secretBase32)?.let(Base32::encode)
            ?: secretBase32.filterNot { it.isWhitespace() || it == '-' || it == '=' }.uppercase()

    private fun decode(text: String): String? = try {
        java.net.URLDecoder.decode(text, StandardCharsets.UTF_8)
    } catch (e: IllegalArgumentException) {
        null
    }

    /** 百分号解码；[keepPlus] 时字面 '+' 保留（base64 里它是数据不是空格）。坏编码返回 null。 */
    private fun percentDecode(text: String, keepPlus: Boolean): String? {
        val prepared = if (keepPlus) text.replace("+", "%2B") else text
        if ('%' !in prepared) return prepared
        return try {
            java.net.URLDecoder.decode(prepared, StandardCharsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            null
        }
    }

    /** 空格编码成 %20 而不是 +：label 位于 path 段，+ 在 path 里是字面量。 */
    private fun encode(text: String): String =
        java.net.URLEncoder.encode(text, StandardCharsets.UTF_8).replace("+", "%20")
}