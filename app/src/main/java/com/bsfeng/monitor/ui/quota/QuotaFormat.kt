package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.domain.quota.OllamaPlanMode
import com.bsfeng.monitor.domain.quota.QuotaBucket
import com.bsfeng.monitor.domain.quota.QuotaGranularity
import com.bsfeng.monitor.domain.quota.QuotaGroup
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaMetrics
import com.bsfeng.monitor.domain.quota.QuotaPeriod
import com.bsfeng.monitor.domain.quota.QuotaUnit
import java.math.BigDecimal
import java.math.RoundingMode
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import kotlin.math.roundToInt

/**
 * 额度页的全部文案与几何口径（纯函数，可 JVM 单测）。
 *
 * 为什么单独成文件：金额走 BigDecimal、Token 走「万」压缩、堆叠柱要归一化、
 * 按模型列表要聚合「其他」—— 这些一旦写在组合里就只能靠真机肉眼验，写错了
 * 表现为「数字对不上」而不是崩溃，最难发现。放进纯函数后用测试钉住。
 */

/** 「按模型」一行：Top 3 + 其它聚合行共用。 */
data class ModelUsageRow(
    val name: String,
    val metrics: QuotaMetrics,
    val isOther: Boolean = false,
)

/** Token 堆叠柱的一根柱：总高占比 + 其中输入占比（输出占剩余）。 */
data class StackedBar(
    val heightFraction: Float,
    val inputFraction: Float,
)

object QuotaFormat {

    /** 千分位定长小数；每主题每帧最多几个数值，创建 DecimalFormat 的代价可忽略。 */
    private fun grouped(value: BigDecimal, decimals: Int): String {
        val symbols = DecimalFormatSymbols(Locale.US)
        val pattern = "#,##0" + if (decimals > 0) "." + "0".repeat(decimals) else ""
        return DecimalFormat(pattern, symbols).apply {
            roundingMode = RoundingMode.HALF_UP
        }.format(value)
    }

    /**
     * 金额 / 百分比 / 次数统一入口。
     *
     * 不在此处做币种换算：DeepSeek 的 CNY 与 USD 是两个独立余额，
     * 相加得到的是没有意义的总数（口径见 QuotaModels 头部注释）。
     */
    fun amount(value: BigDecimal, unit: QuotaUnit): String = when (unit) {
        QuotaUnit.PERCENT -> percent(value)
        QuotaUnit.COUNT -> count(value.toInt())
        QuotaUnit.CNY, QuotaUnit.USD -> unit.symbol + grouped(value, 2)
    }

    /**
     * 花费：至少两位、必要时三位（原型 `¥0.36` 与 `$0.017` 并存）。
     * 统一两位会把「几分钱」显示成 `$0.02`，看起来像没花钱；统一三位又会让整数金额拖个 0。
     */
    fun cost(value: BigDecimal, unit: QuotaUnit): String {
        if (unit == QuotaUnit.PERCENT || unit == QuotaUnit.COUNT) return amount(value, unit)
        val symbols = DecimalFormatSymbols(Locale.US)
        val text = DecimalFormat("#,##0.00#", symbols).apply {
            roundingMode = RoundingMode.HALF_UP
        }.format(value)
        return unit.symbol + text
    }

    /** 百分比去尾零：75.00 → `75%`，72.50 → `72.5%`。 */
    fun percent(value: BigDecimal): String =
        value.setScale(1, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString() + "%"

    /** Token 数：不足一万按整数千分位，过万压缩成「21.9 万」（卡片宽度只有一屏）。 */
    fun tokens(value: Long?): String {
        if (value == null) return "—"
        if (value < 10_000L) return grouped(BigDecimal(value), 0)
        val wan = BigDecimal(value).divide(BigDecimal(10_000), 1, RoundingMode.HALF_UP)
        return wan.stripTrailingZeros().toPlainString() + " 万"
    }

    /** 请求次数。 */
    fun count(value: Int): String = grouped(BigDecimal(value), 0) + " 次"

    /** 月/日；跨时区只在展示层换算（领域模型一律 epoch 秒）。 */
    fun dateOf(epochSecond: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val time = Instant.ofEpochSecond(epochSecond).atZone(zone)
        return "${time.monthValue}/${time.dayOfMonth}"
    }

    /** 时:分（两位补零），失败时间戳与重置时刻共用。 */
    fun clockOf(epochSecond: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val time = Instant.ofEpochSecond(epochSecond).atZone(zone)
        return "%02d:%02d".format(Locale.US, time.hour, time.minute)
    }

    fun dateTimeOf(epochSecond: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dateOf(epochSecond, zone) + " " + clockOf(epochSecond, zone)

    /**
     * 周期文案：带「剩几天」时为「本周期剩 18 天 · 10/27 重置」，
     * 只给 resetsAt 时退化为「10/01 07:00 重置」（旧版百分比计划没有周期对象）。
     */
    fun resetText(
        period: QuotaPeriod?,
        resetsAtEpochSecond: Long?,
        nowEpochSecond: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String? {
        val end = resetsAtEpochSecond ?: period?.endEpochSecond ?: return null
        val days = period?.remainingDays(nowEpochSecond) ?: 0
        return if (period != null && days > 0) {
            "本周期剩 $days 天 · ${dateOf(end, zone)} 重置"
        } else {
            "${dateTimeOf(end, zone)} 重置"
        }
    }

    /** 分桶轴标签：小时桶取「HH」，天桶取「M/d」。 */
    fun bucketLabel(
        bucket: QuotaBucket,
        granularity: QuotaGranularity,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String = when (granularity) {
        QuotaGranularity.HOUR -> clockOf(bucket.startEpochSecond, zone).substring(0, 2)
        QuotaGranularity.DAY -> dateOf(bucket.startEpochSecond, zone)
    }

    /**
     * 轴标签降采样到最多 [max] 个：24 个小时桶全画会挤成墨块，
     * 原型是每 2 小时一个（12 个），这里按等距取点并保持首尾。
     */
    fun sampleLabels(labels: List<String>, max: Int = 6): List<String> {
        if (labels.size <= max || max < 2) return labels
        val step = (labels.size - 1).toFloat() / (max - 1)
        return (0 until max).map { labels[(it * step).roundToInt().coerceIn(0, labels.lastIndex)] }
    }

    /**
     * 按模型 Top N + 「其他」聚合。
     *
     * 超过 N 条时必须合并成一行：原型固定三行 + 其他，列表无限增长会把用量卡撑到两屏。
     * 排序以请求次数为主、Token 为次，与卡片右侧展示的成本无关（成本可能缺字段）。
     */
    fun topGroups(groups: List<QuotaGroup>, limit: Int = 3): List<ModelUsageRow> {
        val sorted = groups.sortedWith(
            compareByDescending<QuotaGroup> { it.metrics.requestCount }
                .thenByDescending { it.metrics.inputTokens ?: 0L }
                .thenBy { it.name },
        )
        if (sorted.size <= limit) {
            return sorted.map { ModelUsageRow(it.name, it.metrics) }
        }
        val head = sorted.take(limit).map { ModelUsageRow(it.name, it.metrics) }
        return head + ModelUsageRow("其他", mergeMetrics(sorted.drop(limit).map { it.metrics }), isOther = true)
    }

    /** 聚合行的指标：缺失字段保持 null，避免把「没数据」写成 0。 */
    fun mergeMetrics(parts: List<QuotaMetrics>): QuotaMetrics = QuotaMetrics(
        requestCount = parts.sumOf { it.requestCount },
        inputTokens = sumLongs(parts.map { it.inputTokens }),
        cachedInputTokens = sumLongs(parts.map { it.cachedInputTokens }),
        outputTokens = sumLongs(parts.map { it.outputTokens }),
        cost = sumMoney(parts.map { it.cost }),
    )

    /**
     * Token 堆叠柱归一化：柱高按**所有桶的最大总量**缩放（不是 min-max），
     * 否则最矮的那根会被拉到与最高齐平，看不出趋势。
     */
    fun stackedBars(inputs: List<Long?>, outputs: List<Long?>): List<StackedBar> {
        val size = maxOf(inputs.size, outputs.size)
        if (size == 0) return emptyList()
        val pairs = (0 until size).map { index ->
            (inputs.getOrNull(index) ?: 0L).coerceAtLeast(0L) to
                (outputs.getOrNull(index) ?: 0L).coerceAtLeast(0L)
        }
        val maxTotal = pairs.maxOf { it.first + it.second }
        return pairs.map { (input, output) ->
            val total = input + output
            StackedBar(
                heightFraction = if (maxTotal <= 0L) 0f else total.toFloat() / maxTotal,
                inputFraction = if (total <= 0L) 0f else input.toFloat() / total,
            )
        }
    }

    /**
     * 所选计费模式与返回结构不一致时的提示。
     *
     * 计费模式是用户手选的（Ollama 新旧两种计划返回的 JSON 结构不同，凭据相同无法自动判别），
     * 所以不能悄悄按返回结构切换展示——那会让「我选过金额」这件事失效；提示一句、
     * 仍按实际结构展示，是唯一不撒谎的做法。
     *
     * 这个偏好在设置里只属于 Ollama：其它 provider 的返回结构由各自接口固定
     * （opencode-go 恒为百分比、DeepSeek 恒为用量），拿 Ollama 的选择去比对它们
     * 会凭空冒出一句「与所选不一致」，所以非 Ollama 一律不提示。
     */
    fun planMismatchNote(
        plan: OllamaPlanMode,
        items: List<QuotaItem>,
        providerId: String = OLLAMA_PROVIDER_ID,
    ): String? {
        if (items.isEmpty() || providerId != OLLAMA_PROVIDER_ID) return null
        val hasMoney = items.any { it.kind == QuotaKind.MONEY }
        val hasPercent = items.any { it.kind == QuotaKind.PERCENT }
        return when {
            plan == OllamaPlanMode.USD && !hasMoney ->
                "接口返回的是按百分比限额结构，与所选「按金额」不一致；卡片按实际结构展示"
            plan == OllamaPlanMode.PERCENT && !hasPercent ->
                "接口返回的是金额结构，与所选「按百分比限额」不一致；卡片按实际结构展示"
            else -> null
        }
    }

    private fun sumLongs(values: List<Long?>): Long? =
        if (values.all { it == null }) null else values.sumOf { it ?: 0L }

    /** 计费模式归属的 provider（见 [planMismatchNote]）。 */
    const val OLLAMA_PROVIDER_ID: String = "ollama"

    private fun sumMoney(values: List<BigDecimal?>): BigDecimal? =
        if (values.all { it == null }) null else values.fold(BigDecimal.ZERO) { acc, v ->
            acc + (v ?: BigDecimal.ZERO)
        }
}
