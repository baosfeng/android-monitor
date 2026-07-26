package com.bsfeng.monitor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.model.Record
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
    val recentEvents: List<Record> = emptyList(),
    val todayEventCount: Int = 0,
    val accessibilityEnabled: Boolean = false
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

    init {
        loadData()
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
                val now = System.currentTimeMillis()
                val todayStart = now - (now % 86400000L)
                val recent = store.queryByDate(todayStart - 86400000L, now)
                val total = recent.size

                _state.value = _state.value.copy(
                    recentEvents = recent.reversed().take(20),
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

    fun clearEvents() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val before = System.currentTimeMillis()
                store.deleteBefore(before)
                loadData()
            } catch (_: Exception) {}
        }
    }

    fun getDeviceId(): String = AppUtils.getDeviceId()
}
