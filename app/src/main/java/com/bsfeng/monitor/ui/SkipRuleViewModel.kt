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

/** 一个订阅源的展示信息 */
data class SubscriptionUi(
    val url: String,
    val version: Long,
    val ruleCount: Int
)

/**
 * 开屏跳过规则管理页面的状态。
 */
data class SkipRuleUiState(
    val rules: List<SkipRule> = emptyList(),
    val enabledCount: Int = 0,
    val isLoading: Boolean = false,
    /** 已添加的订阅源（可多个，各自独立存储） */
    val subscriptions: List<SubscriptionUi> = emptyList(),
    /** 正在更新的订阅地址（null 表示当前没有更新任务） */
    val updatingUrl: String? = null,
    /** 最近一次导出快照的文件路径 */
    val snapshotPath: String? = null,
    /** 操作提示（一次性） */
    val message: String? = null
)

/**
 * 开屏跳过规则管理 ViewModel。
 *
 * 除规则列表外还负责：GKD 订阅拉取（[GkdSubscription]，支持多个订阅源）与
 * 页面快照导出请求（[SnapshotRequest]）。
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
        _state.value = _state.value.copy(
            rules = rules,
            enabledCount = rules.count { it.enabled },
            isLoading = false,
            subscriptions = ruleManager.subscriptionMetas().map {
                SubscriptionUi(url = it.url, version = it.version, ruleCount = it.ruleCount)
            },
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
        _state.value = _state.value.copy(message = "已重置为内置规则（订阅已清空）")
        refresh()
    }

    fun getRule(ruleId: String): SkipRule? = ruleManager.getAllRules().find { it.id == ruleId }

    // ==================== 订阅规则（多订阅源） ====================

    /**
     * 拉取并应用**一个**订阅源（IO 线程执行，结果写入一次性提示）。
     *
     * 地址已存在时视为"更新该源"，不会重复添加；新地址则新增一个订阅源。
     */
    fun updateSubscription(url: String) {
        val target = url.trim()
        if (target.isEmpty()) {
            _state.value = _state.value.copy(message = "订阅地址不能为空")
            return
        }
        _state.value = _state.value.copy(updatingUrl = target, message = null)
        viewModelScope.launch(Dispatchers.IO) {
            val outcome = GkdSubscription.update(ruleManager, target)
            val message = when (outcome) {
                is GkdSubscription.Outcome.Updated ->
                    "订阅已更新：v" + outcome.version + "，" + outcome.ruleCount + " 条规则"
                GkdSubscription.Outcome.UpToDate -> "订阅已是最新版本"
                is GkdSubscription.Outcome.Failed -> "订阅更新失败：" + outcome.reason
            }
            _state.value = _state.value.copy(updatingUrl = null, message = message)
            refresh()
        }
    }

    /** 删除指定订阅源（保留其它订阅源、自定义与内置规则） */
    fun removeSubscription(url: String) {
        ruleManager.clearSubscription(url)
        _state.value = _state.value.copy(message = "已删除该订阅源的规则")
        refresh()
    }

    // ==================== 页面快照 ====================

    /**
     * 请求导出目标 App 的页面快照。
     *
     * 用「切到目标 App 后自动导出」而不是固定延时：真机实测固定延时常导到 App 首页（开屏页已消失），
     * 而开屏广告页在切过去约 2.5 秒时通常正显示跳过按钮。
     */
    fun requestSnapshot() {
        SnapshotRequest.requestOnAppSwitch(MonitorApp.instance.packageName)
        _state.value = _state.value.copy(
            message = "已请求快照：请切到目标 App，约 " + (SnapshotRequest.SWITCH_SETTLE_MS / 1000.0) + " 秒后自动导出"
        )
    }

    /** 消费一次性提示 */
    fun consumeMessage() {
        if (_state.value.message != null) {
            _state.value = _state.value.copy(message = null)
        }
    }
}
