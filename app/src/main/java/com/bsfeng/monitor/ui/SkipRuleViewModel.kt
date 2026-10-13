package com.bsfeng.monitor.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.skip.GkdSubscription
import com.bsfeng.monitor.skip.SkipRuleManager
import com.bsfeng.monitor.skip.SnapshotRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 开屏跳过规则管理页面的状态。
 */
data class SkipRuleUiState(
    val rules: List<SkipRule> = emptyList(),
    val enabledCount: Int = 0,
    val isLoading: Boolean = false,
    /** 订阅地址（未订阅为 null） */
    val subscriptionUrl: String? = null,
    /** 订阅版本号 */
    val subscriptionVersion: Long = 0L,
    /** 订阅规则条数 */
    val subscriptionRuleCount: Int = 0,
    /** 正在更新订阅 */
    val subscriptionUpdating: Boolean = false,
    /** 最近一次导出快照的文件路径 */
    val snapshotPath: String? = null,
    /** 操作提示（一次性） */
    val message: String? = null
)

/**
 * 开屏跳过规则管理 ViewModel。
 *
 * 除规则列表外还负责：GKD 订阅拉取（[GkdSubscription]）与页面快照导出请求（[SnapshotRequest]）。
 */
class SkipRuleViewModel : ViewModel() {

    private val ruleManager: SkipRuleManager = MonitorApp.instance.container.skipRuleManager

    private val _state = MutableStateFlow(SkipRuleUiState())
    val state: StateFlow<SkipRuleUiState> = _state.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        val rules = ruleManager.getAllRules()
        val meta = ruleManager.subscriptionMeta()
        _state.value = _state.value.copy(
            rules = rules,
            enabledCount = rules.count { it.enabled },
            isLoading = false,
            subscriptionUrl = meta?.url,
            subscriptionVersion = meta?.version ?: 0L,
            subscriptionRuleCount = meta?.ruleCount ?: 0,
            snapshotPath = SnapshotRequest.lastPath
        )
    }

    fun toggleRule(ruleId: String) {
        val rule = ruleManager.getAllRules().find { it.id == ruleId } ?: return
        ruleManager.setRuleEnabled(ruleId, !rule.enabled)
        refresh()
    }

    fun deleteRule(ruleId: String) {
        ruleManager.removeRule(ruleId)
        refresh()
    }

    fun resetToBuiltIn() {
        ruleManager.resetToBuiltIn()
        refresh()
    }

    fun getRule(ruleId: String): SkipRule? = ruleManager.getAllRules().find { it.id == ruleId }

    // ==================== 订阅规则 ====================

    /** 拉取并应用订阅（IO 线程执行，结果写入一次性提示） */
    fun updateSubscription(url: String) {
        val target = url.trim()
        if (target.isEmpty()) {
            _state.value = _state.value.copy(message = "订阅地址不能为空")
            return
        }
        _state.value = _state.value.copy(subscriptionUpdating = true, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = GkdSubscription.update(ruleManager, target)
            val message = when (outcome) {
                is GkdSubscription.Outcome.Updated ->
                    "订阅已更新：v" + outcome.version + "，" + outcome.ruleCount + " 条规则"
                GkdSubscription.Outcome.UpToDate -> "订阅已是最新版本"
                is GkdSubscription.Outcome.Failed -> "订阅更新失败：" + outcome.reason
            }
            _state.value = _state.value.copy(subscriptionUpdating = false, message = message)
            refresh()
        }
    }

    /** 清空订阅（保留自定义与内置规则） */
    fun clearSubscription() {
        ruleManager.clearSubscription()
        _state.value = _state.value.copy(message = "已清除订阅规则")
        refresh()
    }

    // ==================== 页面快照 ====================

    /** 请求导出当前前台页面快照（延迟几秒，便于用户切到目标 App） */
    fun requestSnapshot() {
        SnapshotRequest.request()
        _state.value = _state.value.copy(
            message = "已在 " + (SnapshotRequest.DEFAULT_DELAY_MS / 1000) + " 秒后导出当前页面快照，请先切到目标 App"
        )
    }

    /** 消费一次性提示 */
    fun consumeMessage() {
        if (_state.value.message != null) {
            _state.value = _state.value.copy(message = null)
        }
    }
}
