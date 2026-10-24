package com.bsfeng.monitor.domain.quota

/**
 * 额度 provider 契约 —— 每个云服务实现一份，UI 只依赖本接口。
 *
 * 扩展方式：新增实现类 → 在 `ServiceContainer` 注册进 `QuotaProviderRegistry`，
 * 额度页的 provider chip 与额度设置列表自动出现，不用改界面代码。
 *
 * 凭据可能不止一种：DeepSeek 的余额走官方 API（`sk-` Key），用量走平台内部接口
 * （浏览器登录令牌），两者不通用。所以拆成 [balanceCredentialKind] 与 [usageCredentialKind]。
 */
interface QuotaProvider {
    /** 稳定标识（凭据键、预警状态键、持久化偏好都用它，发布后不可变更）。 */
    val id: String

    /** 展示名称（provider chip / 标题）。 */
    val displayName: String

    /** 查额度（余额 / 内含额度 / 百分比限额）用的凭据类型。 */
    val balanceCredentialKind: QuotaCredentialKind

    /** 查用量用的凭据类型；null = 不提供用量。 */
    val usageCredentialKind: QuotaCredentialKind?

    /** 是否提供用量查询（= [usageCredentialKind] 不为 null）。 */
    val supportsUsage: Boolean get() = usageCredentialKind != null

    /** 是否提供按模型分组的用量。 */
    val supportsModelBreakdown: Boolean

    /** 查额度那行的凭据输入占位，如「Ollama API Key」。 */
    val credentialHint: String

    /** 查额度那行的凭据帮助说明（一行文案，如「在 ollama.com/settings/keys 创建」）。 */
    val credentialHelp: String

    /** 查用量那行的凭据输入占位；无用量接口时为 null。 */
    val usageCredentialHint: String? get() = null

    /** 查用量那行的凭据帮助说明；无用量接口时为 null。 */
    val usageCredentialHelp: String? get() = null

    /**
     * 拉取额度项（余额 / 内含额度 / 百分比限额 / 次数配额）。
     *
     * 失败抛 [QuotaError]；调用方负责节流与退避，provider 内部不重试。
     */
    suspend fun fetchItems(credential: String): List<QuotaItem>

    /**
     * 拉取指定时间范围的用量；[supportsUsage] 为 false 时返回 null。
     *
     * 失败抛 [QuotaError]；单次查询的时间范围上限由 provider 自行约束。
     */
    suspend fun fetchUsage(credential: String, range: QuotaRange): QuotaUsage?
}

/** 额度查询错误 —— 按与 provider 无关的语义归类，UI 直接展示 [userMessage]。 */
class QuotaError(
    val kind: QuotaErrorKind,
    message: String? = null,
    /** 429 时的退避秒数（服务端没给就为 null）。 */
    val retryAfterSeconds: Long? = null,
) : Exception(message ?: kind.name) {

    /** 面向用户的中文提示。 */
    val userMessage: String
        get() = when (kind) {
            QuotaErrorKind.MISSING_CREDENTIAL -> "未配置凭据"
            QuotaErrorKind.UNAUTHORIZED -> "凭据无效或已过期"
            QuotaErrorKind.FORBIDDEN -> message ?: "无权访问该账户数据"
            QuotaErrorKind.RATE_LIMITED -> "查询过于频繁，稍后重试"
            QuotaErrorKind.SERVICE_UNAVAILABLE -> message ?: "服务暂时不可用"
            QuotaErrorKind.INVALID_PARAM -> message ?: "查询参数不合法"
            QuotaErrorKind.INVALID_RESPONSE -> "响应解析失败"
            QuotaErrorKind.TRANSPORT -> "网络错误"
        }
}

/** 错误归类。 */
enum class QuotaErrorKind {
    MISSING_CREDENTIAL,
    UNAUTHORIZED,
    FORBIDDEN,
    RATE_LIMITED,
    SERVICE_UNAVAILABLE,
    INVALID_PARAM,
    INVALID_RESPONSE,
    TRANSPORT,
}
