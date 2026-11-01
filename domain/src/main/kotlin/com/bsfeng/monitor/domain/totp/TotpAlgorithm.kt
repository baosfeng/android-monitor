package com.bsfeng.monitor.domain.totp

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 4226（HOTP）与 RFC 6238（TOTP）的动态口令生成。
 *
 * 全部是纯函数：同一 (密钥, 时间, 参数) 永远得到同一个码，不读时钟、不缓存、不打印。
 * 输入不可信但这里**不设 sealed 失败通道**——码是每秒重算一次的展示值，
 * 让 UI 因为一个非法参数崩溃得不偿失；这里降级到安全默认值（见 [hotp] / [totp] 注释），
 * 而「要不要接受这个参数」由 [OtpauthUri.parse] 在导入环节一次性把关。
 */
object TotpAlgorithm {

    private const val DIGITS_6 = 6
    private const val DIGITS_8 = 8
    private const val MODULO_6 = 1_000_000
    private const val MODULO_8 = 100_000_000

    /**
     * 生成 HOTP。
     *
     * 降级口径：空密钥返回空串（没有可展示的码，比返回 000000 更不容易被误读为真码）；
     * digits 非 8 一律按 6 位（只认 6/8，更宽的位数会撞上 31 位动态截断）；
     * 未知 algorithm 按 SHA1；负 counter 按 0。
     * SHA 实现来自 JCA 标准提供者，不引入第三方依赖。
     */
    fun hotp(secret: ByteArray, counter: Long, digits: Int, algorithm: String): String {
        if (secret.isEmpty()) return ""
        val macName = macNameOf(algorithm)
        val hash = hmac(macName, secret, counterBytes(if (counter < 0L) 0L else counter)) ?: return ""
        val offset = hash[hash.size - 1].toInt() and 0x0F
        val binary = ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
        val width = if (digits == DIGITS_8) DIGITS_8 else DIGITS_6
        val modulo = if (width == DIGITS_8) MODULO_8 else MODULO_6
        return (binary % modulo).toString().padStart(width, '0')
    }

    /**
     * 生成 TOTP：计数器 = epochSecond / periodSec。
     *
     * 降级口径同 [hotp]；此外非法 periodSec 按 30 秒、负时间按 0 处理。
     */
    fun totp(secret: ByteArray, epochSecond: Long, params: TotpParams): String {
        val period = if (isSupportedPeriod(params.periodSec)) params.periodSec else TotpParams.DEFAULT_PERIOD_SEC
        val safeSecond = if (epochSecond < 0L) 0L else epochSecond
        return hotp(secret, safeSecond / period, params.digits, params.algorithm)
    }

    private fun macNameOf(algorithm: String): String = when (normalizeTotpAlgorithm(algorithm)) {
        TotpParams.ALGORITHM_SHA256 -> "HmacSHA256"
        TotpParams.ALGORITHM_SHA512 -> "HmacSHA512"
        else -> "HmacSHA1"
    }

    private fun hmac(macName: String, secret: ByteArray, message: ByteArray): ByteArray? = try {
        val mac = Mac.getInstance(macName)
        mac.init(SecretKeySpec(secret, macName))
        mac.doFinal(message)
    } catch (e: java.security.GeneralSecurityException) {
        // 标准 JCA 提供者必然支持这三种 HMAC；真缺了也宁可显示空码，不把异常抛给 UI
        null
    }

    private fun counterBytes(counter: Long): ByteArray {
        val out = ByteArray(8)
        var value = counter
        for (i in 7 downTo 0) {
            out[i] = (value and 0xFF).toByte()
            value = value ushr 8
        }
        return out
    }
}
