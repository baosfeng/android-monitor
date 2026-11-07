package com.bsfeng.monitor.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.domain.trip.DayTimeline
import com.bsfeng.monitor.domain.trip.TripAggregator
import com.bsfeng.monitor.finance.FinanceManager
import com.bsfeng.monitor.service.MonitorAccessibilityService
import com.bsfeng.monitor.util.AccessibilityStatus
import com.bsfeng.monitor.ui.live.DAY_MS
import com.bsfeng.monitor.ui.live.hourlyIntensityOf
import com.bsfeng.monitor.ui.live.rollingHourIntensityOf
import com.bsfeng.monitor.ui.live.recordTypeLabel
import com.bsfeng.monitor.ui.live.startOfDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * 错态描述（锚点 s01-error 逐字）：
 * 「本地数据库暂时不可用（SQLITE_BUSY）。稍后重试即可，已采集的事件不会丢失。」
 *
 * 异常原文（含 SQLite 错误码 / 表名）只进日志，上屏一律这句。
 */
internal const val LOAD_ERROR_DESC = "本地数据库暂时不可用（SQLITE_BUSY）。稍后重试即可，已采集的事件不会丢失。"

/** 一种事件类型 + 条数（环图 / 图例 / 排行用）。 */
data class TypeSlice(val label: String, val count: Int)

data class OverviewState(
    val isLoading: Boolean = true,
    val loadError: String? = null,
    // ── 今日 ──
    val todayEventCount: Int = 0,
    val todaySkipCount: Int = 0,
    val todayActiveMinutes: Long = 0,
    val todayExpense: Double = 0.0,
    val todayIncome: Double = 0.0,
    val yesterdayEventCount: Int = 0,
    val yesterdaySkipCount: Int = 0,
    val yesterdayActiveMinutes: Long = 0,
    /** 无障碍服务状态：与 s02 / s05 共用同一判定（[AccessibilityStatus]） */
    val accessibilityEnabled: Boolean = false,
    val accessibilityServiceRunning: Boolean = false,
    val hourlyIntensity: List<Float> = List(24) { 0f },
    /** 滚动 24 小时逐时强度：下标 0 = 最早那一小时、23 = 当前小时（「最近 24 小时活跃」卡用它）。 */
    val last24hIntensity: List<Float> = List(24) { 0f },
    /** 滚动 24 小时的活跃分钟数（卡头「合计 N 分钟」）。 */
    val last24hActiveMinutes: Long = 0,
    val typeSlicesToday: List<TypeSlice> = emptyList(),
    val recentEvents: List<Record> = emptyList(),
    // ── 近 7 天 ──
    val weekEventCount: Int = 0,
    val weekSkipCount: Int = 0,
    val weekActiveMinutes: Long = 0,
    val weekExpense: Double = 0.0,
    val weekIncome: Double = 0.0,
    val dailyEventCounts7: List<Float> = List(7) { 0f },
    val dailySkipCounts7: List<Float> = List(7) { 0f },
    val dailyActiveMinutes7: List<Float> = List(7) { 0f },
    val topSkippedApps: List<TypeSlice> = emptyList(),
    // ── 近 30 天 ──
    val monthEventCount: Int = 0,
    val monthSkipCount: Int = 0,
    val monthActiveMinutes: Long = 0,
    val monthExpense: Double = 0.0,
    val monthIncome: Double = 0.0,
    val dailyActiveMinutes30: List<Float> = List(30) { 0f },
    val typeSlices30: List<TypeSlice> = emptyList(),
    // ── 上周 / 上月：紧邻当前窗口的同长度区间（近 7 天 → 再往前 7 天；近 30 天 → 再往前 30 天） ──
    /** 上周事件数（副行「较上周 +6.2%」的对比基数）。 */
    val prevWeekEventCount: Int = 0,
    val prevWeekSkipCount: Int = 0,
    val prevMonthEventCount: Int = 0,
    val prevMonthSkipCount: Int = 0,
    /** 边界态内容：今日标题最长的那条事件（真实记录，不编造）；今日无记录时为 null。 */
    val longestEvent: Record? = null,
    /** 今日行程时间轴（概览「今日行程」卡用）；零点整点等窗口为 0 的情况下为 null。 */
    val trip: DayTimeline? = null,
    val tripDayStart: Long = 0L,
    val lastUpdatedAt: Long = 0L,
)

/**
 * s01 今日概览的数据源。
 *
 * 事件类指标来自 RecordStore（今日 / 近 7 天 / 近 30 天各查一次有界区间）；
 * 支出类指标来自 FinanceManager（同区间的交易聚合）。
 * 全部 IO 在 Dispatchers.IO 执行；读取失败进 error 态，不抛到 UI。
 */
class OverviewViewModel(application: Application) : AndroidViewModel(application) {

    private val store = MonitorApp.instance.container.recordStore
    private val finance = FinanceManager.getInstance(application)

    private val _state = MutableStateFlow(OverviewState())
    val state: StateFlow<OverviewState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            // 轮询只在 UI 订阅期间运行：本屏退出组合（切到记账 Tab）或
            // 退到后台（collectAsStateWithLifecycle 取消收集）时订阅数归零，
            // 循环随之挂起，不再出现「切走 Tab 后概览仍在每 5s 查 30 天全量」。
            _state.subscriptionCount.collectLatest { subscribers ->
                if (subscribers <= 0) return@collectLatest
                while (true) {
                    load()
                    delay(REFRESH_MS)
                }
            }
        }
    }

    /**
     * 错态「重试」：**先回到骨架态**再拉数据（锚点 s01-error-retry「整块换成加载骨架」）。
     *
     * 只调 [load] 是不够的：错态块会原地留着、按钮还能连点。置 isLoading=true 的那一刻
     * 错态块连同按钮一起卸载，物理上无法连点；再失败时 [load] 的 catch 落回错态。
     */
    fun retry() {
        if (_state.value.isLoading) return
        viewModelScope.launch {
            _state.value = _state.value.copy(isLoading = true, loadError = null)
            load()
        }
    }

    private suspend fun load() {
        try {
            _state.value = withContext(Dispatchers.IO) { buildSnapshot() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            // 异常原文只进日志：e.message 是内部实现细节（SQLite 错误码 / 表名 / 路径），
            // 直接上屏既泄漏实现细节、又和口径写死的错态文案对不上。
            Log.w("OverviewViewModel", "加载概览数据失败", e)
            _state.value = _state.value.copy(isLoading = false, loadError = LOAD_ERROR_DESC)
        }
    }

    private suspend fun buildSnapshot(): OverviewState {
        val now = System.currentTimeMillis()
        val todayStart = startOfDay(now)
        val weekStart = todayStart - 6 * DAY_MS
        val monthStart = todayStart - 29 * DAY_MS

        val today = store.queryByDate(todayStart, now)
        // 滚动窗口跨自然日：凌晨打开时必须是「昨天此刻 → 现在」，不能只查今天
        val last24h = store.queryByDate(now - DAY_MS, now)
        val yesterday = store.queryByDate(todayStart - DAY_MS, todayStart - 1)
        val week = store.queryByDate(weekStart, now)
        val month = store.queryByDate(monthStart, now)

        val todayStr = LocalDate.now().toString()
        val weekStr = LocalDate.now().minusDays(6).toString()
        val monthStr = LocalDate.now().minusDays(29).toString()
        // 收支一律走 SQL SUM：原实现取「最近 500 笔」再按日期过滤，30 天内超过 500 笔时
        // 被截掉的恰好是最旧那批，月度金额静默偏低且界面无异常提示。
        val todayExpense = finance.sumByDateRange(todayStr, todayStr, expense = true)
        val todayIncome = finance.sumByDateRange(todayStr, todayStr, expense = false)
        val weekExpense = finance.sumByDateRange(weekStr, todayStr, expense = true)
        val weekIncome = finance.sumByDateRange(weekStr, todayStr, expense = false)
        val monthExpense = finance.sumByDateRange(monthStr, todayStr, expense = true)
        val monthIncome = finance.sumByDateRange(monthStr, todayStr, expense = false)

        val weekSkips = week.filter { it.action == ACTION_AD_SKIPPED }

        // 上周 / 上月：紧邻当前窗口的同长度区间（近 7 天 → 再往前 7 天；近 30 天 → 再往前 30 天）。
        // 只走 COUNT，不把上万行读回内存；AD_SKIPPED 落库口径与桌面小组件一致（SCREEN + AD_SKIPPED）。
        val weekPrevStart = weekStart - 7 * DAY_MS
        val monthPrevStart = monthStart - 30 * DAY_MS
        val prevWeekEvents = store.countByDate(weekPrevStart, weekStart - 1)
        val prevWeekSkips = store.countByTypeAndAction(
            RecordType.SCREEN, ACTION_AD_SKIPPED, weekPrevStart, weekStart - 1,
        )
        val prevMonthEvents = store.countByDate(monthPrevStart, monthStart - 1)
        val prevMonthSkips = store.countByTypeAndAction(
            RecordType.SCREEN, ACTION_AD_SKIPPED, monthPrevStart, monthStart - 1,
        )

        // 今日行程：复用本轮已查到的今日记录现算（不额外查库、不建表）；
        // 窗口为 0（零点整点那一瞬）时聚合器会拒绝非法区间，这里按「暂无行程」处理。
        val trip = if (now > todayStart) {
            runCatching { TripAggregator(todayStart, now).aggregate(today) }.getOrNull()
        } else {
            null
        }

        return OverviewState(
            isLoading = false,
            loadError = null,
            todayEventCount = today.size,
            todaySkipCount = today.count { it.action == ACTION_AD_SKIPPED },
            todayActiveMinutes = usageMinutes(today),
            todayExpense = todayExpense,
            todayIncome = todayIncome,
            yesterdayEventCount = yesterday.size,
            yesterdaySkipCount = yesterday.count { it.action == ACTION_AD_SKIPPED },
            yesterdayActiveMinutes = usageMinutes(yesterday),
            accessibilityEnabled = accessibilityEnabled(),
            accessibilityServiceRunning = MonitorAccessibilityService.isRunning,
            hourlyIntensity = hourlyIntensityOf(today),
            last24hIntensity = rollingHourIntensityOf(last24h, now),
            last24hActiveMinutes = usageMinutes(last24h),
            typeSlicesToday = slices(today),
            recentEvents = today.sortedByDescending { it.timestamp }.take(RECENT_LIMIT),
            weekEventCount = week.size,
            weekSkipCount = weekSkips.size,
            weekActiveMinutes = usageMinutes(week),
            weekExpense = weekExpense,
            weekIncome = weekIncome,
            dailyEventCounts7 = dailyBuckets(week, weekStart, 7),
            dailySkipCounts7 = dailyBuckets(weekSkips, weekStart, 7),
            dailyActiveMinutes7 = dailyUsageMinutes(week, weekStart, 7),
            topSkippedApps = weekSkips.groupingBy { it.label.ifBlank { UNKNOWN_APP } }.eachCount()
                .entries.sortedByDescending { it.value }.take(TOP_LIMIT)
                .map { TypeSlice(it.key, it.value) },
            monthEventCount = month.size,
            monthSkipCount = month.count { it.action == ACTION_AD_SKIPPED },
            monthActiveMinutes = usageMinutes(month),
            monthExpense = monthExpense,
            monthIncome = monthIncome,
            dailyActiveMinutes30 = dailyUsageMinutes(month, monthStart, 30),
            typeSlices30 = slices(month),
            prevWeekEventCount = prevWeekEvents,
            prevWeekSkipCount = prevWeekSkips,
            prevMonthEventCount = prevMonthEvents,
            prevMonthSkipCount = prevMonthSkips,
            // 边界态要展示「最长的那条真实记录」：取今日标题最长的一条，没有记录就不编一条。
            longestEvent = today.maxByOrNull { it.label.length },
            trip = trip,
            tripDayStart = todayStart,
            lastUpdatedAt = now,
        )
    }

    /** 无障碍状态走带 TTL 的轮询口径（30s），避免每轮跨进程读设置。 */
    private fun accessibilityEnabled(): Boolean =
        AccessibilityStatus.isEnabledCached(
            getApplication(),
            MonitorAccessibilityService.isRunning,
        )

    /** 活跃时长口径：APP_USAGE 记录的 duration 累加（毫秒 → 分钟）。 */
    private fun usageMinutes(records: List<Record>): Long =
        records.filter { it.type == RecordType.APP_USAGE }.sumOf { it.duration ?: 0L } / 60_000L

    private fun dailyBuckets(records: List<Record>, rangeStart: Long, days: Int): List<Float> {
        val buckets = FloatArray(days)
        records.forEach { record ->
            val idx = ((record.timestamp - rangeStart) / DAY_MS).toInt()
            if (idx in 0 until days) buckets[idx] += 1f
        }
        return buckets.toList()
    }

    private fun dailyUsageMinutes(records: List<Record>, rangeStart: Long, days: Int): List<Float> {
        val buckets = FloatArray(days)
        records.filter { it.type == RecordType.APP_USAGE }.forEach { record ->
            val idx = ((record.timestamp - rangeStart) / DAY_MS).toInt()
            if (idx in 0 until days) buckets[idx] += ((record.duration ?: 0L) / 60_000L).toFloat()
        }
        return buckets.toList()
    }

    private fun slices(records: List<Record>): List<TypeSlice> =
        records.groupingBy { it.type }.eachCount()
            .entries.sortedByDescending { it.value }.take(SLICE_LIMIT)
            .map { TypeSlice(recordTypeLabel(it.key), it.value) }

    private companion object {
        const val REFRESH_MS = 5_000L
        const val RECENT_LIMIT = 3
        const val SLICE_LIMIT = 7
        const val TOP_LIMIT = 3
        const val ACTION_AD_SKIPPED = "AD_SKIPPED"
        const val UNKNOWN_APP = "未知应用"
    }
}
