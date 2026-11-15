package com.bsfeng.monitor.quota

import com.bsfeng.monitor.domain.quota.QuotaError
import com.bsfeng.monitor.domain.quota.QuotaErrorKind
import kotlinx.coroutines.sync.Mutex
import java.util.concurrent.ConcurrentHashMap

/**
 * 刷新的「记账本」：每个实例一把锁 + 节流基准 + 429 退避截止时刻。
 *
 * 为什么单独成类：这三件事共用同一份事实（上一次请求是什么时候、被限流到几时），
 * 散在 repository 里既要重复三张 Map 的增删，又容易在「清凭据 / 删账号」时漏清一张。
 *
 * **键一律是实例键**：同类型第二个账号若复用类型 id，会被第一个账号的锁与退避拖死，
 * 表现是「加了账号但数字永远不动」。
 */
internal class QuotaRefreshBookkeeping(
    private val nowSeconds: () -> Long,
) {

    private val locks = ConcurrentHashMap<String, Mutex>()
    private val lastRefreshAt = ConcurrentHashMap<String, Long>()
    private val retryNotBefore = ConcurrentHashMap<String, Long>()

    /** 该实例的互斥锁（同一账号不并发拉取；不同账号互不阻塞）。 */
    fun lockOf(instanceId: String): Mutex = locks.getOrPut(instanceId) { Mutex() }

    /** 现在能不能发请求：节流窗口已过、且不在退避期内。 */
    fun allows(instanceId: String, intervalSeconds: Long): Boolean {
        val now = nowSeconds()
        val last = lastRefreshAt[instanceId] ?: 0L
        if (now - last < intervalSeconds) return false
        retryNotBefore[instanceId]?.let { if (now < it) return false }
        return true
    }

    /** 记下本次请求时刻（节流基准）。 */
    fun markRefreshed(instanceId: String) {
        lastRefreshAt[instanceId] = nowSeconds()
    }

    /** 429 退避：服务端给了 `Retry-After` 用它，否则退一个默认窗口。 */
    fun backoff(instanceId: String, error: Throwable) {
        val quotaError = error as? QuotaError ?: return
        if (quotaError.kind != QuotaErrorKind.RATE_LIMITED) return
        retryNotBefore[instanceId] = nowSeconds() + (quotaError.retryAfterSeconds ?: RETRY_DEFAULT_SECONDS)
    }

    /** 清掉该实例的记录（断开凭据 / 删账号）：下次重新填 Key 应当立刻能查。 */
    fun forget(instanceId: String) {
        lastRefreshAt.remove(instanceId)
        retryNotBefore.remove(instanceId)
    }

    private companion object {
        /** 服务端没给 Retry-After 时的默认退避。 */
        const val RETRY_DEFAULT_SECONDS = 60L
    }
}
