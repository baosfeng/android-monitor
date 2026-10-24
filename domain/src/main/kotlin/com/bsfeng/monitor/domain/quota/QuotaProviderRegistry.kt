package com.bsfeng.monitor.domain.quota

/**
 * 额度 provider 注册表 —— 启动阶段装配完成后只读；新增 provider 只需注册。
 *
 * 线程安全：用锁保护写入，读取返回快照。
 */
class QuotaProviderRegistry(initial: List<QuotaProvider> = emptyList()) {

    private val lock = Any()
    private val providers = initial.toMutableList()

    /** 注册（同 id 覆盖旧实现，便于替换）。 */
    fun register(provider: QuotaProvider) = synchronized(lock) {
        providers.removeAll { it.id == provider.id }
        providers += provider
    }

    /** 全部 provider（注册顺序）。 */
    fun all(): List<QuotaProvider> = synchronized(lock) { providers.toList() }

    /** 按 id 查。 */
    fun byId(id: String): QuotaProvider? = synchronized(lock) { providers.firstOrNull { it.id == id } }

    /** 是否为空（空态判断）。 */
    val isEmpty: Boolean get() = synchronized(lock) { providers.isEmpty() }
}
