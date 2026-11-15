package com.bsfeng.monitor.quota

import com.bsfeng.monitor.domain.quota.QuotaInstance
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.withLock

/**
 * 账号实例的增删改 —— [QuotaRepository] 的一条支线，独立成类只为守 400 行硬线。
 *
 * 删除与断开都要「凭据 / 快照 / 状态 / 节流记录」四件一起清：漏一件的表现分别是
 * 键还占着、卡片继续显示旧数字、榜单里留一行空壳、重新填 Key 后要等一个上家的冷却窗口。
 */
internal class QuotaInstanceAdmin(
    private val catalog: QuotaInstanceCatalog,
    private val credentials: QuotaCredentialStore,
    private val cache: QuotaSnapshotCache,
    private val bookkeeping: QuotaRefreshBookkeeping,
    private val instances: MutableStateFlow<List<QuotaInstance>>,
    private val states: MutableStateFlow<Map<String, QuotaRepository.ProviderState>>,
    private val initialState: (QuotaInstance) -> QuotaRepository.ProviderState,
) {

    /** 追加一个账号；类型未注册或已达上限返回 null。 */
    fun add(providerTypeId: String): QuotaInstance? {
        val created = catalog.add(providerTypeId) ?: return null
        instances.value = catalog.instances()
        states.update { it + (created.instanceId to initialState(created)) }
        return created
    }

    /** 改名（空串 = 回到默认名）。 */
    fun rename(instanceId: String, label: String) {
        catalog.rename(instanceId, label)
        instances.value = catalog.instances()
    }

    /** 清掉该实例的凭据、快照、状态与节流记录（断开与删除共用）。 */
    fun forget(instanceId: String) {
        credentials.clearAll(instanceId)
        cache.clear(instanceId)
        bookkeeping.forget(instanceId)
    }

    /** 删账号：只剩一个账号时拒绝删除（没有「某类型零账号」这个状态）。 */
    suspend fun remove(instanceId: String): Boolean = bookkeeping.lockOf(instanceId).withLock {
        val removed = catalog.remove(instanceId) ?: return@withLock false
        forget(removed.instanceId)
        states.update { it - removed.instanceId }
        instances.value = catalog.instances()
        true
    }
}
