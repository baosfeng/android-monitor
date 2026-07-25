package com.bsfeng.monitor.ui

import androidx.lifecycle.ViewModel
import com.bsfeng.monitor.MonitorApp
import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.skip.SkipRuleManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 开屏跳过规则管理页面的状态。
 */
data class SkipRuleUiState(
    val rules: List<SkipRule> = emptyList(),
    val enabledCount: Int = 0,
    val isLoading: Boolean = false
)

/**
 * 开屏跳过规则管理 ViewModel。
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
        _state.value = SkipRuleUiState(
            rules = rules,
            enabledCount = rules.count { it.enabled },
            isLoading = false
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
}
