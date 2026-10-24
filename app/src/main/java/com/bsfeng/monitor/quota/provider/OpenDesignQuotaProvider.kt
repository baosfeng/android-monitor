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
import java.math.RoundingMode

/**
 * open-design（Open Design）额度 provider —— 控制台内部接口，用网页 Cookie 鉴权。
 *
 * - `GET /api/v1/workspaces`：用 Cookie 列出 workspace，取第一个的 `workspaceId`
 *   （所以用户只需要贴 Cookie，不用手抄 workspace id）；
 * - `GET /api/v1/workspaces/{id}/billing/coding-plan-usage`：返回
 *   `{tier, eligible, windows[{durationSeconds, usedCredits, limitCredits, remainingCredits, resetsAt, resetMode}]}`，
 *   credits 是十进制字符串，**10000 credits = $1**。
 *
 * 这是个没有文档的内部接口：没有用量/花费查询，只有「窗口剩余比例」，
 * 所以 [fetchUsage] 返回 null，额度页只显示额度卡。
 *
 * 注意：Cookie 里的 `__Secure-better-auth.session_token` 会过期，失效时返回 401 `{"error":"unauthenticated"}`。
 */
class OpenDesignQuotaProvider(
    private val http: QuotaHttp = QuotaHttp(),
    private val baseUrl: String = DEFAULT_BASE_URL,
    private val userAgent: String = DEFAULT_USER_AGENT,
) : QuotaProvider {

    override val id: String = "opendesign"
    override val displayName: String = "Open Design"
    override val balanceCredentialKind: QuotaCredentialKind = QuotaCredentialKind.SESSION_COOKIE
    override val usageCredentialKind: QuotaCredentialKind? = null
    override val supportsModelBreakdown: Boolean = false
    override val credentialHint: String = "网页 Cookie"
    override val credentialHelp: String =
        "浏览器登录 open-design.ai 后，DevTools 里复制 Cookie（含 __Secure-better-auth.session_token）；" +
            "会过期，失效后重贴"

    override suspend fun fetchItems(credential: String): List<QuotaItem> {
        val cookie = QuotaHttp.normalizeCookie(credential)
        val workspaceId = firstWorkspaceId(cookie)
        return parseUsageWindows(
            http.getJson(
                "$baseUrl/api/v1/workspaces/$workspaceId/billing/coding-plan-usage",
                QuotaHttp.cookie(cookie, userAgent),
            ),
        )
    }

    override suspend fun fetchUsage(credential: String, range: QuotaRange): QuotaUsage? = null

    /** Cookie 可能有效但没有任何 workspace（未开通）→ 给一句人话，而不是「解析失败」。 */
    private fun firstWorkspaceId(cookie: String): String {
        val root = http.getJson("$baseUrl/api/v1/workspaces", QuotaHttp.cookie(cookie, userAgent))
        val first = root.items("items").firstOrNull()
            ?: throw QuotaError(QuotaErrorKind.FORBIDDEN, "该账号下没有 workspace")
        return first.str("workspaceId")
            ?: throw QuotaError(QuotaErrorKind.INVALID_RESPONSE, "缺少 workspaceId")
    }

    companion object {

        const val DEFAULT_BASE_URL: String = "https://amr-api.open-design.ai"
        const val DEFAULT_USER_AGENT: String = "android-monitor/1.0 (Android; quota-monitor)"

        private const val HOUR = 3_600L
        private const val DAY = 86_400L

        /**
         * 把 windows 转成额度项。
         *
         * 平台只给 credits，不给花费也不给按模型明细；换算成「剩余百分比」展示，
         * 与控制台的口径一致（credits 数值本身放进标题下方的说明里，不丢信息）。
         */
        internal fun parseUsageWindows(root: JsonNode): List<QuotaItem> {
            val windows = root.items("windows")
            if (windows.isEmpty()) return emptyList()
            val items = windows.mapIndexedNotNull { index, window ->
                val duration = window.long("durationSeconds") ?: return@mapIndexedNotNull null
                val used = window.dec("usedCredits")
                val limit = window.dec("limitCredits")
                val remaining = window.dec("remainingCredits")
                    ?: limit?.minus(used ?: BigDecimal.ZERO)
                    ?: return@mapIndexedNotNull null
                if (limit == null || limit.signum() <= 0) return@mapIndexedNotNull null
                val percent = remaining.multiply(BigDecimal(100))
                    .divide(limit, 2, RoundingMode.HALF_UP)
                QuotaItem(
                    id = "window:$duration",
                    title = windowTitle(duration),
                    kind = QuotaKind.PERCENT,
                    remaining = percent,
                    unit = QuotaUnit.PERCENT,
                    resetsAtEpochSecond = window.epochSecond("resetsAt"),
                    primary = index == 0,
                    // credits 是平台的原生口径（10000 credits = $1），不换算成金额，原样列出来。
                    // 不把「窗口起始时刻」格式化进这句话：它是按抓取时的时区烘成字符串的，
                    // 进缓存后用户换时区会残留旧时区；重置时刻由 resetsAt 单独展示（每次展示都转本地）。
                    detailText = window.dec("usedCredits")?.toPlainString()?.let { used ->
                        "$used / ${limit.toPlainString()} credits"
                    },
                    badge = firstItemBadge(index, root.str("tier"), root.bool("eligible")),
                )
            }
            // 短窗口排前面（5h → 周 → 月），与控制台一致
            return items.sortedBy { it.resetsAtEpochSecond ?: Long.MAX_VALUE }
        }

        /** 只在第一条上带档位标：tier=go、eligible=false 时降级成「额度不可用」。 */
        private fun firstItemBadge(index: Int, tier: String?, eligible: Boolean?): String? {
            if (index != 0) return null
            if (eligible == false) return "额度不可用"
            return tier?.replaceFirstChar { it.uppercase() }
        }

        private fun windowTitle(durationSeconds: Long): String = when (durationSeconds) {
            HOUR * 5 -> "5 小时额度"
            HOUR * 24 -> "每日额度"
            DAY * 7 -> "每周额度"
            DAY * 30 -> "每月额度"
            else -> {
                val hours = durationSeconds / HOUR
                if (durationSeconds % HOUR == 0L) "每 $hours 小时额度" else "额度"
            }
        }
    }
}
