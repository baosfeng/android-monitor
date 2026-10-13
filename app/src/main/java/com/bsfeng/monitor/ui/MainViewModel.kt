package com.bsfeng.monitor.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.record.AppUsageStat
import com.bsfeng.monitor.service.MonitorAccessibilityService
import com.bsfeng.monitor.util.AccessibilityStatus
import com.bsfeng.monitor.util.AppUtils
import com.bsfeng.monitor.util.DebugConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
    /** 当前正在使用的 App 名称（没有 CLOSED 的 OPENED） */
    val activeAppName: String? = null,
    /** 当前 App 已用时长文字（如 "使用中 1分23秒"） */
    val activeAppDurationText: String? = null
)

class MainViewModel : ViewModel() {
    private val app = MonitorApp.instance
    private val store = app.container.recordStore

    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _debugEnabled = MutableStateFlow(DebugConfig.enabled)
    val debugEnabled: StateFlow<Boolean> = _debugEnabled.asStateFlow()

    companion object {
        private const val PAGE_SIZE = 20
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
            // 再启动轮询
            while (true) {
                delay(3000)
                refreshData()
            }
        }
        // 活跃 App 检测：每秒更新，不依赖数据刷新
        viewModelScope.launch {
            while (true) {
                delay(1000)
                updateActiveApp()
            }
        }
    }

    /**
     * 外部触发的加载（如切换 tab、手动刷新）。
     */
    fun loadData() {
        checkAccessibilityStatus()
        viewModelScope.launch(Dispatchers.IO) {
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

            // 先更新列表（尽快显示）
            _state.value = _state.value.copy(
                recentEvents = records.take(loadedCount),
                todayEventCount = records.size,
                isLoadingMore = false,
                hasMore = loadedCount < records.size
            )
            updateActiveApp()

            // 再加载统计数据（较慢的聚合查询）
            val (usage, screens) = withContext(Dispatchers.IO) {
                val u = store.getAppUsageRank(todayStart, now)
                val s = store.queryByType(RecordType.SCREEN, todayStart, now)
                u to s
            }
            _state.value = _state.value.copy(
                appUsageStats = usage,
                screenEvents = screens
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("MainViewModel", "加载今日数据失败", e)
        }
    }

    /**
     * 轮询刷新：重新加载今天数据，但保持当前已加载的页数不变。
     * 新事件会自动出现在列表顶部（因为按时间倒序）。
     */
    private fun refreshData() {
        checkAccessibilityStatus()

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val now = System.currentTimeMillis()
                val todayStart = now - (now % 86400000L)
                val records = store.queryByDate(todayStart, now)
                    .sortedByDescending { it.timestamp }

                cachedTodayRecords = records

                // 如果总记录数比已加载的少（删除事件后），修正 loadedCount
                if (loadedCount > records.size) {
                    loadedCount = records.size
                }

                _state.value = _state.value.copy(
                    recentEvents = records.take(loadedCount),
                    todayEventCount = records.size,
                    hasMore = loadedCount < records.size
                )
                updateActiveApp()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MainViewModel", "轮询刷新数据失败", e)
            }
        }
    }

    /**
     * 检测当前正在使用的 App，计算已用时长。
     * 通过 UsageTracker.getActiveSession() 获取当前活跃 session。
     */
    private fun updateActiveApp() {
        val usageTracker = app.container.usageTracker
        val session = usageTracker.getActiveSession()
        if (session != null) {
            val (name, totalMs) = session
            val min = totalMs / 60000
            val sec = (totalMs % 60000) / 1000
            val text = if (min > 0) "使用中 ${min}分${sec}秒" else "使用中 ${sec}秒"
            _state.value = _state.value.copy(
                activeAppName = name,
                activeAppDurationText = text
            )
        } else {
            // 1. 先用 UsageTracker 记录的最后活跃 App
            val lastLabel = usageTracker.lastActiveLabel
            if (lastLabel != null) {
                _state.value = _state.value.copy(
                    activeAppName = lastLabel,
                    activeAppDurationText = "使用中"
                )
                return
            }
            // 2. 兜底：从数据库最近记录推断（force-stop 后恢复）
            val inferred = inferActiveFromRecentRecords()
            _state.value = _state.value.copy(
                activeAppName = inferred,
                activeAppDurationText = if (inferred != null) "使用中" else null
            )
        }
    }

    /**
     * 从最近 5 分钟的 USAGE 记录推断当前活跃 App（force-stop 重启后 poller 不可用时兜底）。
     */
    private fun inferActiveFromRecentRecords(): String? {
        val fiveMinAgo = System.currentTimeMillis() - 300_000L
        return cachedTodayRecords.firstOrNull {
            it.type is RecordType.APP_USAGE && it.action == "USAGE" && it.timestamp >= fiveMinAgo
        }?.label
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
     */
    fun refreshAccessibilityStatus() {
        val running = MonitorAccessibilityService.isRunning
        val enabled = AccessibilityStatus.isEnabled(app, running)
        _state.value = _state.value.copy(
            accessibilityEnabled = enabled,
            accessibilityServiceRunning = running
        )
    }

    private fun checkAccessibilityStatus() = refreshAccessibilityStatus()

    fun togglePause() {
        val newPaused = !_isPaused.value
        _isPaused.value = newPaused
        if (!newPaused) {
            loadData()
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
                    activeAppName = null,
                    activeAppDurationText = null
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("MainViewModel", "清除今日数据失败", e)
            }
        }
    }

    fun getDeviceId(): String = AppUtils.getDeviceId()
}
