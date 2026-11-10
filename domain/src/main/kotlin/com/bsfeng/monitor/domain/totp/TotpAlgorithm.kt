package com.bsfeng.monitor.domain.totp

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * RFC 4226（HOTP）与 RFC 6238（TOTP）的动态口令生成，外加 Steam Guard 的字母表变体。
 *
 * 全部是纯函数：同一 (密钥, 时间, 参数) 永远得到同一个码，不读时钟、不缓存、不打印。
 * 输入不可信但这里**不设 sealed 失败通道**——码是每秒重算一次的展示值，
 * 让 UI 因为一个非法参数崩溃得不偿失；这里按真实参数出码、越界只夹紧（见 [hotp] / [totp] 注释），
 * 而「要不要接受这个参数」由 [OtpauthUri.parse] 在导入环节一次性把关。
 *
 * 关键口径：位数与周期不再降级成 6 位/30 秒。降级会让 7 位码、15 秒周期静默算成另一个码，
 * 用户看到的是「码不对」而不是「参数不被支持」——那是更坏的错误。
 */
object TotpAlgorithm {

    /** Steam Guard 的 26 字母表（去掉了容易混淆的 0/1/A/E/I/L/O/S/U 之外的字符）。 */
    const val STEAM_ALPHABET = "23456789BCDFGHJKMNPQRTVWXY"

    private const val STEAM_SHA1_PERIOD_SEC = 30
    private const val STEAM_CODE_LENGTH = 5
    private const val STEAM_RADIX = 26

    /** 10^0..10^10；Long 内安全，避免每次取模都调用 Math.pow。 */
    private val POW10 = LongArray(TotpParams.MAX_DIGITS + 1).also {
        it[0] = 1L
        for (i in 1..TotpParams.MAX_DIGITS) it[i] = it[i - 1] * 10L
    }

    /**
     * 生成 HOTP。
     *
     * 降级口径：空密钥返回空串（没有可展示的码，比返回 000000 更不容易被误读为真码）；
     * digits 越界夹紧到 1..10 后**仍按夹紧后的位数出码**；未知 algorithm 按 SHA1；负 counter 按 0。
     * 取模用 Long：31 位动态截断的最大值 2^31-1 在 10^10 之内，位数到 10 也不会溢出。
     * SHA 实现来自 JCA 标准提供者，不引入第三方依赖。
     */
    fun hotp(secret: ByteArray, counter: Long, digits: Int, algorithm: String): String {
        if (secret.isEmpty()) return ""
        val width = TotpParams.clampDigits(digits)
        val hash = hmac(macNameOf(algorithm), secret, counterBytes(if (counter < 0L) 0L else counter))
            ?: return ""
        return (truncate(hash).toLong() % POW10[width]).toString().padStart(width, '0')
    }

    /**
     * 生成 TOTP：计数器 = epochSecond / periodSec。
     *
     * 降级口径同 [hotp]；此外非法 periodSec 夹紧到 1..86400 秒、负时间按 0 处理。
     */
    fun totp(secret: ByteArray, epochSecond: Long, params: TotpParams): String {
        val period = TotpParams.clampPeriod(params.periodSec)
        val safeSecond = if (epochSecond < 0L) 0L else epochSecond
        return hotp(secret, safeSecond / period, params.digits, params.algorithm)
    }

    /**
     * 生成 Steam Guard 码：RFC 6238 SHA1 / 30 秒 → 同一套动态截断得 31 位整数 →
     * 连取 5 次 26 进制，映射到 [STEAM_ALPHABET]。
     *
     * 与数字 TOTP 共用时间窗口与密钥解码，只是输出字母表不同；空密钥返回空串。
     */
    fun steam(secret: ByteArray, epochSecond: Long): String {
        if (secret.isEmpty()) return ""
        val safeSecond = if (epochSecond < 0L) 0L else epochSecond
        val hash = hmac(MAC_SHA1, secret, counterBytes(safeSecond / STEAM_SHA1_PERIOD_SEC)) ?: return ""
        var value = truncate(hash)
        val out = StringBuilder(STEAM_CODE_LENGTH)
        repeat(STEAM_CODE_LENGTH) {
            out.append(STEAM_ALPHABET[value % STEAM_RADIX])
            value /= STEAM_RADIX
        }
        return out.toString()
    }

    /** RFC 4226 §5.3 动态截断：取末字节低 4 位为偏移，读 4 字节并清掉最高位。 */
    private fun truncate(hash: ByteArray): Int {
        val offset = hash[hash.size - 1].toInt() and 0x0F
        return ((hash[offset].toInt() and 0x7F) shl 24) or
            ((hash[offset + 1].toInt() and 0xFF) shl 16) or
            ((hash[offset + 2].toInt() and 0xFF) shl 8) or
            (hash[offset + 3].toInt() and 0xFF)
    }

    private const val MAC_SHA1 = "HmacSHA1"

    private fun macNameOf(algorithm: String): String = when (normalizeTotpAlgorithm(algorithm)) {
        TotpParams.ALGORITHM_SHA256 -> "HmacSHA256"
        TotpParams.ALGORITHM_SHA512 -> "HmacSHA512"
        TotpParams.ALGORITHM_MD5 -> "HmacMD5"
        else -> MAC_SHA1
    }

    private fun hmac(macName: String, secret: ByteArray, message: ByteArray): ByteArray? = try {
        val mac = Mac.getInstance(macName)
        mac.init(SecretKeySpec(secret, macName))
        mac.doFinal(message)
    } catch (e: java.security.GeneralSecurityException) {
        // 标准 JCA 提供者必然支持这四种 HMAC；真缺了也宁可显示空码，不把异常抛给 UI
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
