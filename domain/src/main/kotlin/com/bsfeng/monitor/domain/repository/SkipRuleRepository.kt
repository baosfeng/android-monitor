package com.bsfeng.monitor.domain.repository

import com.bsfeng.monitor.domain.model.SkipRule

/**
 * 跳过规则仓储接口。
 * 由 Data 层实现，Domain 层仅依赖此接口。
 */
interface SkipRuleRepository {

    /**
     * 获取所有规则。
     */
    fun getAllRules(): List<SkipRule>

    /**
     * 根据包名获取规则。
     */
    fun getRuleByApp(packageName: String): SkipRule?

    /**
     * 保存规则。
     */
    fun saveRule(rule: SkipRule)

    /**
     * 删除规则。
     */
    fun deleteRule(ruleId: String)

    /**
     * 启用/禁用规则。
     */
    fun setRuleEnabled(ruleId: String, enabled: Boolean)
}
