package com.bsfeng.monitor.domain.totp

import java.util.Base64

/**
 * 密钥原文的解码入口：先 Base32（严格），不通过时**只在串确实长得像 Base64**时才回落。
 *
 * 顺序不能反。otpauth 规范要求 Base32，绝大多数链接都是；但 Steam Guard 的密钥是
 * standard Base64（形如 `cnOgvVunvLFR1gZAwzDxDrgbXWM=`），它的长度余数会让严格 Base32 直接拒。
 *
 * 回落要设门槛，理由：Base64 字母表几乎覆盖 `[A-Za-z0-9]`，若「Base32 解不开就试 Base64」，
 * 手抄错一位的 Base32（`JBSW1Y3P`）会被当成 base64 收下、界面照样出码但永远不对，
 * 比直接报「密钥格式不对」更糟。所以纯 `[A-Z2-7]`（大小写统一）的串 Base32 拒了就直接 Invalid。
 *
 * [Invalid] 的 reason 恒为 [OtpauthUri.REASON_SECRET_INVALID]：domain 只产机器码，
 * 中文在 [OtpauthUri.reasonMessage] 统一翻译。
 */
sealed interface SecretDecoded {
    data class Ok(val bytes: ByteArray, val kind: String) : SecretDecoded

    data object Empty : SecretDecoded

    data class Invalid(val reason: String) : SecretDecoded
}

/** 解码出来的密钥来源标记，落库时不影响存储口径（统一存 Base32 规范形）。 */
const val SECRET_KIND_BASE32 = "base32"
const val SECRET_KIND_BASE64 = "base64"

object SecretCodec {

    /** Base64 回落的最短长度：再短就只可能是手抄错的 Base32。 */
    private const val MIN_BASE64_CHARS = 4

    /**
     * 解码密钥原文。
     *
     * 空白与空串先归到 [SecretDecoded.Empty]——「没有密钥」和「密钥格式不对」是两条不同的提示，
     * 前者提示用户重扫，后者提示用户手抄错了。Base64 分支容忍无 padding 与 URL-safe 字母表。
     */
    fun decodeOrNull(text: String): SecretDecoded {
        // 空白/连字符/等号都不携带密钥信息：只剩它们 = 没有密钥，不是「密钥格式不对」。
        // 但连字符只对 Base32 手抄分组是噪声，对 URL-safe Base64 是数据，所以两套素材分开算：
        // 判空用去掉全部分隔符的 meaningful，解码仍用只去空白的 stripped。
        val stripped = text.filterNot { it.isWhitespace() }
        val meaningful = stripped.filterNot { it == '-' || it == '=' }
        if (meaningful.isEmpty()) return SecretDecoded.Empty
        Base32.decodeOrNull(text)?.let { bytes ->
            return if (bytes.isEmpty()) {
                SecretDecoded.Empty
            } else {
                SecretDecoded.Ok(bytes, SECRET_KIND_BASE32)
            }
        }
        if (!looksLikeBase64(stripped)) return SecretDecoded.Invalid(OtpauthUri.REASON_SECRET_INVALID)
        base64Decode(stripped)?.let { bytes ->
            return if (bytes.isEmpty()) {
                SecretDecoded.Empty
            } else {
                SecretDecoded.Ok(bytes, SECRET_KIND_BASE64)
            }
        }
        return SecretDecoded.Invalid(OtpauthUri.REASON_SECRET_INVALID)
    }

    /** 算法直连用：只要字节，不要来源标记；解不开返回 null。 */
    fun decodeToBytes(text: String): ByteArray? =
        (decodeOrNull(text) as? SecretDecoded.Ok)?.bytes

    /**
     * Base64 回落的可信门槛：串里必须有 Base32 字母表给不出的特征。
     *
     * 三个特征任一命中即可：URL-safe 专有字符（`+` `/` `-` `_`）、padding（`=`）、
     * 大小写混排（纯大写或纯小写更像手抄的 Base32）。都没有就说明这是「Base32 写错了」而不是 base64 密钥。
     * 长度下限取 4：Base64 的最短有意义长度，也正好是手抄错串的常见长度（`ABC` 只有 3）。
     */
    private fun looksLikeBase64(stripped: String): Boolean {
        val compact = stripped.filterNot { it == '=' }
        if (compact.length < MIN_BASE64_CHARS) return false
        val hasSpecial = compact.any { it == '+' || it == '/' || it == '-' || it == '_' }
        val hasPadding = '=' in stripped
        val hasUpper = compact.any { it in 'A'..'Z' }
        val hasLower = compact.any { it in 'a'..'z' }
        return hasSpecial || hasPadding || (hasUpper && hasLower)
    }

    /** 标准解码器优先，失败再 URL-safe；两者都容忍缺 padding。 */
    private fun base64Decode(compact: String): ByteArray? {
        val padded = if (compact.length % 4 == 0) compact else compact + "=".repeat(4 - compact.length % 4)
        for (decoder in listOf(Base64.getDecoder(), Base64.getUrlDecoder())) {
            try {
                val bytes = decoder.decode(padded)
                if (bytes.isNotEmpty()) return bytes
            } catch (e: IllegalArgumentException) {
                // 换下一个字母表继续试；两种都不行才算非法
            }
        }
        return null
    }
}