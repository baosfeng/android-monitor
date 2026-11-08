package com.bsfeng.monitor.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.ui.live.DAY_MS
import com.bsfeng.monitor.ui.live.bucketizeDaily
import com.bsfeng.monitor.ui.live.startOfDay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * s03 错态文案（锚点 s03-error 逐字）。
 *
 * 唯一来源：ViewModel 只置 [AdViewModel.loadError] 标记，文案从这里取。
 * 异常原文（SQLite 错误码 / 表名 / 路径）只进 Log.w，不上屏。
 */
internal const val AD_LOAD_ERROR_DESC = "跳过事件表暂时被占用（SQLITE_BUSY）。已跳过的广告不会重复出现。"

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

    /**
     * 读表失败标记（只判态，不承载文案）。
     *
     * 统一口径（全仓约定）：**异常原文只进日志、不上屏**。e.message 是 SQLite 错误码 /
     * 表名 / 路径这类实现细节，上屏既泄漏内部结构、又和页面写死的错态文案对不上 ——
     * 此前这里存了 e.message，屏幕层却只用它 `!= null` 判态，那段原文白算。
     * 上屏文案的唯一来源是常量 [AD_LOAD_ERROR_DESC]。
     */
    private val _loadError = MutableStateFlow(false)
    val loadError: StateFlow<Boolean> = _loadError.asStateFlow()

    /**
     * 近 7 天窗口起点（当地 00:00 再往前 6 天），同时是查询窗口与横轴 / 范围标题的唯一来源。
     *
     * 只在跨零点时变一次：页面以它做 key 重算横轴，既不会像无 key 的 remember 那样冻在进屏那一刻，
     * 也不会每 3s 发射一次导致整屏重组（同文件其余字段逐个判等后再赋值，口径一致）。
     */
    private val _windowStart = MutableStateFlow(adWindowStart(System.currentTimeMillis()))
    val windowStart: StateFlow<Long> = _windowStart.asStateFlow()

    init {
        viewModelScope.launch {
            // 轮询只在 UI 订阅期间运行：切走 Tab 或退到后台时订阅数归零，循环挂起。
            // 与 MainViewModel / OverviewViewModel 同一口径 —— 广告页此前是唯一「切走也每 3s 查 7 天」
            // 的屏，而它的窗口是全站最长的。
            _skipRecords.subscriptionCount.collectLatest { subscribers ->
                if (subscribers <= 0) return@collectLatest
                // 先出数据再进入周期休眠：否则切回本 Tab 要先空等一个 REFRESH_MS
                while (true) {
                    loadData()
                    delay(REFRESH_MS)
                }
            }
        }
    }

    fun loadData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val todayStart = startOfDay(now)
                val rangeStart = adWindowStart(now)
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

                // 逐个判等后再赋值：records 每轮都是新 List，直接赋值会让 StateFlow 每 3s 必发射，
                // AdScreen 里 4 个 remember(records) 派生缓存随之全部失效、整屏重组。
                val todayCount = records.count { it.timestampMs >= todayStart }
                val confirmedCount = records.count { it.confirmed == true }
                val lastSkipAt = records.firstOrNull()?.timestampMs ?: 0L
                val dailyCounts = dailyBuckets(records, rangeStart)
                if (_skipRecords.value != records) _skipRecords.value = records
                if (_totalCount.value != records.size) _totalCount.value = records.size
                if (_confirmedCount.value != confirmedCount) _confirmedCount.value = confirmedCount
                if (_todayCount.value != todayCount) _todayCount.value = todayCount
                if (_lastSkipAt.value != lastSkipAt) _lastSkipAt.value = lastSkipAt
                if (_dailyCounts7.value != dailyCounts) _dailyCounts7.value = dailyCounts
                if (_windowStart.value != rangeStart) _windowStart.value = rangeStart
                if (_isLoading.value) _isLoading.value = false
                if (_loadError.value) _loadError.value = false
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                // 异常原文只进日志：e.message 是内部实现细节（SQLite 错误码 / 表名 / 路径），
                // 直接上屏既泄漏实现细节、又和页面写死（锚点 s03-error 逐字）的错态文案对不上。
                Log.w("AdViewModel", "加载跳过广告记录失败", e)
                _isLoading.value = false
                _loadError.value = true
            }
        }
    }

    private fun dailyBuckets(records: List<SkipRecord>, rangeStart: Long): List<Float> =
        bucketizeDaily(records.map { it.timestampMs }, rangeStart, AD_WINDOW_DAYS)

    private companion object {
        const val REFRESH_MS = 3_000L
        const val ACTION_AD_SKIPPED = "AD_SKIPPED"
    }
}

/** 近 7 天趋势的窗口天数（横轴 7 个柱）。 */
internal const val AD_WINDOW_DAYS = 7

/**
 * 近 7 天窗口起点：当地 00:00 再往前 [AD_WINDOW_DAYS] - 1 天。
 *
 * 查询窗口、横轴日标签与「–」范围标题三处必须同源，否则跨零点后三处会各停各的
 * （历史缺陷：横轴用无 key 的 remember 冻在进屏那一刻，跨零点后与 VM 的查询窗口错位）。
 * 纯函数、无 Android 依赖：由 [com.bsfeng.monitor.ui.HomeWindowRefreshTest] 钉住跨零点行为。
 */
internal fun adWindowStart(now: Long): Long = startOfDay(now) - (AD_WINDOW_DAYS - 1) * DAY_MS
