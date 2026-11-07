package com.bsfeng.monitor.quota

import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import com.bsfeng.monitor.domain.quota.QuotaError
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaProvider
import com.bsfeng.monitor.domain.quota.QuotaProviderRegistry
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.domain.quota.QuotaUsage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * 额度状态的单一数据源 —— 拉取、节流、退避、快照缓存与预警触发都在这里。
 *
 * 不依赖 Android 的 Context（凭据存储与通知是构造注入的），便于 JVM 单测。
 *
 * 口径：
 * - 每个 provider 独立节流 60 秒（Ollama 限流 10 次/分钟且跨设备共享）；
 * - 429 按 `Retry-After` 退避，退避期内不发起请求；
 * - 额度与用量并行拉取，一个失败不影响另一个（失败项保留上次成功值并标记 stale）；
 * - 额度拉取成功后评估阈值提醒（同一次多点命中合并成一条）。
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
) {

    /** 单个 provider 的界面状态。 */
    data class ProviderState(
        val providerId: String,
        val items: List<QuotaItem> = emptyList(),
        val usage: QuotaUsage? = null,
        val fetchedAtEpochSecond: Long = 0L,
        val loading: Boolean = false,
        val errorMessage: String? = null,
        val stale: Boolean = false,
        val balanceConfigured: Boolean = false,
        val usageConfigured: Boolean = false,
        val range: QuotaRange = QuotaRange.LAST_24_HOURS,
    )

    private val _states = MutableStateFlow<Map<String, ProviderState>>(emptyMap())
    val states: StateFlow<Map<String, ProviderState>> = _states.asStateFlow()

    /** 每个 provider 一把锁，避免同一 provider 并发拉取。 */
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** 上次发起请求的时刻（节流基准）。 */
    private val lastRefreshAt = ConcurrentHashMap<String, Long>()

    /** 限流退避截止时刻。 */
    private val retryNotBefore = ConcurrentHashMap<String, Long>()

    /** 刷新间隔（秒），按 provider 由设置页决定；默认 60 秒。 */
    var intervalSeconds: (String) -> Long = { 60L }

    init {
        registry.all().forEach { provider ->
            val cached = cache.load(provider.id)
            _states.update {
                it + (provider.id to initial(provider).copy(
                    items = cached?.items.orEmpty(),
                    usage = cached?.usage,
                    fetchedAtEpochSecond = cached?.fetchedAtEpochSecond ?: 0L,
                    // 缓存是上一次成功的结果：先当「陈旧数据」显示，刷新成功后转正
                    stale = cached != null,
                ))
            }
        }
    }

    private fun initial(provider: QuotaProvider): ProviderState = ProviderState(
        providerId = provider.id,
        balanceConfigured = credentials.has(provider.id, provider.balanceCredentialKind),
        usageConfigured = provider.usageCredentialKind?.let { credentials.has(provider.id, it) } ?: false,
    )

    /** 当前注册的 provider（注册顺序）。 */
    fun providers(): List<QuotaProvider> = registry.all()

    fun provider(id: String): QuotaProvider? = registry.byId(id)

    fun state(providerId: String): ProviderState =
        _states.value[providerId] ?: ProviderState(providerId)

    /** 保存凭据并立即刷新。 */
    suspend fun saveCredential(providerId: String, kind: QuotaCredentialKind, value: String?) {
        credentials.save(providerId, kind, value)
        refresh(providerId, force = true)
    }

    /**
     * 断开某个 provider：清凭据 + 清快照 + 状态回落到「未配置」。
     *
     * 与「把输入框清空再保存」的区别：那条路只在填了空串时生效，且**不清快照** ——
     * 仓库冷启动/重进页面会读回上次快照并标 stale，卡片继续显示旧数字，看起来像断开没生效。
     * 所以这里必须连 [QuotaSnapshotCache] 里这一家的快照一起清掉。
     *
     * 不清的东西（都是用户偏好，不是凭据）：刷新间隔、用量范围、预警阈值、Ollama 计费模式。
     * 节流与退避要清：下次重新填 Key 时应当立刻能查，而不是等一个上家的冷却窗口。
     *
     * 与 [refresh] 共用同一把 provider 锁：避免「清到一半正好有一次刷新在跑，把旧快照又写回来」。
     * 不发请求 —— 凭据都没了，refresh 只会走「两个都为 null」的提前返回分支。
     */
    suspend fun clearCredentials(providerId: String) {
        val lock = locks.getOrPut(providerId) { Mutex() }
        lock.withLock {
            credentials.clearAll(providerId)
            // 唯一真值源是凭据：先落盘再清缓存，中途异常也不会留下「有快照没凭据」的半态
            cache.clear(providerId)
            lastRefreshAt.remove(providerId)
            retryNotBefore.remove(providerId)
            update(providerId) {
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
     * 失败回滚范围：用量数据仍是旧范围的，选中态必须跟着数据走 —— 否则会出现「近 30 天」的
     * 分段配着 24 小时的数字。失败原因写进 errorMessage，UI 的错误条
     * 同时说明「数据停在 HH:mm」。
     */
    suspend fun setRange(providerId: String, range: QuotaRange) {
        val provider = registry.byId(providerId) ?: return
        val current = state(providerId)
        if (current.range == range) return
        update(providerId) { it.copy(range = range) }
        if (!provider.supportsUsage || !current.usageConfigured) return
        val succeeded = fetchUsageOnly(provider, range)
        val settled = rangeAfterUsageAttempt(previous = current.range, requested = range, succeeded = succeeded)
        if (settled != range) update(providerId) { it.copy(range = settled) }
    }

    /**
     * 刷新某个 provider。
     *
     * @param force 跳过节流与退避（进页面、保存凭据、点刷新时用）
     */
    suspend fun refresh(providerId: String, force: Boolean = false) {
        val provider = registry.byId(providerId) ?: return
        val lock = locks.getOrPut(providerId) { Mutex() }
        lock.withLock {
            val now = nowSeconds()
            if (!force) {
                val last = lastRefreshAt[providerId] ?: 0L
                if (now - last < intervalSeconds(providerId)) return
                retryNotBefore[providerId]?.let { if (now < it) return }
            }
            val current = state(providerId)
            if (current.loading) return
            val balanceKey = credentials.load(providerId, provider.balanceCredentialKind)
            val usageKey = provider.usageCredentialKind?.let { credentials.load(providerId, it) }
            if (balanceKey == null && usageKey == null) {
                update(providerId) {
                    it.copy(
                        loading = false,
                        errorMessage = null,
                        balanceConfigured = false,
                        usageConfigured = false,
                    )
                }
                return
            }

            lastRefreshAt[providerId] = now
            update(providerId) {
                it.copy(
                    loading = true,
                    errorMessage = null,
                    balanceConfigured = balanceKey != null,
                    usageConfigured = usageKey != null,
                )
            }

            var items = current.items
            var usage = current.usage
            var failure: String? = null
            var failed = false

            if (balanceKey != null) {
                runCatching { provider.fetchItems(balanceKey) }
                    .onSuccess { fresh ->
                        items = fresh
                        failed = false
                        notifier?.handle(providerId, provider.displayName, fresh)
                    }
                    .onFailure { error ->
                        failed = true
                        failure = (error as? QuotaError)?.userMessage ?: error.message ?: "查询失败"
                        logger("额度拉取失败[$providerId/balance]：${error.summaryForLog()}")
                        handleRetry(providerId, error)
                    }
            }
            if (provider.supportsUsage && usageKey != null) {
                val range = state(providerId).range
                runCatching { provider.fetchUsage(usageKey, range) }
                    .onSuccess { usage = it }
                    .onFailure { error ->
                        failed = true
                        if (failure == null) {
                            failure = (error as? QuotaError)?.userMessage ?: error.message ?: "查询失败"
                        }
                        logger("额度拉取失败[$providerId/usage]：${error.summaryForLog()}")
                        handleRetry(providerId, error)
                    }
            }

            val nowAfter = nowSeconds()
            if (!failed && (items.isNotEmpty() || usage != null)) {
                cache.save(
                    com.bsfeng.monitor.domain.quota.QuotaSnapshot(
                        providerId = providerId,
                        items = items,
                        usage = usage,
                        fetchedAtEpochSecond = nowAfter,
                    ),
                )
            }
            update(providerId) {
                it.copy(
                    items = items,
                    usage = usage,
                    loading = false,
                    fetchedAtEpochSecond = if (failed) it.fetchedAtEpochSecond else nowAfter,
                    stale = failed && (items.isNotEmpty() || usage != null),
                    errorMessage = failure,
                )
            }
        }
    }

    /** 刷新所有已配置凭据的 provider（后台定时任务用）。 */
    suspend fun refreshAll() {
        registry.all().forEach { provider ->
            runCatching { refresh(provider.id, force = false) }
        }
    }

    /**
     * 只重拉用量（范围切换用）。
     *
     * @return 是否成功；调用方据此回滚范围（见 [rangeAfterUsageAttempt] 与 [setRange]）
     */
    private suspend fun fetchUsageOnly(provider: QuotaProvider, range: QuotaRange): Boolean {
        val kind = provider.usageCredentialKind ?: return false
        val key = credentials.load(provider.id, kind) ?: return false
        update(provider.id) { it.copy(loading = true) }
        return runCatching { provider.fetchUsage(key, range) }
            .onSuccess { usage ->
                update(provider.id) {
                    it.copy(
                        usage = usage,
                        loading = false,
                        fetchedAtEpochSecond = nowSeconds(),
                        errorMessage = null,
                    )
                }
            }
            .onFailure { error ->
                update(provider.id) {
                    it.copy(
                        loading = false,
                        errorMessage = (error as? QuotaError)?.userMessage ?: error.message,
                    )
                }
                handleRetry(provider.id, error)
            }
            .isSuccess
    }

    private fun handleRetry(providerId: String, error: Throwable) {
        val quotaError = error as? QuotaError ?: return
        if (quotaError.kind == com.bsfeng.monitor.domain.quota.QuotaErrorKind.RATE_LIMITED) {
            val delay = quotaError.retryAfterSeconds ?: retryDefaultSeconds
            retryNotBefore[providerId] = nowSeconds() + delay
        }
    }

    private fun update(providerId: String, transform: (ProviderState) -> ProviderState) {
        _states.update { map ->
            val existing = map[providerId] ?: ProviderState(providerId)
            map + (providerId to transform(existing))
        }
    }

    private companion object {
        /** 服务端没给 Retry-After 时的默认退避。 */
        const val retryDefaultSeconds = 60L
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
