package com.bsfeng.monitor.quota.provider

import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import com.bsfeng.monitor.domain.quota.QuotaError
import com.bsfeng.monitor.domain.quota.QuotaErrorKind
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaProvider
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.domain.quota.QuotaUsage
import java.math.BigDecimal

/**
 * opencode-go 额度 provider —— 一次 `GET /zen/go/v1/usage` 拿三个窗口的已用比例，Bearer 鉴权。
 *
 * - 接口给的是**已用**比例，领域模型一律存**剩余**，所以这里显式做 `remaining = 100 - percent`。
 * - 三个窗口：5 小时 / 每周 / 每月；月周期跟随订阅日（28 / 29 / 31 天），接口只给 `resetsAt`。
 * - `status = rate-limited` 表示该窗口已耗尽：剩余置 0（让预警生效）并标「已限流」。
 * - 没有用量明细（无请求数 / token / 花费 / 按模型分组），所以 [fetchUsage] 恒为 null。
 * - 403 = Key 有效但没有 Go 订阅（`EntitlementError`），单人话提示；401 / 429 / 5xx 交给 [QuotaHttp]。
 */
class OpencodeGoQuotaProvider(
    private val http: QuotaHttp = QuotaHttp(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val userAgent: String = DEFAULT_USER_AGENT,
    /** 耗时基准：旧形态的 `resetInSec` 是相对秒数，需要换算成绝对时刻。 */
    private val nowEpochSecond: () -> Long = { System.currentTimeMillis() / 1000L },
) : QuotaProvider {

    override val id: String = "opencode-go"
    override val displayName: String = "opencode-go"
    override val balanceCredentialKind: QuotaCredentialKind = QuotaCredentialKind.API_KEY
    override val usageCredentialKind: QuotaCredentialKind? = null
    override val supportsModelBreakdown: Boolean = false
    override val credentialHint: String = "Go API Key"
    override val credentialHelp: String = "与推理同一把 sk- Key；在 opencode.ai 控制台创建，需已订阅 OpenCode Go"

    override suspend fun fetchItems(credential: String): List<QuotaItem> {
        val key = QuotaHttp.normalizeCredential(credential)
        val body = try {
            http.getJson("$baseUrl/zen/go/v1/usage", QuotaHttp.bearer(key, userAgent))
        } catch (error: QuotaError) {
            throw noSubscriptionMessage(error) ?: error
        }
        return parseUsage(body, nowEpochSecond())
    }

    override suspend fun fetchUsage(credential: String, range: QuotaRange): QuotaUsage? = null

    /** 403 的原话是 `OpenCode Go subscription required.`，翻成用户能直接照做的中文。 */
    private fun noSubscriptionMessage(error: QuotaError): QuotaError? =
        if (error.kind == QuotaErrorKind.FORBIDDEN) {
            QuotaError(QuotaErrorKind.FORBIDDEN, NO_SUBSCRIPTION_MESSAGE)
        } else {
            null
        }

    companion object {

        const val DEFAULT_BASE_URL: String = "https://opencode.ai"

        /** 该站点不按 UA 拦人（Dalvik UA 也是 401），但保持与其它 provider 一致的中性 UA。 */
        const val DEFAULT_USER_AGENT: String = "android-monitor/1.0 (Android; quota-monitor)"

        /** 403 的用户话术。 */
        const val NO_SUBSCRIPTION_MESSAGE: String = "该 Key 没有 OpenCode Go 订阅"

        /** 已耗尽窗口的角标。 */
        const val BADGE_RATE_LIMITED: String = "已限流"

        private val ZERO = BigDecimal.ZERO
        private val HUNDRED = BigDecimal.valueOf(100)

        private val RATE_LIMITED = "rate-limited"

        /** 三个窗口的键（新 / 旧命名并列）与展示文案；顺序即展示顺序。 */
        private val WINDOWS: List<WindowSpec> = listOf(
            WindowSpec(listOf("rolling", "rollingUsage"), "window:rolling", "5 小时额度"),
            WindowSpec(listOf("weekly", "weeklyUsage"), "window:weekly", "每周额度"),
            WindowSpec(listOf("monthly", "monthlyUsage"), "window:monthly", "每月额度"),
        )

        /**
         * 把 `{"usage":{rolling,weekly,monthly}}` 转成额度项。
         *
         * 新旧两套字段名都认（窗口键 `rolling|rollingUsage`、比例 `percent|usagePercent`、
         * 重置 `resetsAt|resetInSec`），缺哪个窗口就少一条，三条全缺才报解析失败。
         */
        internal fun parseUsage(root: JsonNode, nowEpochSecond: Long = System.currentTimeMillis() / 1000L): List<QuotaItem> {
            // 官方当前把三个窗口包在 `usage` 里；旧版 hydration 直接平铺在顶层，两种都读。
            val scope = root.obj("usage") ?: root
            val parsed = WINDOWS.mapNotNull { spec ->
                val window = spec.keys.firstNotNullOfOrNull { key -> scope.obj(key) } ?: return@mapNotNull null
                val used = window.dec("percent") ?: window.dec("usagePercent") ?: return@mapNotNull null
                val rateLimited = window.str("status")?.trim().equals(RATE_LIMITED, ignoreCase = true)
                // 已用 → 剩余，并夹到 0..100：接口偶尔给出负值 / 超过 100 的脏数据
                val remaining = HUNDRED.minus(used).coerceIn(ZERO, HUNDRED)
                QuotaItem(
                    id = spec.id,
                    title = spec.title,
                    kind = QuotaKind.PERCENT,
                    remaining = if (rateLimited) ZERO else remaining,
                    total = null,
                    unit = QuotaUnit.PERCENT,
                    resetsAtEpochSecond = window.resetEpochSecond(nowEpochSecond),
                    badge = if (rateLimited) BADGE_RATE_LIMITED else null,
                )
            }
            if (parsed.isEmpty()) {
                throw QuotaError(QuotaErrorKind.INVALID_RESPONSE, "响应里没有额度窗口")
            }
            // 主项恰好留一条：5 小时窗口在时归它，缺失时退给第一个存在的窗口（否则 hero 没数据）
            return parsed.mapIndexed { index, item -> item.copy(primary = index == 0) }
        }

        /** 重置时刻：新形态是 RFC3339 绝对时串，旧形态是相对秒数（需要基准时刻）。 */
        private fun JsonNode.resetEpochSecond(nowEpochSecond: Long): Long? =
            epochSecond("resetsAt") ?: long("resetInSec")?.let { nowEpochSecond + it }

        private data class WindowSpec(
            val keys: List<String>,
            val id: String,
            val title: String,
        )
    }
}
