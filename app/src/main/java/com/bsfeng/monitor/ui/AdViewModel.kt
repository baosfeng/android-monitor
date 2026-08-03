package com.bsfeng.monitor.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.model.RecordType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 跳过广告记录。
 */
data class SkipRecord(
    val appName: String,
    val packageName: String,
    val timestampMs: Long,
    val selector: String,
    val summary: String
)

/**
 * 广告跳过时间线 ViewModel。
 *
 * 从 RecordStore 查询 AD_SKIPPED 事件。
 */
class AdViewModel : ViewModel() {

    private val store = MonitorApp.instance.container.recordStore

    private val _skipRecords = MutableStateFlow<List<SkipRecord>>(emptyList())
    val skipRecords: StateFlow<List<SkipRecord>> = _skipRecords.asStateFlow()

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    init {
        loadData()
        viewModelScope.launch {
            while (true) {
                delay(3000)
                loadData()
            }
        }
    }

    fun loadData() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val todayStart = System.currentTimeMillis() - 86400000L * 7
                // SQL 层直接过滤 AD_SKIPPED,避免全量 SCREEN 记录拉取+JSON 反序列化
                val skipEvents = store.queryByTypeAndAction(
                    RecordType.SCREEN, "AD_SKIPPED", todayStart, System.currentTimeMillis()
                )

                _skipRecords.value = skipEvents.map { record ->
                    SkipRecord(
                        appName = record.detail?.get("app_name") ?: "",
                        packageName = record.detail?.get("package_name") ?: "",
                        timestampMs = record.timestamp,
                        selector = record.detail?.get("selector") ?: "",
                        summary = record.label
                    )
                }
                _totalCount.value = skipEvents.size
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AdViewModel", "加载跳过广告记录失败", e)
            }
        }
    }

    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                store.deleteByAction("AD_SKIPPED")
                _skipRecords.value = emptyList()
                _totalCount.value = 0
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w("AdViewModel", "清除跳过广告记录失败", e)
            }
        }
    }
}
