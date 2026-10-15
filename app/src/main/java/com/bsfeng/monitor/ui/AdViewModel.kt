package com.bsfeng.monitor.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.ui.live.DAY_MS
import com.bsfeng.monitor.ui.live.startOfDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** 跳过方式（s03 筛选 chip）。 */
enum class SkipMethod(val label: String) {
    ALL("全部"),
    RULE("规则"),
    COORD("坐标兜底"),
    A11Y("无障碍"),
}

/**
 * 由落库的选择器表达式反推跳过方式。
 *
 * 采集端 [com.bsfeng.monitor.skip.SkipEventRecorder] 只写入 selector 原文，
 * 没有独立的「方式」字段，这里按选择器文案归类；无法归类的一律记为规则命中。
 */
fun classifySkipMethod(selector: String): SkipMethod {
    val s = selector.lowercase()
    return when {
        s.contains("坐标") || s.contains("coord") -> SkipMethod.COORD
        s.contains("无障碍") || s.contains("a11y") || s.contains("事件流") -> SkipMethod.A11Y
        else -> SkipMethod.RULE
    }
}

/**
 * 跳过广告记录。
 */
data class SkipRecord(
    val appName: String,
    val packageName: String,
    val timestampMs: Long,
    val selector: String,
    val summary: String,
    /** 点击后确认结果：true 已确认广告消失 / false 未确认 / null 未复核（旧版本记录） */
    val confirmed: Boolean? = null,
    /** 采集端可选字段：单次响应耗时（ms）。未写入时为 null。 */
    val costMs: Long? = null,
    /** 采集端可选字段：本次跳过省下的时长（ms）。未写入时为 null。 */
    val savedMs: Long? = null,
) {
    val displayName: String
        get() = if (appName.isNotBlank()) appName else packageName.ifBlank { UNKNOWN_APP }

    /**
     * 跳过方式：构造时惰性求值一次。
     *
     * 原先写成 get() 每次都调 classifySkipMethod(selector)，而该函数内部会对 selector 做
     * lowercase + 多次 contains —— 每个列表行每次重组都会重算一遍，滚动帧的 ANIMATION
     * 阶段成本里含这部分字符串分配。记录不可变，缓存即可。
     */
    val method: SkipMethod by lazy(LazyThreadSafetyMode.NONE) { classifySkipMethod(selector) }

    private companion object {
        const val UNKNOWN_APP = "未知应用"
    }
}

/**
 * 广告跳过时间线 ViewModel。
 *
 * 从 RecordStore 查询近 7 天的 AD_SKIPPED 事件，并派生趋势 / 方式分布 / 五态。
 */
class AdViewModel : ViewModel() {

    private val store = MonitorApp.instance.container.recordStore

    private val _skipRecords = MutableStateFlow<List<SkipRecord>>(emptyList())
    val skipRecords: StateFlow<List<SkipRecord>> = _skipRecords.asStateFlow()

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    /** 其中"点击后确认广告消失"的条数（成功率才可信） */
    private val _confirmedCount = MutableStateFlow(0)
    val confirmedCount: StateFlow<Int> = _confirmedCount.asStateFlow()

    private val _todayCount = MutableStateFlow(0)
    val todayCount: StateFlow<Int> = _todayCount.asStateFlow()

    private val _lastSkipAt = MutableStateFlow(0L)
    val lastSkipAt: StateFlow<Long> = _lastSkipAt.asStateFlow()

    private val _dailyCounts7 = MutableStateFlow(List(7) { 0f })
    val dailyCounts7: StateFlow<List<Float>> = _dailyCounts7.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _loadError = MutableStateFlow<String?>(null)
    val loadError: StateFlow<String?> = _loadError.asStateFlow()

    init {
        viewModelScope.launch {
            while (true) {
                loadData()
                delay(REFRESH_MS)
            }
        }
    }

    fun loadData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val rangeStart = startOfDay(now) - (WINDOW_DAYS - 1) * DAY_MS
                // SQL 层直接过滤 AD_SKIPPED,避免全量 SCREEN 记录拉取+JSON 反序列化
                val skipEvents = store.queryByTypeAndAction(
                    RecordType.SCREEN, ACTION_AD_SKIPPED, rangeStart, now,
                )

                val records = skipEvents
                    .map { record ->
                        SkipRecord(
                            appName = record.detail?.get("app_name") ?: "",
                            packageName = record.detail?.get("package_name") ?: "",
                            timestampMs = record.timestamp,
                            selector = record.detail?.get("selector") ?: "",
                            summary = record.label,
                            confirmed = record.detail?.get("confirmed")?.toBoolean(),
                            costMs = record.detail?.get("cost_ms")?.toLongOrNull(),
                            savedMs = record.detail?.get("saved_ms")?.toLongOrNull(),
                        )
                    }
                    .sortedByDescending { it.timestampMs }

                _skipRecords.value = records
                _totalCount.value = records.size
                _confirmedCount.value = records.count { it.confirmed == true }
                _todayCount.value = records.count { it.timestampMs >= startOfDay(now) }
                _lastSkipAt.value = records.firstOrNull()?.timestampMs ?: 0L
                _dailyCounts7.value = dailyBuckets(records, rangeStart)
                _isLoading.value = false
                _loadError.value = null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AdViewModel", "加载跳过广告记录失败", e)
                _isLoading.value = false
                _loadError.value = e.message ?: "跳过事件表暂时被占用（SQLITE_BUSY）"
            }
        }
    }

    private fun dailyBuckets(records: List<SkipRecord>, rangeStart: Long): List<Float> {
        val buckets = FloatArray(WINDOW_DAYS)
        records.forEach { record ->
            val idx = ((record.timestampMs - rangeStart) / DAY_MS).toInt()
            if (idx in 0 until WINDOW_DAYS) buckets[idx] += 1f
        }
        return buckets.toList()
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                store.deleteByAction(ACTION_AD_SKIPPED)
                _skipRecords.value = emptyList()
                _totalCount.value = 0
                _confirmedCount.value = 0
                _todayCount.value = 0
                _lastSkipAt.value = 0L
                _dailyCounts7.value = List(WINDOW_DAYS) { 0f }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AdViewModel", "清除跳过广告记录失败", e)
            }
        }
    }

    private companion object {
        const val WINDOW_DAYS = 7
        const val REFRESH_MS = 3_000L
        const val ACTION_AD_SKIPPED = "AD_SKIPPED"
    }
}
