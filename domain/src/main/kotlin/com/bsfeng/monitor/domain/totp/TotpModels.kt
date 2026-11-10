package com.bsfeng.monitor.domain.totp

/**
 * 验证器参数。
 *
 * 取值面按「主流验证器实际覆盖的范围」放宽：位数 1–10、周期 1–86400 秒、算法 SHA1/SHA256/SHA512/MD5。
 * 放宽不等于放任——算法层一律按真实参数出码（见 [TotpAlgorithm]），不再把 7 位静默算成 6 位；
 * 「要不要接受这个参数」仍由 [OtpauthUri.parse] 在导入环节一次性把关。
 *
 * [type] 区分 TOTP / HOTP：HOTP 的计数器挂在 [TotpAccount.counter] 上，不按时间换码。
 * MD5 只会在迁移或第三方导入里出现，手动输入不引导用户选择。
 */
data class TotpParams(
    val algorithm: String = ALGORITHM_SHA1,
    val digits: Int = DEFAULT_DIGITS,
    val periodSec: Int = DEFAULT_PERIOD_SEC,
    val type: String = TYPE_TOTP,
) {
    companion object {
        const val ALGORITHM_SHA1 = "SHA1"
        const val ALGORITHM_SHA256 = "SHA256"
        const val ALGORITHM_SHA512 = "SHA512"
        const val ALGORITHM_MD5 = "MD5"
        const val TYPE_TOTP = "TOTP"
        const val TYPE_HOTP = "HOTP"
        const val DEFAULT_DIGITS = 6
        const val DEFAULT_PERIOD_SEC = 30
        const val MIN_DIGITS = 1
        const val MAX_DIGITS = 10
        const val MIN_PERIOD_SEC = 1
        const val MAX_PERIOD_SEC = 86400

        /** 导入环节的准入判据：范围外一律拒绝，不猜用户意图。 */
        fun isSupportedDigits(digits: Int): Boolean = digits in MIN_DIGITS..MAX_DIGITS

        /** 导入环节的准入判据：范围外一律拒绝。 */
        fun isSupportedPeriod(periodSec: Int): Boolean = periodSec in MIN_PERIOD_SEC..MAX_PERIOD_SEC

        /** 算法层的降级口径：越界夹紧后仍按夹紧值出码，不回落默认。 */
        fun clampDigits(digits: Int): Int = digits.coerceIn(MIN_DIGITS, MAX_DIGITS)

        /** 算法层的降级口径：越界夹紧后仍按夹紧值计时，不回落 30 秒。 */
        fun clampPeriod(periodSec: Int): Int = periodSec.coerceIn(MIN_PERIOD_SEC, MAX_PERIOD_SEC)

        /** type 只有两个字面量，其余一律按 TOTP 处理（算法层不抛异常）。 */
        fun isHotpType(type: String): Boolean = type.trim().uppercase() == TYPE_HOTP
    }
}

/**
 * 一条验证器账户。
 *
 * id 用 0 表示「尚未入库」：解析出的账户在落库前没有主键，用 0 比用负值更贴近
 * SQLite 自增语义。[secretBase32] 是**密钥原文**而不是严格的 Base32：Steam 的密钥是
 * base64 原文，一律先经 [SecretCodec] 解码再算码；导入时落库值统一规范成无填充大写 Base32，
 * 保证「同一账户反复导入」不会因为大小写/空格产生重复记录。
 *
 * [counter] 只在 [TotpParams.type] 为 HOTP 时有意义，TOTP 恒为 0。
 * [variant] 标注展示形态：STEAM 用 5 位字母表出码、不显示数字分段。
 *
 * 故意没有 createdAt：排序由 sortOrder 决定，时间戳属于存储层职责，
 * 领域层不需要时钟依赖。
 */
data class TotpAccount(
    val id: Long = 0L,
    val issuer: String,
    val accountName: String,
    val secretBase32: String,
    val params: TotpParams = TotpParams(),
    val sortOrder: Int = 0,
    val iconKey: String = "",
    val variant: String = VARIANT_STANDARD,
    val counter: Long = 0L,
) {
    companion object {
        /** 默认展示形态：一行 issuer + 一行账号名，口令按位数分段。 */
        const val VARIANT_STANDARD = "STANDARD"

        /** Steam Guard：5 位字母表口令，不分段，周期固定 30 秒。 */
        const val VARIANT_STEAM = "STEAM"
    }
}

/**
 * 倒计时状态，供 UI 直接渲染。
 *
 * remainingSec 恒在 1..periodSec：换码瞬间显示满格而不是 0，
 * 否则环形进度会在每个窗口末尾闪一下「0 秒」。
 * progress 是**剩余**比例（1.0 = 刚进位，趋向 0 = 即将换码），进度条按它收缩。
 */
data class Countdown(val remainingSec: Int, val progress: Float)

/**
 * otpauth URI 解析结果。
 *
 * 用 sealed 结果而不是异常：URI 来自扫码/粘贴，属于不可信输入，
 * 「非法」是正常分支，调用方需要逐条给出可操作的提示（密钥错 / 位数不支持 / 链接太长）。
 */
sealed interface OtpauthParseResult {
    data class Success(val account: TotpAccount) : OtpauthParseResult
    data class Failure(val reason: String) : OtpauthParseResult
}

/**
 * 批量导入结果（Google 迁移二维码）。
 *
 * [skipped] 是给用户看的中文句子，一条对应一个被跳过的条目，不中断整批。
 */
data class OtpauthBatchResult(
    val accounts: List<TotpAccount>,
    val skipped: List<String>,
)

/** 支持的算法名（归一化后的大写形式）。 */
internal val SUPPORTED_TOTP_ALGORITHMS = listOf(
    TotpParams.ALGORITHM_SHA1,
    TotpParams.ALGORITHM_SHA256,
    TotpParams.ALGORITHM_SHA512,
    TotpParams.ALGORITHM_MD5,
)

/**
 * Steam 变体识别：issuer 归一后是 steam，或位数是 Steam 的 5 位且 label 里带 steam。
 * 只影响展示形态，不改变出码口径（出码由 [TotpAlgorithm.steam] 决定）。
 */
internal fun detectTotpVariant(issuer: String, accountName: String, digits: Int): String =
    if (issuer.equals("steam", ignoreCase = true) ||
        (digits == 5 && accountName.contains("steam", ignoreCase = true))
    ) {
        TotpAccount.VARIANT_STEAM
    } else {
        TotpAccount.VARIANT_STANDARD
    }

/**
 * 归一化算法名：忽略大小写、HMAC 前缀与连字符/下划线（HMAC-SHA1 / HMAC_SHA1 / sha-256 同义），
 * 不认识返回 null。返回 null 而不是兜底成 SHA1，是为了让调用方能区分「参数缺失」与「参数非法」。
 */
fun normalizeTotpAlgorithm(raw: String): String? {
    var compact = raw.trim().uppercase()
    compact = compact.removePrefix("HMAC-").removePrefix("HMAC_")
    compact = compact.replace("-", "").replace("_", "")
    return SUPPORTED_TOTP_ALGORITHMS.firstOrNull { it == compact }
}