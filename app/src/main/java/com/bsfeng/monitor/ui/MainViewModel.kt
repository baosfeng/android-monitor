package com.bsfeng.monitor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.data.EventEntity
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType
import com.bsfeng.monitor.network.ApiService
import com.bsfeng.monitor.network.EventReporter
import com.bsfeng.monitor.util.AppUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MonitorState(
    val isMonitoring: Boolean = true,
    val recentEvents: List<EventEntity> = emptyList(),
    val unsentCount: Int = 0,
    val todayUnlockCount: Int = 0,
    val todayEventCount: Int = 0,
    val accessibilityEnabled: Boolean = false,
    val serverConnected: Boolean? = null  // null=检测中, true=已连接, false=无法连接
)

class MainViewModel : ViewModel() {
    private val app = com.bsfeng.monitor.MonitorApp.instance
    private val db = DatabaseHelper.getInstance(app)
    private val reporter = EventReporter(app)

    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    init {
        loadData()
    }

    fun checkAccessibilityStatus() {
        val enabled = try {
            val am = app.getSystemService(android.content.Context.ACCESSIBILITY_SERVICE)
                    as android.view.accessibility.AccessibilityManager
            val services = am.getEnabledAccessibilityServiceList(
                android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_GENERIC
            )
            services.any { it.resolveInfo?.serviceInfo?.packageName == app.packageName }
        } catch (_: Exception) {
            false
        }
        _state.value = _state.value.copy(accessibilityEnabled = enabled)
    }

    fun loadData() {
        checkAccessibilityStatus()
        checkServerConnection()

        viewModelScope.launch(Dispatchers.IO) {
            val now = System.currentTimeMillis()
            val todayStart = now - (now % 86400000L)
            try {
                val recent = db.getRecentEvents(20)
                val unsent = db.countUnsent()
                val unlocks = db.countByTypeSince(todayStart, EventType.UNLOCK_SUCCESS.name)
                val total = db.getRecentEvents(10000).size

                _state.value = _state.value.copy(
                    recentEvents = recent,
                    unsentCount = unsent,
                    todayUnlockCount = unlocks,
                    todayEventCount = total
                )
            } catch (_: Exception) {}
        }
    }

    fun checkServerConnection() {
        viewModelScope.launch(Dispatchers.IO) {
            _state.value = _state.value.copy(serverConnected = null)  // 检测中
            val result = ApiService.checkConnection()
            _state.value = _state.value.copy(
                serverConnected = result  // true=已连接, false=无法连接
            )
        }
    }

    fun togglePause() {
        val newPaused = !_isPaused.value
        _isPaused.value = newPaused
        if (!newPaused) {
            loadData()
        }
    }

    fun uploadNow() {
        viewModelScope.launch(Dispatchers.IO) {
            reporter.uploadBatch()
            loadData()
        }
    }

    fun getDeviceId(): String = AppUtils.getDeviceId()
}
