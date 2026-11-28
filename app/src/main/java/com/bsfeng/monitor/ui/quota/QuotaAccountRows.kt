package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.domain.quota.QuotaError
import com.bsfeng.monitor.domain.quota.QuotaErrorKind
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.domain.quota.instanceSeq
import com.bsfeng.monitor.quota.QuotaRepository
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 账号卡列表的数据模型与全部推导规则（纯函数，可 JVM 单测）。
 *
 * 为什么推导不放 Composable：徽标优先级、两列选择、紧急判定、结论条 leader 都是「同一份
 * 快照只能有一个答案」的口径 —— 写在组合里就会主屏说一套、三态区说另一套。
 *
 * 产品口径（leader 已裁决）：
 * - 数字 = 剩余比例；两列 = 剩余最低的两个可量化窗口（同值优先主项）；
 * - 纯余额型（DeepSeek）一列：金额 + 「余额」+ 更新时间；
 * - 徽标优先级：耗尽 > 令牌过期 > 低于阈值 > 快见底(≤20%) > 已恢复 > 主号/备用；
 * - 紧急 = 任一窗口 0% / 最低 ≤20% / 凭据类错误 / 余额低于阈值（「只显示需处理的行」据它折叠重置时刻）。
 */

/** 数位列的状态：决定横条颜色（原型 .ncol.is-low / .is-dead）。 */
internal enum class QuotaColumnState { NORMAL, LOW, DEAD }

/** 账号行右侧的一列（原型 .ncol：数字 → 5dp 条 → 窗口名 → 重置时刻）。 */
internal data class QuotaAccountColumn(
    val title: String,
    /** 剩余比例 0..1；纯余额型 / 未配置为 null。 */
    val fraction: Float?,
    val valueText: String,
    val resetText: String?,
    val state: QuotaColumnState,
)

/** 徽标语义色：DANGER = 原型 .badge.is-dead（danger 底）。 */
internal enum class QuotaBadgeTone { NEUTRAL, DANGER }

/** 账号行徽标（原型 .badge）；[urgent] 表示这一行属于「需处理」，决定重置时刻折不折叠。 */
internal data class QuotaAccountBadge(
    val text: String,
    val tone: QuotaBadgeTone = QuotaBadgeTone.NEUTRAL,
    val urgent: Boolean = true,
)

/** 一个账号行（= 一个实例）的全部展示数据。 */
internal data class QuotaAccountUi(
    val instanceId: String,
    val providerTypeId: String,
    /** 行标题（类型名）。 */
    val name: String,
    /** 结论条 / 三态区用的「类型名 · 身份」。 */
    val headline: String,
    val badge: QuotaAccountBadge?,
    val columns: List<QuotaAccountColumn>,
    val urgent: Boolean,
    val configured: Boolean,
    val supportsUsage: Boolean,
    val usageConfigured: Boolean,
)

/**
 * 由实例清单 + 状态 + 三态区 + 阈值算出账号行（顺序 = 入参顺序 = 注册顺序 + 组内序号）。
 *
 * 返回的是**全部实例**（含未配置）；额度主屏只消费 configured 的行 —— 注册表会给每个
 * provider 补默认实例，全列出来就是一堆「未配置 / —」的占位卡。
 */
internal fun buildQuotaAccounts(
    providers: List<QuotaProviderInfo>,
    states: Map<String, QuotaRepository.ProviderState>,
    triad: QuotaAlertTriad,
    lowBalanceThresholds: Map<QuotaUnit, BigDecimal>,
    nowEpochSecond: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<QuotaAccountUi> {
    val recovered = triad.rows
        .filter { it.kind == QuotaAlertTriadKind.RECOVERED }
        .map { it.instanceId }
        .toSet()
    return providers.map { info ->
        val state = states[info.instanceId] ?: QuotaRepository.ProviderState(info.instanceId)
        val configured = state.balanceConfigured || state.usageConfigured
        val seq = instanceSeq(info.instanceId, info.providerTypeId)
        val identity = quotaAccountIdentity(info, seq)
        val columns = quotaAccountColumns(state, configured, nowEpochSecond, zone)
        val badge = quotaAccountBadge(state, info, seq, recovered, lowBalanceThresholds)
        QuotaAccountUi(
            instanceId = info.instanceId,
            providerTypeId = info.providerTypeId,
            name = info.typeDisplayName,
            headline = if (identity.isEmpty()) info.typeDisplayName else info.typeDisplayName + " · " + identity,
            badge = badge,
            columns = columns,
            urgent = columns.any { it.state != QuotaColumnState.NORMAL } || badge?.urgent == true,
            configured = configured,
            supportsUsage = info.supportsUsage,
            usageConfigured = info.usageConfigured,
        )
    }
}

/** 账号身份：改过名用名字；多账号时第一实例「主号」、第二「备用」、其余「账号 N」；单账号为空。 */
internal fun quotaAccountIdentity(info: QuotaProviderInfo, seq: Int): String = info.label.trim().ifEmpty {
    when {
        !info.multiAccount -> ""
        seq == 1 -> "主号"
        seq == 2 -> "备用"
        else -> "账号 " + seq
    }
}

/**
 * 徽标（原型 .badge）：按「耗尽 > 令牌过期 > 低于阈值 > 快见底 > 已恢复 > 主号/备用」取第一条。
 *
 * 令牌过期判据是状态里的 errorMessage 等于凭据类错误的用户文案：数据层只把 userMessage 写回
 * 状态（丢了 QuotaErrorKind），所以这里对同一个领域常量比对，不另拼字符串。
 */
internal fun quotaAccountBadge(
    state: QuotaRepository.ProviderState,
    info: QuotaProviderInfo,
    seq: Int,
    recovered: Set<String>,
    lowBalanceThresholds: Map<QuotaUnit, BigDecimal>,
): QuotaAccountBadge? {
    val fractions = state.items.mapNotNull { it.remainingFraction }
    val dead = fractions.any { it <= 0f }
    val low = fractions.minOrNull()?.let { it <= QUOTA_LOW_FRACTION } == true
    val balanceBelow = state.items.any { item ->
        item.isPlainBalance && lowBalanceThresholds[item.unit]?.let { item.remaining <= it } == true
    }
    val credentialError = state.errorMessage == QuotaError(QuotaErrorKind.UNAUTHORIZED).userMessage
    return when {
        dead -> QuotaAccountBadge("耗尽", QuotaBadgeTone.DANGER)
        credentialError -> QuotaAccountBadge("令牌过期")
        balanceBelow -> QuotaAccountBadge("低于阈值", QuotaBadgeTone.DANGER)
        low -> QuotaAccountBadge("快见底")
        // 已恢复是本轮的好消息，不是「需处理」：urgent=false，正常行照样折叠重置时刻
        info.instanceId in recovered -> QuotaAccountBadge("已恢复", urgent = false)
        // 主号 / 备用 / 账号 N 只是身份，不是「需处理」
        info.multiAccount && seq == 1 -> QuotaAccountBadge("主号", urgent = false)
        info.multiAccount && seq == 2 -> QuotaAccountBadge("备用", urgent = false)
        info.multiAccount -> QuotaAccountBadge("账号 " + seq, urgent = false)
        else -> null
    }
}

/** 快见底阈值：与三态区同源（QUOTA_TRIAD_LOW_FRACTION，0.20）。 */
internal const val QUOTA_LOW_FRACTION: Float = 0.20f

/** 数位列：最低的两个可量化窗口（同值优先主项）；纯余额型一列；未配置一列占位。 */
internal fun quotaAccountColumns(
    state: QuotaRepository.ProviderState,
    configured: Boolean,
    nowEpochSecond: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): List<QuotaAccountColumn> {
    if (!configured) {
        return listOf(QuotaAccountColumn("未配置", null, "—", null, QuotaColumnState.NORMAL))
    }
    val quantifiable = state.items
        .mapNotNull { item -> item.remainingFraction?.let { fraction -> item to fraction } }
        .sortedWith(
            compareBy<Pair<QuotaItem, Float>> { it.second }.thenByDescending { it.first.primary },
        )
    if (quantifiable.isEmpty()) {
        val balance = state.items.firstOrNull()
        return if (balance != null && balance.isPlainBalance) {
            listOf(
                QuotaAccountColumn(
                    title = balance.title.ifBlank { "余额" },
                    fraction = null,
                    valueText = QuotaFormat.amount(balance.remaining, balance.unit),
                    resetText = alertAgoText(state.fetchedAtEpochSecond, nowEpochSecond),
                    state = if (balance.remaining.signum() <= 0) QuotaColumnState.DEAD else QuotaColumnState.NORMAL,
                ),
            )
        } else {
            emptyList()
        }
    }
    return quantifiable.take(2).map { (item, fraction) ->
        QuotaAccountColumn(
            title = item.title,
            fraction = fraction,
            valueText = (fraction * 100f).roundToInt().toString() + "%",
            resetText = quotaResetText(item.resetsAtEpochSecond ?: item.period?.endEpochSecond, nowEpochSecond, zone),
            state = columnStateOf(fraction),
        )
    }
}

/** 横条 / 数值的状态档：0% 危险，≤20% 警告，其余正常。 */
internal fun columnStateOf(fraction: Float): QuotaColumnState = when {
    fraction <= 0f -> QuotaColumnState.DEAD
    fraction <= QUOTA_LOW_FRACTION -> QuotaColumnState.LOW
    else -> QuotaColumnState.NORMAL
}

/** 重置时刻短文案：今天 17:00 / 明天 00:00 / 周一 00:00（7 天内）/ 12-01 00:00；无重置为 null。 */
internal fun quotaResetText(
    resetsAtEpochSecond: Long?,
    nowEpochSecond: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String? {
    val ts = resetsAtEpochSecond ?: return null
    val at = Instant.ofEpochSecond(ts).atZone(zone)
    val now = Instant.ofEpochSecond(nowEpochSecond).atZone(zone)
    val clock = String.format(Locale.US, "%02d:%02d", at.hour, at.minute)
    val days = java.time.temporal.ChronoUnit.DAYS.between(now.toLocalDate(), at.toLocalDate())
    return when {
        days <= 0L -> "今天 " + clock
        days == 1L -> "明天 " + clock
        days < 7L -> WEEKDAY_NAMES[at.dayOfWeek.value - 1] + " " + clock
        else -> String.format(Locale.US, "%02d-%02d ", at.monthValue, at.dayOfMonth) + clock
    }
}

/** 周一…周日（原型写「周一 00:00」，不用 Locale 的短名，避免随系统语言变）。 */
private val WEEKDAY_NAMES = arrayOf("周一", "周二", "周三", "周四", "周五", "周六", "周日")

/** 结论条：剩余最低的那一行（同值取账号顺序靠前者）。 */
internal data class QuotaLeader(
    val headline: String,
    val windowText: String,
    val resetText: String?,
    val fraction: Float,
    val valueText: String,
)

internal fun quotaLeader(accounts: List<QuotaAccountUi>): QuotaLeader? {
    var best: Triple<QuotaAccountUi, QuotaAccountColumn, Float>? = null
    accounts.forEach { account ->
        account.columns.forEach { column ->
            val fraction = column.fraction ?: return@forEach
            val current = best
            if (current == null || fraction < current.third) best = Triple(account, column, fraction)
        }
    }
    return best?.let { (account, column, fraction) ->
        QuotaLeader(
            headline = account.headline,
            windowText = column.title + "窗口",
            resetText = column.resetText,
            fraction = fraction,
            valueText = column.valueText,
        )
    }
}

/** 页头副行：7 个账号 · 4 家 · 更新于 4 分钟前（账号数 = 实例数，家数 = 类型数）。 */
internal fun quotaHeadCounts(accounts: List<QuotaAccountUi>): Pair<Int, Int> =
    accounts.size to accounts.map { it.providerTypeId }.distinct().size

/**
 * 页头时间戳（锚点 s18-updated 的文案）：取最近一次成功拉取时间。
 *
 * [failed] = 任一账号这次查询失败 / 数据是旧的：文案改成「更新失败 · 数据 X 分钟前」，
 * 不让旧数字冒充新数据（原型没有失败态，这里只改副行、不加新块，布局不变）。
 */
internal fun quotaUpdatedText(
    fetchedAtEpochSecond: Long,
    nowEpochSecond: Long,
    failed: Boolean = false,
    /** 时区：生产用系统默认；测试钉住时区，否则 CI（UTC）与本地（+08）断言不一致。 */
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val ago = when {
        fetchedAtEpochSecond <= 0L -> null
        (nowEpochSecond - fetchedAtEpochSecond) < 60L -> "刚刚"
        (nowEpochSecond - fetchedAtEpochSecond) < 3_600L ->
            ((nowEpochSecond - fetchedAtEpochSecond).coerceAtLeast(0L) / 60).toString() + " 分钟前"
        else -> QuotaFormat.clockShortOf(fetchedAtEpochSecond, zone)
    }
    // 失败态只报「更新失败」不报时间：393dp 下副行可用宽 201dp，带前缀 + 时间会 ellipsis
    // （实测「更新失败 · 上次成功 刚刚」超出可用宽）；上次成功时刻在结论条与三态区里有。
    if (failed) return "更新失败"
    return if (ago == null) "尚未更新" else "更新于" + if (ago == "刚刚") "刚刚" else " " + ago
}

/** 是否有账号这次没查到 / 数据是旧的：页头副行据此改口径（见 [quotaUpdatedText]）。 */
internal fun quotaHasFailure(states: Map<String, QuotaRepository.ProviderState>): Boolean =
    states.values.any { it.errorMessage != null || it.stale }

/** 最近一次成功拉取时间（跨账号取最大；都没拉过为 0）。 */
internal fun quotaLatestFetch(states: Map<String, QuotaRepository.ProviderState>): Long =
    states.values.maxOfOrNull { it.fetchedAtEpochSecond } ?: 0L
