package com.bsfeng.monitor.widget

import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaUnit
import java.math.BigDecimal
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Locale

// 额度组件的「内容层」：账号快照 → 格子文案，尺寸 → 网格档位。全部纯函数，JVM 单测直接钉边界。
//
// 为什么不在 composable 里算：Glance 的 composition 跑在 RemoteViews 里，出错只留一张白板，
// 肉眼在桌面上几乎复核不出来；数字/时刻/选行这类口径只能靠纯函数断言。

/**
 * 组件数据：账号快照 + 最近一次成功拉取时间。
 *
 * @param configured 是否至少一个账号配了额度凭据；false = 显示「未配置额度」
 * @param accounts 已配凭据的账号（快照缺失的账号也在这里，items 为空）
 * @param fetchedAtText 最近一次成功拉取的相对时间文案（如「更新于 5 分钟前」）；从未拉取为 null
 */
data class QuotaWidgetFeed(
    val configured: Boolean,
    val accounts: List<QuotaWidgetAccount> = emptyList(),
    val fetchedAtText: String? = null,
) {
    companion object {
        /** 读盘失败 / 未装配时的安全值：组件走空态，不显示 0%。 */
        val EMPTY = QuotaWidgetFeed(configured = false)
    }
}

/**
 * 一个账号实例的组件内快照。
 *
 * @param items 该账号的额度项；纯余额型（金额且没有总额）也在里面
 * @param stale 本次刷新失败、沿用上次成功数据
 */
data class QuotaWidgetAccount(
    val instanceId: String,
    val displayName: String,
    val items: List<QuotaItem> = emptyList(),
    val stale: Boolean = false,
    val fetchedAtEpochSecond: Long = 0L,
)

/**
 * 组件里的一格。
 *
 * @param valueText 等宽数值：「12%」/「¥8.60」/「$12.00」/ 隐藏时的「¥••••」
 * @param captionText 「账号 窗口 · 重置时刻」，如「opencode 5 小时 · 今天 17:00」
 * @param fraction 0..1，横条长度；纯余额型没有分母，为 null（不画横条）
 */
data class QuotaWidgetCell(
    val instanceId: String,
    val valueText: String,
    val captionText: String,
    val fraction: Float?,
)

/**
 * 选行：一账号一格，按「该账号最紧张的可量化窗口」升序，纯余额型排在后面。
 *
 * 同比例时优先 provider 自报的主项，再按实例键兜底 —— 与主屏榜单同一套序，
 * 否则同一份数据在 App 与桌面上的先后会不一致。
 *
 * @return 至多 [limit] 格；没有任何可展示项时为空（UI 走空态）
 */
fun quotaWidgetCells(
    accounts: List<QuotaWidgetAccount>,
    limit: Int,
    nowEpochSecond: Long,
    hiddenAmounts: Boolean = false,
    captionWidthDp: Float = Float.MAX_VALUE,
    zone: ZoneId = ZoneId.systemDefault(),
): List<QuotaWidgetCell> {
    if (limit <= 0) return emptyList()
    return accounts
        .mapNotNull { cellOf(it, nowEpochSecond, hiddenAmounts, captionWidthDp, zone) }
        .sortedWith(compareBy({ it.fraction ?: Float.MAX_VALUE }, { it.instanceId }))
        .take(limit)
}

/**
 * 顶栏文案：「额度 · 更新于 4 分钟前」；数据过期时加一句，避免把旧数字当实时值。
 *
 * 从没成功拉过时说「尚未更新」而不是「更新于 —」：后者读起来像刚刚更新过。
 */
fun quotaHeaderText(fetchedAtText: String?, stale: Boolean): String = when {
    fetchedAtText.isNullOrBlank() -> "额度 · 尚未更新"
    stale -> "额度 · 数据过期 · " + fetchedAtText
    else -> "额度 · " + fetchedAtText
}

/**
 * 金额文案：按额度单位给符号，隐藏时与记账组件同一占位。
 *
 * 为什么不用现成的 [money] 一把梭：它固定 ¥，而额度的 USD（Ollama 按金额）与次数套餐（COUNT）
 * 混在同一个组件里，符号错了比不显示更误导。
 */
fun quotaAmountText(remaining: BigDecimal, unit: QuotaUnit, hidden: Boolean = false): String {
    // 隐藏金额直接复用记账组件的占位（¥••••），隐私口径全仓一处
    if (hidden) return money(0.0, hidden = true)
    val value = remaining.toDouble()
    return when (unit) {
        QuotaUnit.CNY -> money(value, hidden = false)
        QuotaUnit.USD -> "$" + String.format(Locale.US, "%,.2f", value)
        QuotaUnit.COUNT -> count(remaining.toInt()) + " 次"
        // 百分比项不该走金额分支；真遇到脏数据时按剩余百分比读，别显示成「¥88.00」
        QuotaUnit.PERCENT -> percent((value / 100.0).toFloat()).toString() + "%"
    }
}

/**
 * 重置时刻：今天 17:00 / 明天 09:00 / 周一 00:00 / 12-01；没有重置时刻返回空串。
 *
 * 一周内报星期（原型「周一 00:00」），更远只报月日（周几会重复、反而要算）；
 * 已经过了重置时刻（快照过期）只给钟点，不写「昨天」——组件上那是错误数据，不是昨天的日程。
 */
fun quotaResetText(
    nowEpochSecond: Long,
    resetsAtEpochSecond: Long,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    if (resetsAtEpochSecond <= 0L) return ""
    val reset = Instant.ofEpochSecond(resetsAtEpochSecond).atZone(zone)
    val today = Instant.ofEpochSecond(nowEpochSecond).atZone(zone).toLocalDate()
    val days = ChronoUnit.DAYS.between(today, reset.toLocalDate())
    val clock = String.format(Locale.US, "%02d:%02d", reset.hour, reset.minute)
    return when {
        days < 0L -> clock
        days == 0L -> "今天 " + clock
        days == 1L -> "明天 " + clock
        days <= 6L -> weekdayName(reset.dayOfWeek.value) + " " + clock
        else -> String.format(Locale.US, "%02d-%02d", reset.monthValue, reset.dayOfMonth)
    }
}

/** 一条账号 → 一格的文案；既没有可量化窗口、也不是纯余额型时返回 null（不进网格）。 */
private fun cellOf(
    account: QuotaWidgetAccount,
    nowEpochSecond: Long,
    hiddenAmounts: Boolean,
    captionWidthDp: Float,
    zone: ZoneId,
): QuotaWidgetCell? {
    val windows = account.items.mapNotNull { item ->
        item.remainingFraction?.let { fraction -> fraction to item }
    }
    val primary = windows.minWithOrNull(compareBy({ it.first }, { !it.second.primary }))
    if (primary != null) {
        val (fraction, item) = primary
        return QuotaWidgetCell(
            instanceId = account.instanceId,
            valueText = percent(fraction).toString() + "%",
            captionText = windowCaption(account.displayName, item, nowEpochSecond, captionWidthDp, zone),
            fraction = fraction,
        )
    }
    val balance = account.items.firstOrNull { it.isPlainBalance } ?: return null
    return QuotaWidgetCell(
        instanceId = account.instanceId,
        valueText = quotaAmountText(balance.remaining, balance.unit, hiddenAmounts),
        captionText = joinCaption(account.displayName, balance.title),
        fraction = null,
    )
}

private fun windowCaption(
    displayName: String,
    item: QuotaItem,
    nowEpochSecond: Long,
    captionWidthDp: Float,
    zone: ZoneId,
): String {
    val resetAt = item.resetsAtEpochSecond ?: item.period?.endEpochSecond ?: 0L
    return quotaCaptionText(
        displayName = displayName,
        windowTitle = item.title,
        resetText = quotaResetText(nowEpochSecond, resetAt, zone),
        availableWidthDp = captionWidthDp,
    )
}

/**
 * 说明行：「账号 窗口 · 重置时刻」；一格放不下整段时**整段省掉重置时刻**，只留「账号 窗口」。
 *
 * 为什么不是让系统按可用宽 ellipsis：截断点正好落在「·」后面，桌面上会出现「open-design 本月 ·」
 * 这种半截分隔符（250×110 声明最小箱实测就是这样），比不显示时刻更像渲染坏了。
 * 阈值 120dp：10sp 下「账号 窗口 · HH:mm」实测 107dp、「账号 窗口 · 今天 HH:mm」129dp，
 * 取中间值，宽箱（4×2 真机 157dp）保留完整写法。
 */
fun quotaCaptionText(
    displayName: String,
    windowTitle: String,
    resetText: String,
    availableWidthDp: Float,
): String {
    val base = joinCaption(displayName, windowTitle)
    if (resetText.isEmpty() || availableWidthDp < CAPTION_FULL_MIN_WIDTH_DP) return base
    return base + " · " + resetText
}

/** 完整说明行（带重置时刻）至少需要的格子宽度。 */
internal const val CAPTION_FULL_MIN_WIDTH_DP = 120f

private fun joinCaption(displayName: String, windowTitle: String): String = when {
    windowTitle.isBlank() -> displayName
    displayName.isBlank() -> windowTitle
    else -> displayName + " " + windowTitle
}

private fun weekdayName(isoDayOfWeek: Int): String = when (isoDayOfWeek) {
    1 -> "周一"
    2 -> "周二"
    3 -> "周三"
    4 -> "周四"
    5 -> "周五"
    6 -> "周六"
    else -> "周日"
}