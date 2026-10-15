package com.bsfeng.monitor.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.finance.FinanceManager
import com.bsfeng.monitor.finance.model.Transaction
import com.bsfeng.monitor.service.MonitorAccessibilityService
import com.bsfeng.monitor.util.AccessibilityStatus
import com.bsfeng.monitor.ui.live.DAY_MS
import com.bsfeng.monitor.ui.live.hourlyIntensityOf
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

    fun retry() {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        try {
            _state.value = withContext(Dispatchers.IO) { buildSnapshot() }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("OverviewViewModel", "加载概览数据失败", e)
            _state.value = _state.value.copy(
                isLoading = false,
                loadError = e.message ?: "统计加载失败（本地数据库暂时不可用）",
            )
        }
    }

    private suspend fun buildSnapshot(): OverviewState {
        val now = System.currentTimeMillis()
        val todayStart = startOfDay(now)
        val weekStart = todayStart - 6 * DAY_MS
        val monthStart = todayStart - 29 * DAY_MS

        val today = store.queryByDate(todayStart, now)
        val yesterday = store.queryByDate(todayStart - DAY_MS, todayStart - 1)
        val week = store.queryByDate(weekStart, now)
        val month = store.queryByDate(monthStart, now)

        val todayStr = LocalDate.now().toString()
        val weekStr = LocalDate.now().minusDays(6).toString()
        val monthStr = LocalDate.now().minusDays(29).toString()
        val transactions = finance.getRecentTransactions(TRANSACTION_LIMIT)

        val weekSkips = week.filter { it.action == ACTION_AD_SKIPPED }

        return OverviewState(
            isLoading = false,
            loadError = null,
            todayEventCount = today.size,
            todaySkipCount = today.count { it.action == ACTION_AD_SKIPPED },
            todayActiveMinutes = usageMinutes(today),
            todayExpense = sumBy(transactions, todayStr, todayStr, true),
            todayIncome = sumBy(transactions, todayStr, todayStr, false),
            yesterdayEventCount = yesterday.size,
            yesterdaySkipCount = yesterday.count { it.action == ACTION_AD_SKIPPED },
            yesterdayActiveMinutes = usageMinutes(yesterday),
            accessibilityEnabled = accessibilityEnabled(),
            accessibilityServiceRunning = MonitorAccessibilityService.isRunning,
            hourlyIntensity = hourlyIntensityOf(today),
            typeSlicesToday = slices(today),
            recentEvents = today.sortedByDescending { it.timestamp }.take(RECENT_LIMIT),
            weekEventCount = week.size,
            weekSkipCount = weekSkips.size,
            weekActiveMinutes = usageMinutes(week),
            weekExpense = sumBy(transactions, weekStr, todayStr, true),
            weekIncome = sumBy(transactions, weekStr, todayStr, false),
            dailyEventCounts7 = dailyBuckets(week, weekStart, 7),
            dailySkipCounts7 = dailyBuckets(weekSkips, weekStart, 7),
            dailyActiveMinutes7 = dailyUsageMinutes(week, weekStart, 7),
            topSkippedApps = weekSkips.groupingBy { it.label.ifBlank { UNKNOWN_APP } }.eachCount()
                .entries.sortedByDescending { it.value }.take(TOP_LIMIT)
                .map { TypeSlice(it.key, it.value) },
            monthEventCount = month.size,
            monthSkipCount = month.count { it.action == ACTION_AD_SKIPPED },
            monthActiveMinutes = usageMinutes(month),
            monthExpense = sumBy(transactions, monthStr, todayStr, true),
            monthIncome = sumBy(transactions, monthStr, todayStr, false),
            dailyActiveMinutes30 = dailyUsageMinutes(month, monthStart, 30),
            typeSlices30 = slices(month),
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

    /** [expense] = true 取支出，false 取收入；日期为 ISO "yyyy-MM-dd"，字符串比较即时间序。 */
    private fun sumBy(list: List<Transaction>, from: String, to: String, expense: Boolean): Double =
        list.filter { (if (expense) it.isExpense else it.isIncome) && it.date >= from && it.date <= to }
            .sumOf { it.amount }

    private companion object {
        const val REFRESH_MS = 5_000L
        const val RECENT_LIMIT = 3
        const val SLICE_LIMIT = 7
        const val TOP_LIMIT = 3
        const val TRANSACTION_LIMIT = 500
        const val ACTION_AD_SKIPPED = "AD_SKIPPED"
        const val UNKNOWN_APP = "未知应用"
    }
}
