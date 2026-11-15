package com.bsfeng.monitor.domain.quota

/**
 * 额度聚合与排序 —— 把「多个账号实例 × 多条额度项」压成「一实例一行」的榜单。
 *
 * 一行 = 一个 [QuotaInstance]（同一 provider 类型可挂多个账号，各自独立参与排序）。
 *
 * 为什么只收能算出百分比的行：百分比是唯一可横向比较的量纲。纯余额型（金额且没有总额，
 * 如 DeepSeek 余额）没有分母，为了排进榜单只能臆造一个总额，排出来的位次是假的，
 * 宁可不进榜、由调用方单独展示。
 *
 * 为什么一家一行且取「剩余最少」的那条：榜单要回答的是「哪家先撑不住」，
 * 一家同时有 session / weekly 两个窗口时，先耗尽的是数值更小的那个窗口。
 */
object QuotaAggregator {

    /**
     * 排序口径：剩余比例升序（越低越紧急）。
     *
     * 比例相同按实例键字典序兜底：不兜底时相同比例的行序取决于输入顺序，
     * 每次刷新都可能让两行互换，用户会以为数据在跳。同类型两个账号剩余比例相同时，
     * 兜底键必须落在实例键上（`#2` 与类型 id 天然不同），否则两行会来回抖。
     */
    private val ROW_ORDER: Comparator<QuotaAggregateRow> =
        compareBy({ it.primaryFraction }, { it.instanceId })

    /**
     * 聚合入口：过滤 → 选主指标 → 排序 → 编号位次。
     *
     * @return 位次从 1 开始、1 为最紧急；没有可聚合的 provider 时返回空列表（UI 走空态）
     */
    fun aggregate(inputs: List<ProviderAggregateInput>): List<QuotaAggregateRow> =
        inputs
            // 没配额度凭据的 provider 没有可信数据；即便残留旧 items 也不该占榜位
            .filter { it.balanceConfigured }
            .mapNotNull { it.toRowOrNull() }
            .sortedWith(ROW_ORDER)
            .mapIndexed { index, row -> row.copy(rank = index + 1) }

    /**
     * 单家 provider 出行为 null 的两种情况：没有额度项，或所有项都算不出百分比。
     * 后者正是「纯余额型不进聚合」的落点。
     */
    private fun ProviderAggregateInput.toRowOrNull(): QuotaAggregateRow? {
        if (items.isEmpty()) return null
        val measured = items.mapNotNull { item ->
            item.remainingFraction?.let { Measured(item, it) }
        }
        if (measured.isEmpty()) return null

        // 同比例时优先 provider 自报的主项，再不做决定就保持 items 原序：
        // 主指标在刷新之间来回换比「取到第二条」更让人怀疑数据不可靠
        val primaryIndex = measured.indices.minWithOrNull(
            compareBy<Int> { measured[it].fraction }
                .thenBy { !measured[it].item.primary },
        ) ?: return null
        val primary = measured[primaryIndex]

        // 其余项按剩余升序给摘要：调用方若要展开，看到的顺序与榜单语义一致
        val others = measured
            .filterIndexed { index, _ -> index != primaryIndex }
            .sortedBy { it.fraction }
            .map { it.item }

        return QuotaAggregateRow(
            instanceId = instanceId,
            providerTypeId = providerTypeId,
            displayName = displayName,
            primaryItem = primary.item,
            primaryFraction = primary.fraction,
            otherItems = others,
            rank = 0, // 位次由排序后的位置决定，这里只是占位
            errorMessage = errorMessage,
            stale = stale,
            fetchedAtEpochSecond = fetchedAtEpochSecond,
        )
    }

    private data class Measured(val item: QuotaItem, val fraction: Float)
}

/**
 * 聚合输入 —— domain 层自带的最小快照，刻意不引用 app 层的 `QuotaRepository.ProviderState`，
 * 否则 domain 反向依赖 UI 层、纯函数也无法在 JVM 单测里直接构造。
 *
 * @param balanceConfigured 是否已配置额度凭据；未配置的 provider 不进榜单
 * @param errorMessage 最近一次拉取失败原因；有旧数据时会与 [stale] 同时出现
 * @param fetchedAtEpochSecond 本次数据拉取时间（epoch 秒），供调用方标注「多久前」
 */
data class ProviderAggregateInput(
    /** 实例键（凭据 / 状态 / 快照的唯一键）；同一 provider 类型的多个账号互不相同。 */
    val instanceId: String,
    /** provider 类型 id：只用于卡片排版与限流口径分流，不参与排序。 */
    val providerTypeId: String = instanceId,
    /** 展示名（多账号时已带账号名，如 `DeepSeek · 账号 2`）。 */
    val displayName: String,
    val items: List<QuotaItem> = emptyList(),
    val balanceConfigured: Boolean = true,
    val errorMessage: String? = null,
    val stale: Boolean = false,
    val fetchedAtEpochSecond: Long = 0L,
)

/**
 * 榜单一行 = 一个 provider 账号实例。
 *
 * @param instanceId 实例键：点行下钻、灰行判断、稳定排序都按它
 * @param primaryItem 主指标：该家「能算出百分比」的项里剩余最少的一条
 * @param primaryFraction 主指标剩余比例 0..1（[primaryItem] 的 `remainingFraction`，非空）
 * @param otherItems 同家其余可量化项，按剩余升序；算不出百分比的项（纯余额）不在此列
 * @param rank 位次，1 = 最紧急
 */
data class QuotaAggregateRow(
    val instanceId: String,
    val providerTypeId: String = instanceId,
    val displayName: String,
    val primaryItem: QuotaItem,
    val primaryFraction: Float,
    val otherItems: List<QuotaItem> = emptyList(),
    val rank: Int = 0,
    val errorMessage: String? = null,
    val stale: Boolean = false,
    val fetchedAtEpochSecond: Long = 0L,
)