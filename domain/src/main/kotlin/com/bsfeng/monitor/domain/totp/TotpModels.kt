package com.bsfeng.monitor.domain.totp

/**
 * 验证器参数。
 *
 * 默认值就是绝大多数服务端发的口径，调用方只在偏离默认时才显式传参。
 * 取值范围收紧到 RFC 6238 的三种 HMAC 与常见的 6/8 位、30/60 秒，是为了让
 * 「不认识的参数一律拒绝」有确定性边界——否则 7 位码、45 秒周期会被静默算成另一个码，
 * 用户看到的是「码不对」而不是「这个验证器不被支持」。
 */
data class TotpParams(
    val algorithm: String = ALGORITHM_SHA1,
    val digits: Int = DEFAULT_DIGITS,
    val periodSec: Int = DEFAULT_PERIOD_SEC,
) {
    companion object {
        const val ALGORITHM_SHA1 = "SHA1"
        const val ALGORITHM_SHA256 = "SHA256"
        const val ALGORITHM_SHA512 = "SHA512"
        const val DEFAULT_DIGITS = 6
        const val DEFAULT_PERIOD_SEC = 30
    }
}

/**
 * 一条验证器账户。
 *
 * id 用 0 表示「尚未入库」：解析出的账户在落库前没有主键，用 0 比用负值更贴近
 * SQLite 自增语义。密钥统一存 Base32 无填充大写形式，保证「同一账户反复导入」不会
 * 因为大小写/空格产生重复记录。
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
) {
    companion object {
        /** 默认展示形态：一行 issuer + 一行账号名。 */
        const val VARIANT_STANDARD = "STANDARD"
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

/** 支持的算法名（归一化后的大写形式）。 */
internal val SUPPORTED_TOTP_ALGORITHMS = listOf(
    TotpParams.ALGORITHM_SHA1,
    TotpParams.ALGORITHM_SHA256,
    TotpParams.ALGORITHM_SHA512,
)

/**
 * 归一化算法名：忽略大小写与连字符（sha-256 与 SHA256 同义），不认识返回 null。
 * 返回 null 而不是兜底成 SHA1，是为了让调用方能区分「参数缺失」与「参数非法」。
 */
internal fun normalizeTotpAlgorithm(raw: String): String? {
    val compact = raw.trim().replace("-", "").uppercase()
    return SUPPORTED_TOTP_ALGORITHMS.firstOrNull { it == compact }
}

internal fun isSupportedDigits(digits: Int): Boolean = digits == 6 || digits == 8

internal fun isSupportedPeriod(periodSec: Int): Boolean = periodSec == 30 || periodSec == 60
