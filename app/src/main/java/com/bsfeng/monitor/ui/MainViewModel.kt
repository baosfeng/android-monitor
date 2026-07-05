package com.bsfeng.monitor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.data.EventEntity
import com.bsfeng.monitor.util.AppUtils
import com.bsfeng.monitor.util.DebugConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MonitorState(
    val isMonitoring: Boolean = true,
    val recentEvents: List<EventEntity> = emptyList(),
    val todayEventCount: Int = 0,
    val accessibilityEnabled: Boolean = false
)

class MainViewModel : ViewModel() {
    private val app = com.bsfeng.monitor.MonitorApp.instance
    private val db = DatabaseHelper.getInstance(app)

    private val _state = MutableStateFlow(MonitorState())
    val state: StateFlow<MonitorState> = _state.asStateFlow()

    private val _isPaused = MutableStateFlow(false)
    val isPaused: StateFlow<Boolean> = _isPaused.asStateFlow()

    private val _debugEnabled = MutableStateFlow(DebugConfig.enabled)
    val debugEnabled: StateFlow<Boolean> = _debugEnabled.asStateFlow()

    init {
        loadData()
        // 每 3 秒自动刷新界面
        viewModelScope.launch {
            while (true) {
                delay(3000)
                loadData()
            }
        }
    }

    private fun checkAccessibilityStatus() {
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

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val recent = db.getRecentEvents(20)
                val total = db.getRecentEvents(10000).size

                _state.value = _state.value.copy(
                    recentEvents = recent,
                    todayEventCount = total
                )
            } catch (_: Exception) {}
        }
    }

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

    /** 一键清空所有事件 */
    fun clearEvents() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                db.deleteAllEvents()
                loadData()
            } catch (_: Exception) {}
        }
    }

    fun getDeviceId(): String = AppUtils.getDeviceId()
}
