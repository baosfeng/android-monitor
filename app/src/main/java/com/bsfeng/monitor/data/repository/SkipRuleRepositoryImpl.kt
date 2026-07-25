package com.bsfeng.monitor.data.repository

import com.bsfeng.monitor.domain.model.SkipRule
import com.bsfeng.monitor.domain.repository.SkipRuleRepository
import com.bsfeng.monitor.skip.SkipRuleManager

/**
 * 跳过规则仓储实现 — 委托给 SkipRuleManager。
 */
class SkipRuleRepositoryImpl(
    private val ruleManager: SkipRuleManager
) : SkipRuleRepository {

    override fun getAllRules(): List<SkipRule> = ruleManager.getAllRules()

    override fun getRuleByApp(packageName: String): SkipRule? = ruleManager.findEnabledRule(packageName)

    override fun saveRule(rule: SkipRule) = ruleManager.addRule(rule)

    override fun deleteRule(ruleId: String) = ruleManager.removeRule(ruleId)

    override fun setRuleEnabled(ruleId: String, enabled: Boolean) =
        ruleManager.setRuleEnabled(ruleId, enabled)
}
