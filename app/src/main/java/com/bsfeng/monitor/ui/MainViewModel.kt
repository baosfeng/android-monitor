package com.bsfeng.monitor.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.record.AppUsageStat
import com.bsfeng.monitor.service.MonitorAccessibilityService
import com.bsfeng.monitor.skip.SkipConfig
import com.bsfeng.monitor.util.AccessibilityStatus
import com.bsfeng.monitor.util.AppUtils
import com.bsfeng.monitor.ui.live.hourlyIntensityOf
import com.bsfeng.monitor.util.DebugConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class MonitorState(
    val isMonitoring: Boolean = true,
    /** 当前已加载的事件列表（分页） */
    val recentEvents: List<Record> = emptyList(),
    /** 今天的事件总数 */
    val todayEventCount: Int = 0,
    /** 是否正在加载更多 */
    val isLoadingMore: Boolean = false,
    /** 是否还有更多数据可加载 */
    val hasMore: Boolean = false,
    val accessibilityEnabled: Boolean = false,
    /** 无障碍服务实例是否已连接（用于区分"已勾选但未连接"） */
    val accessibilityServiceRunning: Boolean = false,
    val appUsageStats: List<AppUsageStat> = emptyList(),
    val screenEvents: List<Record> = emptyList(),
    /** 首屏加载中（s02 五态之 loading） */
    val isLoading: Boolean = true,
    /** 加载失败原因（s02 五态之 error）；null = 正常 */
    val loadError: String? = null,
    /** 今日逐时事件强度（24 格热力条） */
    val hourlyIntensity: List<Float> = List(24) { 0f },
    /** 今日各类型事件条数（s02 类型 chip 的计数） */
    val typeCounts: Map<RecordType, Int> = emptyMap(),
)

class MainViewModel : ViewModel() {
    private val app = MonitorApp.instance
    private val store = app.container.recordStore

    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    /**
     * 最近一次数据刷新时间（s02 页头「最近更新」）。
     *
     * 独立于 [state]：它每轮轮询都会变，若留在列表 state 里，data class 就永远不等 →
     * 每 3s 强制 emit 一次 → 整页重组 + LazyColumn DSL 重建。页头自己收这个值即可。
     */
    private val _lastUpdatedAt = MutableStateFlow(0L)
    val lastUpdatedAt: StateFlow<Long> = _lastUpdatedAt.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _debugEnabled = MutableStateFlow(DebugConfig.enabled)
    val debugEnabled: StateFlow<Boolean> = _debugEnabled.asStateFlow()

    /** 开屏跳过监控窗口（毫秒） */
    private val _watchWindowMs = MutableStateFlow(SkipConfig.watchWindowMs)
    val watchWindowMs: StateFlow<Long> = _watchWindowMs.asStateFlow()

    companion object {
        private const val PAGE_SIZE = 20
        /** 轮询周期：仅在 UI 订阅期间生效（见 [init]） */
        private const val POLL_MS = 3_000L
    }

    /** 今天所有记录的缓存（按时间倒序，最新的在前） */
    private var cachedTodayRecords: List<Record> = emptyList()
    /** 当前已加载的记录数 */
    private var loadedCount = 0
    private var initialLoadDone = false

    init {
        viewModelScope.launch {
            loadDataInternal()  // 等待初次加载完成
            initialLoadDone = true
            // 轮询：只有 UI 正在订阅（本屏可见、且未退到后台——UI 侧用
            // collectAsStateWithLifecycle 收集）时才跑；订阅数为 0 直接挂起，
            // 不再出现「切走 Tab / 退到后台仍每 3s 干活」的情况。
            _state.subscriptionCount.collectLatest { subscribers ->
                if (subscribers <= 0) return@collectLatest
                // 先出数据再进入周期休眠：与 OverviewViewModel.init 同一口径，
                // 否则切回本 Tab 后要先空等一个 POLL_MS（3s）才刷新。
                while (true) {
                    refreshData()
                    delay(POLL_MS)
                }
            }
        }
        // 原「每秒检测活跃 App」的循环已移除：activeApp* 无任何 UI 消费，
        // 每秒一次主线程 StateFlow 发射（文本含秒数，逐秒都变）会让整屏每秒重组。
    }

    /**
     * 外部触发的加载（如切换 tab、手动刷新）。
     */
    fun loadData() {
        viewModelScope.launch(Dispatchers.IO) {
            checkAccessibilityStatus()
            loadDataInternal()
        }
    }

    /**
     * 内部初次加载 + 统计数据（挂起函数，确保串行执行）。
     */
    private suspend fun loadDataInternal() {
        checkAccessibilityStatus()
        try {
            val now = System.currentTimeMillis()
            val todayStart = now - (now % 86400000L)

            // 数据库操作在 IO 线程
            val records = withContext(Dispatchers.IO) {
                store.queryByDate(todayStart, now).sortedByDescending { it.timestamp }
            }

            cachedTodayRecords = records
            loadedCount = PAGE_SIZE.coerceAtMost(records.size)

            // 先更新列表（尽快显示）：数据没变就不发（刷新时页面照常，列表不必重组）
            val old = _state.value
            val next = old.copy(
                recentEvents = records.take(loadedCount),
                todayEventCount = records.size,
                isLoadingMore = false,
                hasMore = loadedCount < records.size,
                hourlyIntensity = hourlyIntensityOf(records),
                typeCounts = records.groupingBy { it.type }.eachCount(),
                isLoading = false,
                loadError = null
            )
            if (next != old) _state.value = next
            _lastUpdatedAt.value = now

            // 再加载统计数据（较慢的聚合查询）
            val (usage, screens) = withContext(Dispatchers.IO) {
                val u = store.getAppUsageRank(todayStart, now)
                val s = store.queryByType(RecordType.SCREEN, todayStart, now)
                u to s
            }
            val statsOld = _state.value
            val statsNext = statsOld.copy(
                appUsageStats = usage,
                screenEvents = screens
            )
            if (statsNext != statsOld) _state.value = statsNext
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("MainViewModel", "加载今日数据失败", e)
            _state.value = _state.value.copy(
                isLoading = false,
                loadError = e.message ?: "今日事件读取失败"
            )
        }
    }

    /**
     * 轮询刷新：重新加载今天数据，但保持当前已加载的页数不变。
     * 新事件会自动出现在列表顶部（因为按时间倒序）。
     */
    private fun refreshData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 跨进程读设置 / 无障碍服务列表：留在 IO，且吃 30s TTL 缓存
                checkAccessibilityStatus()
                val now = System.currentTimeMillis()
                val todayStart = now - (now % 86400000L)
                val records = store.queryByDate(todayStart, now)
                    .sortedByDescending { it.timestamp }

                cachedTodayRecords = records

                // 如果总记录数比已加载的少（删除事件后），修正 loadedCount
                if (loadedCount > records.size) {
                    loadedCount = records.size
                }

                // 数据没变就不发：StateFlow 每 3s 发一次会让整页重组 + LazyColumn DSL 重建
                val old = _state.value
                val next = old.copy(
                    recentEvents = records.take(loadedCount),
                    todayEventCount = records.size,
                    hasMore = loadedCount < records.size,
                    hourlyIntensity = hourlyIntensityOf(records),
                    typeCounts = records.groupingBy { it.type }.eachCount(),
                    loadError = null
                )
                if (next != old) _state.value = next
                // 页头「最近更新」照常逐轮刷新，但它只带动页头一行的重组
                _lastUpdatedAt.value = now
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MainViewModel", "轮询刷新数据失败", e)
            }
        }
    }

    /**
     * 分页加载更多（滚动到底部时调用）。
     */
    fun loadMore() {
        val state = _state.value
        if (state.isLoadingMore || loadedCount >= cachedTodayRecords.size) return

        _state.value = state.copy(isLoadingMore = true)

        // 模拟短暂延迟，让加载指示器可见
        viewModelScope.launch(Dispatchers.IO) {
            loadedCount = (loadedCount + PAGE_SIZE).coerceAtMost(cachedTodayRecords.size)
            _state.value = _state.value.copy(
                recentEvents = cachedTodayRecords.take(loadedCount),
                isLoadingMore = false,
                hasMore = loadedCount < cachedTodayRecords.size
            )
        }
    }

    /**
     * 刷新无障碍服务状态。
     *
     * 判定逻辑见 [AccessibilityStatus]：系统设置项 / [android.view.accessibility.AccessibilityManager]
     * 列表 / 服务实例三者任一命中即视为已开启（HyperOS 上单个来源会误报"未开启"）。
     *
     * 走 [AccessibilityStatus.isEnabledFresh]：实时值同时回写 30s TTL 缓存，
     * 否则授权返回后紧接着的轮询会用旧 `false` 把界面刷回「未开启」。
     */
    fun refreshAccessibilityStatus() {
        val running = MonitorAccessibilityService.isRunning
        val enabled = AccessibilityStatus.isEnabledFresh(app, running)
        _state.value = _state.value.copy(
            accessibilityEnabled = enabled,
            accessibilityServiceRunning = running
        )
    }

    /**
     * 轮询路径的状态刷新：走 [AccessibilityStatus.isEnabledCached]（30s TTL），
     * 避免每 3s 一次跨进程设置读取；用户操作触发的刷新仍走
     * [refreshAccessibilityStatus]（实时值）。
     */
    private fun checkAccessibilityStatus() {
        val running = MonitorAccessibilityService.isRunning
        val enabled = AccessibilityStatus.isEnabledCached(app, running)
        _state.value = _state.value.copy(
            accessibilityEnabled = enabled,
            accessibilityServiceRunning = running
        )
    }

    fun togglePause() {
        val newPaused = !_isPaused.value
        _isPaused.value = newPaused
        if (!newPaused) {
            loadData()
        }
    }

    /** 设置开屏跳过的监控窗口（毫秒）：落库并重载内置规则的时间闸门 */
    fun setWatchWindowMs(windowMs: Long) {
        viewModelScope.launch(Dispatchers.IO) {
            app.container.skipRuleManager.setWatchWindowMs(windowMs)
            _watchWindowMs.value = SkipConfig.watchWindowMs
        }
    }

    fun toggleDebug() {
        val newEnabled = !_debugEnabled.value
        _debugEnabled.value = newEnabled
        DebugConfig.setEnabled(newEnabled)
    }

    fun clearToday() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val todayStart = now - (now % 86400000L)
                store.deleteByRange(todayStart, now)
                cachedTodayRecords = emptyList()
                loadedCount = 0
                _state.value = _state.value.copy(
                    recentEvents = emptyList(),
                    todayEventCount = 0,
                    hasMore = false,
                    isLoadingMore = false,
                    hourlyIntensity = List(24) { 0f },
                    typeCounts = emptyMap()
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MainViewModel", "清除今日数据失败", e)
            }
        }
    }

    /** 五态 error 态的「重试」入口。 */
    fun retry() {
        loadData()
    }

    fun getDeviceId(): String = AppUtils.getDeviceId()
}
