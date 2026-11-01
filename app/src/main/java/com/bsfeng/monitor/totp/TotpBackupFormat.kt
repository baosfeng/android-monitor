package com.bsfeng.monitor.totp

import com.bsfeng.monitor.domain.totp.TotpParams

/**
 * 备份条目 —— 加密备份与第三方导入共用的线格式，不含 id / 排序等本机字段。
 *
 * secret 保留 Base32 原文（不在这里解码）；合法性统一交给 domain 层 OtpauthUri.parse 判定。
 */
data class TotpBackupEntry(
    val issuer: String,
    val account: String,
    val secret: String,
    val algorithm: String = TotpParams.ALGORITHM_SHA1,
    val digits: Int = TotpParams.DEFAULT_DIGITS,
    val period: Int = TotpParams.DEFAULT_PERIOD_SEC,
    val type: String = "TOTP",
)

/**
 * 导入结果：可导入条目 + 被跳过条目及原因。
 *
 * 整份文件结构损坏抛 [TotpBackupException]；单条不合法（缺密钥 / 参数不支持 / 变体不支持）
 * 只跳过并写明原因，不静默丢数据 —— UI 必须把 skipped 展示给用户。
 */
data class TotpImportResult(
    val entries: List<TotpBackupEntry>,
    val skipped: List<String>,
) {
    val total: Int get() = entries.size + skipped.size
}

/** 备份 / 导入失败（口令错、格式不识别、版本不支持、文件过大）。消息面向用户，可直接展示。 */
class TotpBackupException(message: String, cause: Throwable? = null) : Exception(message, cause)
