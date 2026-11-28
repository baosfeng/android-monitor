package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.quota.QuotaRepository
import androidx.compose.ui.text.PlatformTextStyle
import androidx.compose.ui.text.TextStyle
import com.bsfeng.monitor.ui.components.BadgeTone

/**
 * s19 账号行的展示态：状态角标 + 副行文案（纯函数，可 JVM 单测）。
 *
 * 数据来自 [QuotaRepository.ProviderState]（ViewModel 已暴露 providerStates），
 * 不新增 ViewModel 字段：本轮只读它已有的状态，避免为一个副行改 VM 与数据层。
 */
internal data class AccountStatusText(
    val badge: String,
    val tone: BadgeTone,
    val subtitle: String,
)

/** 「X 分钟前更新」里的时间部分；[withSuffix] = false 时给「停在 …前」复用。 */
internal fun agoText(seconds: Long): String {
    val safe = seconds.coerceAtLeast(0)
    if (safe < 60L) return "刚刚"
    val minutes = safe / 60
    if (minutes < 60L) return minutes.toString() + " 分钟"
    val hours = minutes / 60
    val rest = minutes % 60
    if (hours < 24L) {
        return if (rest == 0L) hours.toString() + " 小时" else hours.toString() + " 小时 " + rest.toString() + " 分钟"
    }
    return (hours / 24).toString() + " 天"
}

/** 主窗口摘要：「每周 96%」/「余额 ¥110.00」；没有可量化窗口时为 null。 */
internal fun primaryWindowText(items: List<QuotaItem>): String? {
    val item = items.firstOrNull { it.primary } ?: items.firstOrNull() ?: return null
    val value = QuotaFormat.amount(item.remaining, item.unit)
    return item.title + " " + value
}

/**
 * 账号行状态。
 *
 * 角标优先级：未配置 → 查询失败（令牌过期是其中一类）→ 已连接。
 * 「令牌过期」只在错误原文命中鉴权类关键词时使用；其余失败老实写「查询失败」。
 */
internal fun accountStatusText(
    info: QuotaProviderInfo,
    state: QuotaRepository.ProviderState?,
    nowEpochSecond: Long,
): AccountStatusText {
    val configured = state?.let { it.balanceConfigured || it.usageConfigured } ?: info.connected
    val kind = kindLabel(info.balanceKind)
    if (!configured) {
        return when {
            info.multiAccount -> AccountStatusText(
                badge = "未配置",
                tone = BadgeTone.Warn,
                subtitle = "添加后与主号并列，不覆盖主号凭据",
            )
            else -> AccountStatusText(
                badge = "未配置",
                tone = BadgeTone.Warn,
                subtitle = kind + " · 未配置",
            )
        }
    }
    val error = state?.errorMessage
    val fetchedAt = state?.fetchedAtEpochSecond ?: 0L
    if (error != null) {
        val expired = looksLikeExpiredToken(error)
        val where = if (fetchedAt > 0) {
            "停在 " + agoText(nowEpochSecond - fetchedAt) + "前"
        } else {
            "上次拉取失败"
        }
        return AccountStatusText(
            badge = if (expired) "令牌过期" else "查询失败",
            tone = BadgeTone.Danger,
            subtitle = kind + " · " + where,
        )
    }
    val updated = if (fetchedAt > 0) " · " + agoText(nowEpochSecond - fetchedAt) + "前更新" else " · 还没取到数据"
    val window = primaryWindowText(state?.items.orEmpty())
    return AccountStatusText(
        badge = "已连接",
        tone = BadgeTone.Success,
        subtitle = kind + updated + (window?.let { " · " + it } ?: ""),
    )
}

/** 错误原文是否属于鉴权类（令牌过期 / 401 / 请重新登录）。 */
internal fun looksLikeExpiredToken(message: String): Boolean {
    val text = message.lowercase()
    return listOf("过期", "expired", "重新登录", "unauthorized", "401", "invalid token", "invalid api key")
        .any { text.contains(it) }
}

/** 账号分组标题右侧摘要：「4 个 · 2 个需处理」。 */
internal fun accountsGroupSummary(total: Int, needsAttention: Int): String =
    if (needsAttention > 0) {
        total.toString() + " 个 · " + needsAttention.toString() + " 个需处理"
    } else {
        total.toString() + " 个"
    }

/** 该账号是否「需处理」（未配置或最近一次拉取失败）。 */
internal fun accountNeedsAttention(status: AccountStatusText): Boolean = status.badge != "已连接"

/** 刷新节奏说明（原型 .rhythm）。前台是每账号设置，这里展示展开中账号的实际值。 */
internal fun rhythmText(frontMinutes: Int, backgroundMinutes: Int): String = buildString {
    append("当前：前台每 ")
    append(frontMinutes)
    append(" 分钟（Ollama 限流 10 次/分钟，跨设备共享）· ")
    if (backgroundMinutes <= 0) {
        append("后台已关闭，只在打开 App 时刷新。")
    } else {
        append("后台每 ")
        append(backgroundMinutes)
        append(" 分钟，系统 doze 可能推迟到 ")
        append(backgroundMinutes * 2)
        append(" 分钟。超过 2 倍间隔仍未更新，额度页会标「数据过期」。")
    }
}

/** 主窗口是不是余额型：账号行只显示金额、没有可比的百分比。 */
internal fun QuotaItem.isBalanceOnly(): Boolean = kind == QuotaKind.MONEY

/**
 * 原型按 CSS line-height 定行高；Compose 默认 includeFontPadding 每行多 2–3dp，
 * 单行 11sp 实测从 13.75 涨到 16.3，累计会把整页推高。需要按原型行盒量的行统一走这里。
 */
internal fun TextStyle.quotaTight(): TextStyle =
    copy(platformStyle = PlatformTextStyle(includeFontPadding = false))
