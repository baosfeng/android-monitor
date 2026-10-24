package com.bsfeng.monitor.ui.quota

import com.bsfeng.monitor.domain.quota.ProviderAggregateInput
import com.bsfeng.monitor.domain.quota.QuotaAggregateRow
import com.bsfeng.monitor.domain.quota.QuotaAggregator
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaProvider
import com.bsfeng.monitor.quota.QuotaRepository
import java.math.BigDecimal
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * 聚合视图的全部纯计算：`ProviderState` → 聚合输入 → 榜单 / 灰行 / 行内文案。
 *
 * 为什么单独成文件且不碰 Compose：这些映射错了不会崩，只会「某家凭空掉出榜单」
 * 或「副文案少一个窗口」，只有真机肉眼才能发现。抽成纯函数后由
 * `QuotaAggregateRowsTest` 钉住，改 domain 聚合器或加 provider 时先红一次。
 */

/** 不参与聚合的一家（末尾灰行）。 */
data class QuotaExcludedProvider(
    val providerId: String,
    val displayName: String,
    /** 灰行副文案，说明「为什么没进榜」。 */
    val note: String,
)

/**
 * 聚合视图的状态，与单家视图 `QuotaBody` 的四态对应，外加一格「配了但没有可排序项」。
 *
 * 「没有可排序的行」必须拆开，因为三种情况下用户要做的事完全不同：
 * - 正在查（[Loading]）：等结果，别动配置；
 * - 一家都没配（[NotConfigured]）：去配置；
 * - 配了但全失败（[AllFailed]）：看原因、重试。
 *
 * 早期只判断 `!hasRanked` 就一律画「去配置 provider」的空态，于是首次进页面（请求在跑）
 * 和接口全失败时都在喊「去配置」——用户会去重填一遍本来正确的 Key。
 *
 * [NoRankable] 单独占一格：凭据是对的、请求也成功了，只是返回的额度算不出百分比
 * （纯余额型），喊「去配置」同样是错的，这里只能引导去看 provider 设置。
 */
enum class QuotaAggregateStatus {
    /** 首次数据还没回来（请求在跑，或还没下发）：骨架。 */
    Loading,

    /** 一家都没配凭据：空态 + 去配置。 */
    NotConfigured,

    /** 已配置但没有一条能算出百分比：解释口径，不给「去配置」。 */
    NoRankable,

    /** 配了但全部查询失败：错误态 + 原因 + 重试。 */
    AllFailed,

    /** 有可排序的行（部分失败家带 stale 照常上榜，属正常内容）。 */
    Content,
}

/**
 * 聚合视图的一屏数据。
 *
 * @param latestFetchedAtEpochSecond 各家里最近一次成功拉取的时刻，用于「更新于 HH:mm」；
 *   取 max 而不是当前选中家，否则切家时这行会往回跳
 * @param configuredCount 已配置凭据（余额或用量任一）的家数：区分「一个都没配」与「配了但没数据」
 * @param awaitingDataCount 已配置但一条数据都还没取回的家数：>0 说明该等，不该喊去配置
 * @param failureMessage 全部已配置家都失败时的原因；部分失败为 null（那走榜单 + stale）
 */
data class QuotaAggregateSnapshot(
    val rows: List<QuotaAggregateRow> = emptyList(),
    val excluded: List<QuotaExcludedProvider> = emptyList(),
    val latestFetchedAtEpochSecond: Long = 0L,
    val configuredCount: Int = 0,
    val awaitingDataCount: Int = 0,
    val failureMessage: String? = null,
) {
    /** 一家都没进榜 = 没有可排序内容（灰行不算榜单内容）。 */
    val hasRanked: Boolean get() = rows.isNotEmpty()

    /** 结论条取第 1 名。 */
    val leader: QuotaAggregateRow? get() = rows.firstOrNull()

    /** 界面只消费这个，判定逻辑本身在 [aggregateStatusOf] 里由单测钉住。 */
    val status: QuotaAggregateStatus get() = aggregateStatusOf(this)
}

/**
 * 状态判定（纯函数）。
 *
 * 顺序即优先级：**先「有数据」再「在等数据」最后才「失败 / 没配 / 无可排序项」**。
 * 先看榜单是因为部分失败时行还在，这时换骨架或错误页会把已经查到的数据抹掉；
 * 等数据排在失败前面，是因为「一家失败、另一家还在查」时结论未定，提前报错会误导重试。
 */
fun aggregateStatusOf(snapshot: QuotaAggregateSnapshot): QuotaAggregateStatus = when {
    snapshot.hasRanked -> QuotaAggregateStatus.Content
    snapshot.awaitingDataCount > 0 -> QuotaAggregateStatus.Loading
    snapshot.failureMessage != null -> QuotaAggregateStatus.AllFailed
    // 走到这里：没报错、也没在等，却排不出行 → 凭据配了，只是额度算不出百分比
    snapshot.configuredCount > 0 -> QuotaAggregateStatus.NoRankable
    else -> QuotaAggregateStatus.NotConfigured
}

/** 有凭据（余额或用量任一）：聚合只认这种家，没凭据的连请求都不会发。 */
private fun QuotaRepository.ProviderState.isConfigured(): Boolean =
    balanceConfigured || usageConfigured

/**
 * 这家是不是「数据还没影」。
 *
 * `loading` 之外还要认「已配置、从未取回、也没报错、且当前不在加载」：
 * 进页面到请求真正下发之间有一个空窗（`LaunchedEffect` 在首次组合之后才跑），
 * 只认 `loading` 的话这一帧会闪一下空态——正是要修的那个误报。
 */
private fun QuotaRepository.ProviderState.isAwaitingData(): Boolean =
    items.isEmpty() && usage == null && errorMessage == null &&
        (loading || fetchedAtEpochSecond == 0L)

/**
 * 全部失败时的提示文案；只要有一家没报错就返回 null（那是部分失败，走榜单 + 旧数据标签）。
 *
 * 各家原因相同时只显示一条：真实的失败原因通常同源（断网、令牌过期），
 * 拼成「A；B；C」反而让用户以为是三件不同的事。
 */
fun aggregateFailureMessage(messages: List<String?>): String? {
    if (messages.isEmpty()) return null
    val present = messages.map { it?.trim()?.takeIf(String::isNotEmpty) }
    if (present.any { it == null }) return null
    return present.filterNotNull().distinct().joinToString("；")
}

/** 单家映射：纯函数，测试直接构造 [QuotaRepository.ProviderState] 断言。 */
fun aggregateInputOf(
    provider: QuotaProvider,
    state: QuotaRepository.ProviderState?,
): ProviderAggregateInput = ProviderAggregateInput(
    providerId = provider.id,
    displayName = provider.displayName,
    items = state?.items.orEmpty(),
    // 没有 state 视为未配置：榜单只收有凭据的家，避免冷启动时把空行排进去
    balanceConfigured = state?.balanceConfigured ?: false,
    errorMessage = state?.errorMessage,
    stale = state?.stale ?: false,
    fetchedAtEpochSecond = state?.fetchedAtEpochSecond ?: 0L,
)

/**
 * 灰行判定：有额度凭据、有额度项，但**一条都算不出百分比**。
 *
 * 不把「未配置凭据」也做成灰行：单家视图的 chip 已经表达未配置，
 * 榜单里再排一排「未配置」会把真正需要看的紧急度挤下去。
 */
fun excludedOf(
    provider: QuotaProvider,
    state: QuotaRepository.ProviderState?,
): QuotaExcludedProvider? {
    if (state == null || !state.balanceConfigured) return null
    if (state.items.isEmpty()) return null
    if (state.items.any { it.remainingFraction != null }) return null
    return QuotaExcludedProvider(
        providerId = provider.id,
        displayName = provider.displayName,
        note = excludedNote(state.items),
    )
}

/** 灰行副文案：余额型与「有其他结构但无总量」分开说，用户才知道该不该去补总额。 */
fun excludedNote(items: List<QuotaItem>): String =
    if (items.all { it.isPlainBalance }) "余额型 · 无总量，算不出百分比" else "无总量 · 算不出百分比"

/**
 * 聚合入口：进榜的行交给 domain 聚合器，灰行在 app 层另算（聚合器只返回进榜行）。
 *
 * 四态所需的三个计数也在这里算出：界面上只该有一处知道「哪些家算已配置」，
 * 散到 Composable 里就会出现「空态和错误态的判据不一致」。
 */
fun buildAggregateSnapshot(
    providers: List<QuotaProvider>,
    states: Map<String, QuotaRepository.ProviderState>,
): QuotaAggregateSnapshot {
    val configured = providers.mapNotNull { states[it.id] }.filter { it.isConfigured() }
    return QuotaAggregateSnapshot(
        rows = QuotaAggregator.aggregate(providers.map { aggregateInputOf(it, states[it.id]) }),
        excluded = providers.mapNotNull { excludedOf(it, states[it.id]) },
        latestFetchedAtEpochSecond = providers.maxOfOrNull {
            states[it.id]?.fetchedAtEpochSecond ?: 0L
        } ?: 0L,
        configuredCount = configured.size,
        awaitingDataCount = configured.count { it.isAwaitingData() },
        // 只有已配置的家都报错才算「全部失败」：没配置的家本来就不发请求
        failureMessage = aggregateFailureMessage(configured.map { it.errorMessage }),
    )
}

/** 统计行：`3 家参与排序 · 更新于 9:41`。 */
fun aggregateMetaText(
    snapshot: QuotaAggregateSnapshot,
    zone: ZoneId = ZoneId.systemDefault(),
): String {
    val updated = if (snapshot.latestFetchedAtEpochSecond > 0L) {
        "更新于 " + QuotaFormat.clockOf(snapshot.latestFetchedAtEpochSecond, zone)
    } else {
        "尚未更新"
    }
    return "${snapshot.rows.size} 家参与排序 · $updated"
}

/** 窗口短名：额度项的 title 都带「额度」后缀，行内副文案里省掉（`5 小时 12%`）。 */
fun windowShortTitle(title: String): String =
    title.removeSuffix("额度").trim().ifEmpty { title }

/**
 * 0..1 比例 → `12%` / `46.5%`（一位小数、去尾零）。
 *
 * 先乘 1000 取整再除 10：`0.46f * 100` 是 45.999996，直接 setScale 会得到 `46%` 与
 * `45.9%` 随机取决于舍入模式；走整数分位才是稳定的。
 */
fun percentOfFraction(fraction: Float): String =
    QuotaFormat.percent(BigDecimal.valueOf((fraction * 1000f).roundToInt() / 10.0))

/**
 * 一条额度项的百分比文本。
 *
 * 百分比项直接取 `remaining`（12.00 → `12%`，不做浮点往返）；
 * 金额 / 次数项只能从 `remainingFraction` 折算。
 */
fun percentTextOf(item: QuotaItem): String? {
    val fraction = item.remainingFraction ?: return null
    if (item.kind == QuotaKind.PERCENT) return QuotaFormat.percent(item.remaining)
    return percentOfFraction(fraction)
}

/**
 * 行副文案：多窗口并排（`5 小时 12% · 每周 46% · 每月 71%`）。
 *
 * 主指标放在最前：它决定了这家的位次，读的人先看到「为什么排这里」。
 */
fun aggregateSummary(row: QuotaAggregateRow): String =
    (listOf(row.primaryItem) + row.otherItems)
        .mapNotNull { item -> percentTextOf(item)?.let { "${windowShortTitle(item.title)} $it" } }
        .joinToString(" · ")

/**
 * 行副文案 + 陈旧数据后缀。
 *
 * `stale` 只在「失败但有旧数据」时置位：这时卡片照常排序，但必须标出数据停在几点，
 * 否则用户会把昨天的 40% 当成刚查到的。
 */
fun aggregateSubText(row: QuotaAggregateRow, zone: ZoneId = ZoneId.systemDefault()): String {
    val base = aggregateSummary(row)
    if (!row.stale || row.fetchedAtEpochSecond <= 0L) return base
    val stopped = "停在 " + QuotaFormat.clockOf(row.fetchedAtEpochSecond, zone)
    return if (base.isEmpty()) stopped else "$base · $stopped"
}

/** 结论条文案：`opencode-go · 5 小时`（右侧百分比单独渲染）。 */
fun leaderTitle(row: QuotaAggregateRow): String =
    row.displayName + " · " + windowShortTitle(row.primaryItem.title)
