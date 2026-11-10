package com.bsfeng.monitor.domain.totp

import java.util.Base64

/**
 * 密钥原文的解码入口：先 Base32（严格），失败再 Base64（标准 + URL-safe）。
 *
 * 顺序不能反。otpauth 规范要求 Base32，绝大多数链接都是；但 Steam Guard 的密钥是
 * standard Base64（形如 `cnOgvVunvLFR1gZAwzDxDrgbXWM=`），它的长度余数会让严格 Base32 直接拒，
 * 于是「先 Base32 再 Base64」既保住规范口径，又让 Steam 可用。
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

    /**
     * 解码密钥原文。
     *
     * 空白与空串先归到 [SecretDecoded.Empty]——「没有密钥」和「密钥格式不对」是两条不同的提示，
     * 前者提示用户重扫，后者提示用户手抄错了。Base64 分支容忍无 padding 与 URL-safe 字母表。
     */
    fun decodeOrNull(text: String): SecretDecoded {
        val compact = text.filterNot { it.isWhitespace() }
        if (compact.isEmpty() || compact.all { it == '=' }) return SecretDecoded.Empty
        Base32.decodeOrNull(text)?.let { bytes ->
            return if (bytes.isEmpty()) {
                SecretDecoded.Empty
            } else {
                SecretDecoded.Ok(bytes, SECRET_KIND_BASE32)
            }
        }
        base64Decode(compact)?.let { bytes ->
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
