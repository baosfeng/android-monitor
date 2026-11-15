package com.bsfeng.monitor.domain.quota

/**
 * 一个 provider 账号实例 —— 把「类型」与「实例」拆开。
 *
 * - [providerTypeId] 沿用原 `QuotaProvider.id`：只决定用哪个实现、卡片怎么排版、限流口径；
 * - [instanceId] 是凭据 / 状态 / 快照 / 预警 / 聚合榜单的唯一键。
 *
 * 零迁移：某类型的第一个实例 [instanceId] 直接等于 [providerTypeId]（`ollama` / `deepseek` /
 * `opendesign` / `opencode-go`），已有凭据、快照、`refresh_minutes` 键天然命中；新增实例才是
 * `"$providerTypeId#2"` 这种。
 */
data class QuotaInstance(
    val instanceId: String,
    val providerTypeId: String,
    /** 用户改过的名字；空 = 未改名（展示时退回类型名 / 「账号 N」）。 */
    val label: String = "",
) {
    /** 该类型内的序号，从 1 起；解析失败按第一实例处理。 */
    val seq: Int get() = instanceSeq(instanceId, providerTypeId)
}

/** 实例在所属类型里的序号：第一实例（id == 类型 id）为 1，其余取 `#N` 后缀。 */
fun instanceSeq(instanceId: String, providerTypeId: String): Int {
    if (instanceId == providerTypeId) return 1
    val suffix = instanceId.substringAfterLast('#', missingDelimiterValue = "")
    return suffix.toIntOrNull()?.takeIf { it >= 2 } ?: 1
}

/**
 * 实例清单的纯逻辑：合成默认单实例、规范化已落盘清单、分配新实例 id。
 *
 * 单独成 object 而不是塞进 SharedPreferences 实现：这三件事决定「加/删账号后键是否还唯一」，
 * 出错不会崩、只会两个账号互相覆盖，必须能在 JVM 单测里直接断言。
 */
object QuotaInstances {

    /** 第一实例的 id 恒等于类型 id（零迁移约定）。 */
    fun defaultInstance(providerTypeId: String): QuotaInstance =
        QuotaInstance(instanceId = providerTypeId, providerTypeId = providerTypeId)

    /** 空清单时按注册顺序合成默认单实例（不写盘也能正常工作）。 */
    fun defaults(providerTypeIds: List<String>): List<QuotaInstance> =
        providerTypeIds.map(::defaultInstance)

    /**
     * 规范化已落盘清单：去重、丢掉注册表里没有的类型、给缺失类型补默认实例。
     *
     * 补默认实例是为了「升级 App 新增 provider」时不必迁移偏好：清单里没有它 → 直接长出来。
     */
    fun normalize(instances: List<QuotaInstance>, providerTypeIds: List<String>): List<QuotaInstance> {
        val known = providerTypeIds.toSet()
        val kept = instances
            .filter { it.providerTypeId in known }
            .distinctBy { it.instanceId }
        // 按注册顺序分组、组内按序号：设置页 / chip / 榜单直接消费这个顺序，
        // 不必各自再排一次（各排各的就会「设置页是 A,B 而 chip 是 B,A」）
        return providerTypeIds.flatMap { type ->
            val group = kept.filter { it.providerTypeId == type }
            if (group.isEmpty()) listOf(defaultInstance(type)) else group.sortedBy { it.seq }
        }
    }

    /** 该类型下一个可用的实例 id：优先 `#2`，被占用就往后顺延。 */
    fun nextInstanceId(providerTypeId: String, taken: Collection<String>): String {
        val used = taken.toSet()
        var seq = 2
        // 上限只是防呆：真到 99 个账号时也应停下而不是死循环
        while (seq < MAX_INSTANCES_PER_TYPE && "$providerTypeId#$seq" in used) seq++
        return "$providerTypeId#$seq"
    }

    /** 单类型账号数上限（防呆，与账号列表 UI 的可读性一致）。 */
    const val MAX_INSTANCES_PER_TYPE: Int = 100

    /**
     * 设置页组内一行的标题：改过名用名字；没改名时，单账号用类型名、多账号用「账号 N」。
     *
     * 多账号一律用「账号 N」：同一组里两行都叫「DeepSeek」时，用户点开的是哪个账号全凭运气。
     */
    fun accountName(instance: QuotaInstance, typeDisplayName: String, multiAccount: Boolean): String =
        instance.label.trim().ifEmpty {
            if (multiAccount) "账号 ${instance.seq}" else typeDisplayName
        }

    /**
     * 跨类型的展示名（聚合榜单 / 单家 chip / 通知标题）：同类型只有一个账号时就是类型名，
     * 多账号时带上账号名（`DeepSeek · 账号 2`），保证「一眼分清谁是谁」。
     */
    fun displayName(instance: QuotaInstance, typeDisplayName: String, multiAccount: Boolean): String =
        if (!multiAccount) typeDisplayName else "$typeDisplayName · ${accountName(instance, typeDisplayName, true)}"
}
