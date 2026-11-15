package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import com.bsfeng.monitor.domain.quota.QuotaInstance
import com.bsfeng.monitor.domain.quota.QuotaInstances
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaProvider
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.domain.quota.QuotaUsage
import com.bsfeng.monitor.quota.QuotaRepository
import java.math.BigDecimal
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * 聚合相关测试的共用夹具。
 *
 * 抽成 object 而不是各自写一份：多个测试类（映射 / 文案 / 状态）构造同一批 provider 与实例时，
 * 一旦 `QuotaItem` / `QuotaProvider` / `QuotaInstance` 的必填字段变了，只改这一处。
 *
 * 口径：界面上一行 = 一个**账号实例**。单账号用例走 [instanceOf] 的默认值
 * （instanceId == providerTypeId，即零迁移的默认单实例），多账号用例显式传 `#2` 这类 id。
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

    /** 账号实例：默认就是该类型的第一个实例（instanceId == providerTypeId，零迁移口径）。 */
    fun instanceOf(providerTypeId: String, seq: Int = 1, label: String = ""): QuotaInstance =
        if (seq <= 1) {
            QuotaInstance(providerTypeId, providerTypeId, label)
        } else {
            QuotaInstance("$providerTypeId#$seq", providerTypeId, label)
        }

    /** 一批单账号实例（每个 provider 一个默认实例）。 */
    fun instancesOf(providers: List<QuotaProvider>): List<QuotaInstance> =
        providers.map { QuotaInstances.defaultInstance(it.id) }

    /** 单账号聚合：每个 provider 一个默认实例，测试只关心内容不关心多账号。 */
    fun snapshotOf(
        providers: List<QuotaProvider>,
        states: Map<String, QuotaRepository.ProviderState>,
    ): QuotaAggregateSnapshot = buildAggregateSnapshot(instancesOf(providers), providers, states)

    /** 单账号映射：默认实例 + 非多账号口径。 */
    fun inputOf(
        provider: QuotaProvider,
        state: QuotaRepository.ProviderState?,
    ): com.bsfeng.monitor.domain.quota.ProviderAggregateInput =
        aggregateInputOf(instanceOf(provider.id), provider, state, multiAccount = false)

    /** 单账号的 state 构造：键与实例键一致，省掉每处手写两遍。 */
    fun stateOf(
        instanceId: String,
        items: List<QuotaItem> = emptyList(),
        balanceConfigured: Boolean = false,
        errorMessage: String? = null,
        stale: Boolean = false,
        fetchedAtEpochSecond: Long = 0L,
        loading: Boolean = false,
    ): QuotaRepository.ProviderState = QuotaRepository.ProviderState(
        instanceId = instanceId,
        providerTypeId = instanceId.substringBefore('#'),
        items = items,
        balanceConfigured = balanceConfigured,
        errorMessage = errorMessage,
        stale = stale,
        fetchedAtEpochSecond = fetchedAtEpochSecond,
        loading = loading,
    )
}
