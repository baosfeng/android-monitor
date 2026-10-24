package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaProvider
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.domain.quota.QuotaUsage
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 聚合相关测试的共用夹具。
 *
 * 抽成 object 而不是各自写一份：两个测试类（映射 / 文案）构造同一批 provider 与额度项时，
 * 一旦 `QuotaItem` 或 `QuotaProvider` 的必填字段变了，只改这一处。
 */
internal object AggregateFixtures {

    /** 测试统一用东八区：文案里带 HH:mm，跟着机器时区跑会在 CI 上飘。 */
    val zone: ZoneId = ZoneId.of("Asia/Shanghai")

    fun epochOf(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Long =
        ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone)
            .toInstant().epochSecond

    fun percentItem(
        id: String,
        title: String,
        remaining: String,
        primary: Boolean = false,
    ): QuotaItem = QuotaItem(
        id = id,
        title = title,
        kind = QuotaKind.PERCENT,
        remaining = BigDecimal(remaining),
        unit = QuotaUnit.PERCENT,
        primary = primary,
    )

    /** 纯余额型：金额且没有总额（DeepSeek 的样子）。 */
    fun moneyItem(id: String, title: String, remaining: String): QuotaItem = QuotaItem(
        id = id,
        title = title,
        kind = QuotaKind.MONEY,
        remaining = BigDecimal(remaining),
        total = null,
        unit = QuotaUnit.CNY,
    )

    /** 金额型且有总额：算得出百分比。 */
    fun moneyItemWithTotal(
        id: String,
        title: String,
        remaining: String,
        total: String,
    ): QuotaItem = QuotaItem(
        id = id,
        title = title,
        kind = QuotaKind.MONEY,
        remaining = BigDecimal(remaining),
        total = BigDecimal(total),
        unit = QuotaUnit.USD,
    )

    /** 最小 provider 桩：聚合只用到 id / displayName，其余给中性默认值。 */
    fun fakeProvider(id: String, name: String): QuotaProvider = object : QuotaProvider {
        override val id: String = id
        override val displayName: String = name
        override val balanceCredentialKind: QuotaCredentialKind = QuotaCredentialKind.API_KEY
        override val usageCredentialKind: QuotaCredentialKind? = null
        override val supportsModelBreakdown: Boolean = false
        override val credentialHint: String = "API Key"
        override val credentialHelp: String = ""

        override suspend fun fetchItems(credential: String): List<QuotaItem> = emptyList()

        override suspend fun fetchUsage(credential: String, range: QuotaRange): QuotaUsage? = null
    }
}
