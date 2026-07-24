package com.bsfeng.monitor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.data.EventEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.json.JSONObject

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
 * 从数据库查询 skip_ad=true 的事件，按时间倒序排列。
 */
class AdViewModel : ViewModel() {

    private val app = com.bsfeng.monitor.MonitorApp.instance
    private val db = DatabaseHelper.getInstance(app)

    private val _skipRecords = MutableStateFlow<List<SkipRecord>>(emptyList())
    val skipRecords: StateFlow<List<SkipRecord>> = _skipRecords.asStateFlow()

    private val _totalCount = MutableStateFlow(0)
    val totalCount: StateFlow<Int> = _totalCount.asStateFlow()

    init {
        loadData()
        // 每 3 秒自动刷新
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
                // 查询所有事件中 summary 以 ⏭️ 开头的（即跳过广告事件）
                val allRecent = db.getRecentEvents(500)
                val skipEvents = allRecent.filter { e ->
                    e.summary.startsWith("⏭️") || extractSkipFlag(e.extraJson)
                }

                _skipRecords.value = skipEvents.map { toSkipRecord(it) }
                _totalCount.value = skipEvents.size
            } catch (_: Exception) {}
        }
    }

    /** 通过 extraJson 解析跳过标记（兜底方案） */
    private fun extractSkipFlag(extraJson: String): Boolean {
        return try {
            val obj = JSONObject(extraJson)
            val rawExtra = obj.optJSONObject("rawExtra")
            rawExtra?.optString("skip_ad") == "true"
        } catch (_: Exception) {
            false
        }
    }

    /** 将事件实体转换为 SkipRecord */
    private fun toSkipRecord(event: EventEntity): SkipRecord {
        var appName = ""
        var pkg = ""
        var selector = ""

        try {
            val obj = JSONObject(event.extraJson)
            val rawExtra = obj.optJSONObject("rawExtra")
            if (rawExtra != null) {
                appName = rawExtra.optString("app_name", "")
                pkg = rawExtra.optString("package_name", "")
                selector = rawExtra.optString("skip_selector", "")
            }
        } catch (_: Exception) {}

        return SkipRecord(
            appName = appName.ifBlank { event.summary.removePrefix("⏭️ 自动跳过 ").removeSuffix(" 的开屏广告") },
            packageName = pkg,
            timestampMs = event.timestampMs,
            selector = selector,
            summary = event.summary
        )
    }

    /** 一键清空跳过记录 */
    fun clearAll() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 清空所有事件（与 MainViewModel.clearEvents 共用）
                db.deleteAllEvents()
                loadData()
            } catch (_: Exception) {}
        }
    }
}
