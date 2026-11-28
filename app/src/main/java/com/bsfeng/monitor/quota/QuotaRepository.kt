package com.bsfeng.monitor.quota

import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import com.bsfeng.monitor.domain.quota.QuotaError
import com.bsfeng.monitor.domain.quota.QuotaInstance
import com.bsfeng.monitor.domain.quota.QuotaInstances
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaProvider
import com.bsfeng.monitor.domain.quota.QuotaProviderRegistry
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.domain.quota.QuotaSnapshot
import com.bsfeng.monitor.domain.quota.QuotaUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

/**
 * 额度状态的单一数据源 —— 拉取、节流、退避、快照缓存与预警触发都在这里。
 *
 * 不依赖 Android 的 Context（凭据存储与通知是构造注入的），便于 JVM 单测。
 *
 * 口径：
 * - **所有键都是实例键**（[QuotaInstance.instanceId]），不是 provider 类型 id：
 *   同一 provider 挂两个账号时，用类型 id 当键会让第二个账号被第一个的锁 / 节流 / 429 退避拖死，
 *   表现是「加了账号但数字永远不动」；
 * - 每个实例独立节流（Ollama 限流 10 次/分钟且跨设备共享）；
 * - 429 按 `Retry-After` 退避，退避期内不发起请求；
 * - 额度与用量并行拉取，一个失败不影响另一个（失败项保留上次成功值并标记 stale）；
 * - 额度拉取成功后评估阈值提醒（同一次多点命中合并成一条，按实例发）。
 */
class QuotaRepository(
    private val registry: QuotaProviderRegistry,
    private val credentials: QuotaCredentialStore,
    private val notifier: QuotaAlertNotifier? = null,
    private val nowSeconds: () -> Long = { System.currentTimeMillis() / 1000 },
    /** 错误日志出口；默认丢弃，注入后写入 logcat（保留纯 JVM 可测性）。 */
    private val logger: (String) -> Unit = {},
    /** 上次成功快照的本地缓存：冷启动先回显，避免「首次进页面正在请求」时白屏。 */
    private val cache: QuotaSnapshotCache = NoQuotaSnapshotCache,
    /** 账号实例清单：空清单时按 registry 合成默认单实例（零迁移）。 */
    catalog: QuotaInstanceCatalog = InMemoryQuotaInstanceCatalog(registry.all().map { it.id }),
) {

    /** 单个实例（账号）的界面状态。 */
    data class ProviderState(
        /** 实例键（凭据 / 快照 / 状态 / 通知都按它）。 */
        val instanceId: String,
        /** provider 类型 id：卡片排版、限流口径、锚点分流都用它。 */
        val providerTypeId: String = instanceId,
        val items: List<QuotaItem> = emptyList(),
        val usage: QuotaUsage? = null,
        val fetchedAtEpochSecond: Long = 0L,
        val loading: Boolean = false,
        val errorMessage: String? = null,
        val stale: Boolean = false,
        val balanceConfigured: Boolean = false,
        val usageConfigured: Boolean = false,
        /** 用量范围：缺省 7 天，与原型 s18 的展开图一致（r5 前靠在首次展开时偷偷切一次来凑）。 */
        val range: QuotaRange = QuotaRange.LAST_7_DAYS,
    )

    private val _states = MutableStateFlow<Map<String, ProviderState>>(emptyMap())
    val states: StateFlow<Map<String, ProviderState>> = _states.asStateFlow()

    private val _instances = MutableStateFlow(emptyList<QuotaInstance>())

    /** 账号实例清单（按类型注册顺序分组，组内按创建顺序）。 */
    val instances: StateFlow<List<QuotaInstance>> = _instances.asStateFlow()

    private val bookkeeping = QuotaRefreshBookkeeping(nowSeconds)

    private val admin = QuotaInstanceAdmin(
        catalog = catalog,
        credentials = credentials,
        cache = cache,
        bookkeeping = bookkeeping,
        instances = _instances,
        states = _states,
        initialState = ::initial,
    )

    /** 刷新间隔（秒），按 provider **类型** 由设置页决定；默认 60 秒。 */
    var intervalSeconds: (String) -> Long = { 60L }

    init {
        val initialInstances = catalog.instances()
        _instances.value = initialInstances
        initialInstances.forEach { instance ->
            val cached = cache.load(instance.instanceId)
            _states.update {
                it + (instance.instanceId to initial(instance).copy(
                    items = cached?.items.orEmpty(),
                    usage = cached?.usage,
                    fetchedAtEpochSecond = cached?.fetchedAtEpochSecond ?: 0L,
                    // 缓存是上一次成功的结果：先当「陈旧数据」显示，刷新成功后转正
                    stale = cached != null,
                ))
            }
        }
    }

    private fun initial(instance: QuotaInstance): ProviderState {
        val provider = registry.byId(instance.providerTypeId)
        return ProviderState(
            instanceId = instance.instanceId,
            providerTypeId = instance.providerTypeId,
            balanceConfigured = provider != null &&
                credentials.has(instance.instanceId, provider.balanceCredentialKind),
            usageConfigured = provider?.usageCredentialKind
                ?.let { credentials.has(instance.instanceId, it) } ?: false,
        )
    }

    /** 当前注册的 provider 类型（注册顺序）。 */
    fun providers(): List<QuotaProvider> = registry.all()

    /** 按**类型**查 provider 实现。 */
    fun provider(providerTypeId: String): QuotaProvider? = registry.byId(providerTypeId)

    /** 按实例查它对应的 provider 实现。 */
    fun providerOf(instanceId: String): QuotaProvider? =
        instance(instanceId)?.let { registry.byId(it.providerTypeId) }

    fun instance(instanceId: String): QuotaInstance? =
        _instances.value.firstOrNull { it.instanceId == instanceId }

    fun state(instanceId: String): ProviderState =
        _states.value[instanceId] ?: ProviderState(instanceId)

    // MARK: - 账号实例的增删改（实现在 [QuotaInstanceAdmin]）

    /** 新增一个账号：落清单、建空状态；没有凭据，不发请求。 */
    fun addInstance(providerTypeId: String): QuotaInstance? = admin.add(providerTypeId)

    fun renameInstance(instanceId: String, label: String) = admin.rename(instanceId, label)

    /** 删账号（清凭据 + 快照 + 状态）；只剩一个账号时返回 false。 */
    suspend fun deleteInstance(instanceId: String): Boolean = admin.remove(instanceId)

    // MARK: - 凭据

    /** 保存凭据并立即刷新。 */
    suspend fun saveCredential(instanceId: String, kind: QuotaCredentialKind, value: String?) {
        credentials.save(instanceId, kind, value)
        refresh(instanceId, force = true)
    }

    /**
     * 断开某个账号：清凭据 + 清快照 + 状态回落到「未配置」。
     *
     * 与「把输入框清空再保存」的区别：那条路只在填了空串时生效，且**不清快照** ——
     * 仓库冷启动/重进页面会读回上次快照并标 stale，卡片继续显示旧数字，看起来像断开没生效。
     * 所以这里必须连 [QuotaSnapshotCache] 里这个账号的快照一起清掉。
     *
     * 不清的东西（都是用户偏好，不是凭据）：刷新间隔、用量范围、预警阈值、Ollama 计费模式。
     * 节流与退避要清：下次重新填 Key 时应当立刻能查，而不是等一个上家的冷却窗口。
     */
    suspend fun clearCredentials(instanceId: String) {
        bookkeeping.lockOf(instanceId).withLock {
            // 唯一真值源是凭据：先落盘再清缓存，中途异常也不会留下「有快照没凭据」的半态
            admin.forget(instanceId)
            update(instanceId) {
                it.copy(
                    items = emptyList(),
                    usage = null,
                    fetchedAtEpochSecond = 0L,
                    loading = false,
                    errorMessage = null,
                    stale = false,
                    balanceConfigured = false,
                    usageConfigured = false,
                )
            }
        }
    }

    /**
     * 切换用量范围：只重拉用量，避免打满限流。
     *
     * 同范围不是无条件短路：这条还没拿到用量（首次展开）时要补拉一次，
     * 否则「展开后柱图空白」永远等不到数据（r5 修；旧实现 request 恒等于 current，直接 return）。
     */
    suspend fun setRange(instanceId: String, range: QuotaRange) {
        val provider = providerOf(instanceId) ?: return
        val current = state(instanceId)
        if (current.range == range && (current.usage != null || !provider.supportsUsage)) return
        update(instanceId) { it.copy(range = range) }
        if (!provider.supportsUsage || !current.usageConfigured) return
        val succeeded = fetchUsageOnly(instanceId, provider, range)
        val settled = rangeAfterUsageAttempt(previous = current.range, requested = range, succeeded = succeeded)
        if (settled != range) update(instanceId) { it.copy(range = settled) }
    }

    // MARK: - 刷新

    /**
     * 刷新某个实例。
     *
     * @param force 跳过节流与退避（进页面、保存凭据、点刷新时用）
     */
    suspend fun refresh(instanceId: String, force: Boolean = false) {
        val provider = providerOf(instanceId) ?: return
        bookkeeping.lockOf(instanceId).withLock {
            if (!force && !bookkeeping.allows(instanceId, intervalSeconds(instanceId))) return
            val current = state(instanceId)
            if (current.loading) return
            val balanceKey = credentials.load(instanceId, provider.balanceCredentialKind)
            val usageKey = provider.usageCredentialKind?.let { credentials.load(instanceId, it) }
            if (balanceKey == null && usageKey == null) {
                update(instanceId) {
                    it.copy(
                        loading = false,
                        errorMessage = null,
                        balanceConfigured = false,
                        usageConfigured = false,
                    )
                }
                return
            }

            bookkeeping.markRefreshed(instanceId)
            update(instanceId) {
                it.copy(
                    loading = true,
                    errorMessage = null,
                    balanceConfigured = balanceKey != null,
                    usageConfigured = usageKey != null,
                )
            }

            val result = fetchBoth(instanceId, provider, current, balanceKey, usageKey)
            persist(instanceId, provider, result)
        }
    }

    /** 刷新所有账号（后台定时任务用）。 */
    suspend fun refreshAll() {
        _instances.value.map { it.instanceId }.forEach { instanceId ->
            runCatching { refresh(instanceId, force = false) }
        }
    }

    /** 一次刷新的结果：额度 / 用量 / 失败原因 / 是否有失败项。 */
    private data class FetchResult(
        val items: List<QuotaItem>,
        val usage: QuotaUsage?,
        val failure: String?,
        val failed: Boolean,
    )

    /**
     * 额度与用量并行拉取：一个失败不影响另一个。
     *
     * 额度成功后立即评估阈值提醒（按实例发，标题带账号名，见 [notifierTitleOf]）；
     * 用量失败只记原因，不改写刚拿到的额度成 stale。
     */
    private suspend fun fetchBoth(
        instanceId: String,
        provider: QuotaProvider,
        current: ProviderState,
        balanceKey: String?,
        usageKey: String?,
    ): FetchResult {
        var items = current.items
        var usage = current.usage
        var failure: String? = null
        var failed = false

        if (balanceKey != null) {
            runCatching { provider.fetchItems(balanceKey) }
                .onSuccess { fresh ->
                    items = fresh
                    // 这一次拉取成功即视为整体正常：用量的失败不该把刚拿到的额度标成 stale
                    failed = false
                    failure = null
                    notifier?.handle(instanceId, notifierTitleOf(instanceId), fresh)
                }
                .onFailure { error ->
                    failed = true
                    failure = (error as? QuotaError)?.userMessage ?: error.message ?: "查询失败"
                    logger("额度拉取失败[${instanceId}/balance]：${error.summaryForLog()}")
                    bookkeeping.backoff(instanceId, error)
                }
        }
        if (provider.supportsUsage && usageKey != null) {
            runCatching { provider.fetchUsage(usageKey, current.range) }
                .onSuccess { usage = it }
                .onFailure { error ->
                    failed = true
                    if (failure == null) {
                        failure = (error as? QuotaError)?.userMessage ?: error.message ?: "查询失败"
                    }
                    logger("额度拉取失败[${instanceId}/usage]：${error.summaryForLog()}")
                    bookkeeping.backoff(instanceId, error)
                }
        }
        return FetchResult(items, usage, failure, failed)
    }

    /** 落盘 + 更新状态：只在「没失败且有数据」时写快照，失败保留旧快照。 */
    private fun persist(instanceId: String, provider: QuotaProvider, result: FetchResult) {
        val nowAfter = nowSeconds()
        val hasData = result.items.isNotEmpty() || result.usage != null
        if (!result.failed && hasData) {
            cache.save(
                QuotaSnapshot(
                    providerId = instanceId,
                    providerTypeId = provider.id,
                    items = result.items,
                    usage = result.usage,
                    fetchedAtEpochSecond = nowAfter,
                ),
            )
        }
        update(instanceId) {
            it.copy(
                items = result.items,
                usage = result.usage,
                loading = false,
                fetchedAtEpochSecond = if (result.failed) it.fetchedAtEpochSecond else nowAfter,
                stale = result.failed && hasData,
                errorMessage = result.failure,
            )
        }
    }

    /**
     * 只重拉用量（范围切换用）。
     *
     * @return 是否成功；调用方据此回滚范围（见 [rangeAfterUsageAttempt] 与 [setRange]）
     */
    private suspend fun fetchUsageOnly(
        instanceId: String,
        provider: QuotaProvider,
        range: QuotaRange,
    ): Boolean {
        val kind = provider.usageCredentialKind ?: return false
        val key = credentials.load(instanceId, kind) ?: return false
        update(instanceId) { it.copy(loading = true) }
        return runCatching { provider.fetchUsage(key, range) }
            .onSuccess { usage ->
                update(instanceId) {
                    it.copy(
                        usage = usage,
                        loading = false,
                        fetchedAtEpochSecond = nowSeconds(),
                        errorMessage = null,
                    )
                }
            }
            .onFailure { error ->
                update(instanceId) {
                    it.copy(
                        loading = false,
                        errorMessage = (error as? QuotaError)?.userMessage ?: error.message,
                    )
                }
                bookkeeping.backoff(instanceId, error)
            }
            .isSuccess
    }

    private fun update(instanceId: String, transform: (ProviderState) -> ProviderState) {
        _states.update { map ->
            val existing = map[instanceId] ?: state(instanceId)
            map + (instanceId to transform(existing))
        }
    }

    /** 通知标题里的账号名：同类型只有一个账号时就是类型名，多账号时带账号名。 */
    private fun notifierTitleOf(instanceId: String): String {
        val instance = instance(instanceId) ?: return instanceId
        val provider = registry.byId(instance.providerTypeId) ?: return instanceId
        val multi = _instances.value.count { it.providerTypeId == instance.providerTypeId } > 1
        return QuotaInstances.displayName(instance, provider.displayName, multi)
    }
}

/**
 * 范围切换请求结束后 state 里的范围该是哪一个。
 *
 * 成功 = 新范围；失败 = 旧范围（数据没换成新的，选中态也不能换）。
 * 纯函数，便于把「失败必须回滚」直接单测钉死，而不依赖 Android 的凭据存储。
 */
internal fun rangeAfterUsageAttempt(
    previous: QuotaRange,
    requested: QuotaRange,
    succeeded: Boolean,
): QuotaRange = if (succeeded) requested else previous

/** 日志用摘要：带上错误种类与原始消息（消息里不含凭据，QuotaHttp 已保证）。 */
private fun Throwable.summaryForLog(): String =
    (this as? QuotaError)?.let { "${it.kind} ${it.message.orEmpty()}" } ?: (message ?: javaClass.simpleName)
