package com.bsfeng.monitor.totp.ui

// TOTP UI 层的纯逻辑：验证码分段、平台首字与取色、由密钥派生验证码、表单校验。
// 全部是真函数（无 Compose、无时钟），单测直接钉住这些口径。

import com.bsfeng.monitor.domain.totp.Base32
import com.bsfeng.monitor.domain.totp.Countdown
import com.bsfeng.monitor.domain.totp.TotpAlgorithm
import com.bsfeng.monitor.domain.totp.TotpParams

/** 剩余多少秒切 warning 色（原型 .totp-row.is-warn 的口径）。 */
const val TOTP_WARN_SECONDS = 5

/** 段间距：6 位 → 「123 456」，8 位 → 「1234 5678」；其它长度按对半切，切不动就原样返回。 */
fun formatCode(code: String, digits: Int): String {
    if (code.isEmpty()) return code
    val mid = when {
        digits == 8 && code.length == 8 -> 4
        digits == 6 && code.length == 6 -> 3
        else -> code.length / 2
    }
    if (mid <= 0 || mid >= code.length) return code
    return code.substring(0, mid) + " " + code.substring(mid)
}

/** 平台色块首字：取首个非空白字符并转大写；空串回落到全角问号（不抛异常）。 */
fun iconInitial(issuer: String): String {
    val ch = issuer.trim().firstOrNull() ?: return "？"
    return ch.uppercase()
}

/**
 * 平台色块取色下标：issuer 稳定散列到既有 chart 色板 16 色。
 *
 * 用 [Int] 截断成非负再取模，避免 hashCode 为负时得到负下标（原型只要求「同一平台同色」，
 * 不新增颜色 token）。
 */
fun issuerColorIndex(issuer: String): Int {
    var h = 0
    for (c in issuer.trim().lowercase()) {
        h = h * 31 + c.code
    }
    return (h and 0x7FFF_FFFF) % 16
}

/** 由 Base32 密钥与秒级时间派生验证码；密钥非法时返回空串（UI 显示占位，不抛异常）。 */
fun deriveCode(secretBase32: String, epochSecond: Long, params: TotpParams): String {
    val secret = Base32.decodeOrNull(secretBase32) ?: return ""
    return TotpAlgorithm.totp(secret, epochSecond, params)
}

/** 剩余 ≤5 秒切 warning（纯判断，供环形与秒数共用）。 */
fun isCountdownWarning(countdown: Countdown): Boolean = countdown.remainingSec <= TOTP_WARN_SECONDS

/**
 * 手动输入的校验：null = 合法，否则返回面向用户的全角中文提示。
 *
 * 口径固定为「先必填、再格式」——空密钥不报格式错，否则用户会以为密钥只是写错了。
 */
fun validateManualEntry(issuer: String, secretBase32: String): String? {
    if (issuer.isBlank()) return "请填写平台名"
    if (secretBase32.isBlank() || secretBase32.none { !it.isWhitespace() }) return "请填写密钥"
    if (Base32.decodeOrNull(secretBase32) == null) return "密钥格式不对：只能包含 A–Z 与 2–7，可带空格"
    return null
}

/** 手动输入表单的完整校验：平台名 / 账户 / 密钥逐项过，返回第一条面向用户的提示。 */
fun validateManualForm(form: TotpManualForm): String? {
    if (form.issuer.isBlank()) return "请填写平台名"
    if (form.accountName.isBlank()) return "请填写账户"
    return validateManualEntry(form.issuer, form.secretBase32)
}

/**
 * 列表分组标题：按平台名分段，空平台归到「未命名平台」。
 *
 * 分组标题必须由账户自身决定（同一账户永远落在同一段），不依赖列表顺序，
 * 否则增删一条账户会让其它账户跳段。
 */
fun groupLabelOf(issuer: String): String = issuer.trim().ifBlank { "未命名平台" }

/** 加密导出密码校验：至少 8 位（原型「设置备份密码（至少 8 位）」）。 */
fun validateBackupPassword(password: String): String? =
    if (password.length < BACKUP_PASSWORD_MIN) "备份密码至少 $BACKUP_PASSWORD_MIN 位" else null

/** 原型写死的密码下限，UI 与测试共用一处。 */
const val BACKUP_PASSWORD_MIN = 8
