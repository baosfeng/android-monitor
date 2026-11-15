package com.bsfeng.monitor.quota

import android.content.Context
import androidx.core.content.edit
import com.bsfeng.monitor.domain.quota.QuotaInstance
import com.bsfeng.monitor.domain.quota.QuotaInstances
import org.json.JSONArray
import org.json.JSONObject

/**
 * provider 账号实例清单 —— 「同一 provider 可以挂多个账号」的事实源。
 *
 * 只负责「有哪些账号」；凭据、快照、状态、预警各自按 [QuotaInstance.instanceId] 存，
 * 删账号时由 [QuotaRepository.deleteInstance] 统一清干净。
 *
 * 零迁移：第一次读到空清单时按 registry 合成本地默认单实例（id == providerTypeId），
 * 不写盘也正常工作，老用户的凭据 / 快照 / refresh_minutes 键天然命中。
 */
abstract class QuotaInstanceCatalog(protected val providerTypeIds: List<String>) {

    protected abstract fun loadRaw(): List<QuotaInstance>

    protected abstract fun save(all: List<QuotaInstance>)

    /** 当前清单；读到的脏数据（未知类型 / 重复 id）就地规范化，不写盘。 */
    fun instances(): List<QuotaInstance> = synchronized(lock) {
        QuotaInstances.normalize(loadRaw(), providerTypeIds)
    }

    /** 追加一个账号；类型未知或已达上限返回 null。 */
    fun add(providerTypeId: String): QuotaInstance? = synchronized(lock) {
        if (providerTypeId !in providerTypeIds) return null
        val all = QuotaInstances.normalize(loadRaw(), providerTypeIds)
        val sameType = all.filter { it.providerTypeId == providerTypeId }
        if (sameType.size >= QuotaInstances.MAX_INSTANCES_PER_TYPE) return null
        val created = QuotaInstance(
            instanceId = QuotaInstances.nextInstanceId(providerTypeId, all.map { it.instanceId }),
            providerTypeId = providerTypeId,
        )
        save(all + created)
        created
    }

    /** 改名（空串 = 回到默认名）；实例不存在时忽略。 */
    fun rename(instanceId: String, label: String) = synchronized(lock) {
        val all = QuotaInstances.normalize(loadRaw(), providerTypeIds)
        if (all.none { it.instanceId == instanceId }) return
        save(all.map { if (it.instanceId == instanceId) it.copy(label = label.trim()) else it })
    }

    /**
     * 删一个账号。
     *
     * 其余账号的 id **不重排**：重排会让「账号 3」的凭据与快照落到刚删掉的「账号 2」的键上，
     * 用户看到的是别人家的余额。只剩一个账号时拒绝删除（没有「零账号」这个状态）。
     */
    fun remove(instanceId: String): QuotaInstance? = synchronized(lock) {
        val all = QuotaInstances.normalize(loadRaw(), providerTypeIds)
        val target = all.firstOrNull { it.instanceId == instanceId } ?: return null
        if (all.count { it.providerTypeId == target.providerTypeId } <= 1) return null
        save(all.filterNot { it.instanceId == instanceId })
        target
    }

    /** 该实例是否已是同类型里唯一的账号（删除入口据此禁用）。未知实例按「唯一」处理，宁可不可删。 */
    fun isOnlyAccount(instanceId: String): Boolean {
        val all = instances()
        val target = all.firstOrNull { it.instanceId == instanceId } ?: return true
        return all.count { it.providerTypeId == target.providerTypeId } <= 1
    }

    private val lock = Any()
}

/** 落盘实现：一个 key 存整个 JSON 数组（清单很小，不值得拆键）。 */
class QuotaInstanceStore(
    context: Context,
    providerTypeIds: List<String>,
) : QuotaInstanceCatalog(providerTypeIds) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun loadRaw(): List<QuotaInstance> {
        val raw = prefs.getString(KEY_INSTANCES, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                array.optJSONObject(index)?.let { json ->
                    val instanceId = json.optString("instanceId").takeIf { it.isNotEmpty() } ?: return@let null
                    val providerTypeId = json.optString("providerTypeId").takeIf { it.isNotEmpty() } ?: return@let null
                    QuotaInstance(instanceId, providerTypeId, json.optString("label"))
                }
            }
        }.getOrDefault(emptyList())
    }

    override fun save(all: List<QuotaInstance>) {
        val array = JSONArray().apply {
            all.forEach { instance ->
                put(
                    JSONObject()
                        .put("instanceId", instance.instanceId)
                        .put("providerTypeId", instance.providerTypeId)
                        .put("label", instance.label),
                )
            }
        }
        prefs.edit { putString(KEY_INSTANCES, array.toString()) }
    }

    private companion object {
        const val PREFS_NAME = "quota_instances"
        const val KEY_INSTANCES = "instances"
    }
}

/** 内存实现：单测与未装配时的兜底（行为与落盘实现一致）。 */
class InMemoryQuotaInstanceCatalog(providerTypeIds: List<String>) : QuotaInstanceCatalog(providerTypeIds) {

    private var cached: List<QuotaInstance> = emptyList()

    override fun loadRaw(): List<QuotaInstance> = cached

    override fun save(all: List<QuotaInstance>) {
        cached = all
    }
}
