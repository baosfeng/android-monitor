package com.bsfeng.monitor.skip

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 规则管理器。
 *
 * 负责：
 * - 首次启动时合并内置规则到本地存储
 * - 加载、保存用户规则
 * - 启用/禁用规则
 * - 添加/编辑/删除用户自定义规则
 */
class SkipRuleManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val rulesFile = File(context.filesDir, RULES_FILE_NAME)

    /** 所有规则（包括内置 + 用户自定义） */
    private var allRules: MutableList<SkipRule> = mutableListOf()
    private var initialized = false

    init {
        loadRules()
    }

    /**
     * 获取所有规则。
     */
    fun getAllRules(): List<SkipRule> = allRules.toList()

    /**
     * 获取已启用的规则。
     */
    fun getEnabledRules(): List<SkipRule> = allRules.filter { it.enabled }

    /**
     * 按包名查找已启用的规则。
     */
    fun findEnabledRule(packageName: String): SkipRule? =
        allRules.find { it.app == packageName && it.enabled }

    /**
     * 启用/禁用规则。
     */
    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        val index = allRules.indexOfFirst { it.id == ruleId }
        if (index >= 0) {
            allRules[index] = allRules[index].copy(enabled = enabled)
            saveRules()
        }
    }

    /**
     * 添加自定义规则。
     */
    fun addRule(rule: SkipRule) {
        allRules.add(rule)
        saveRules()
    }

    /**
     * 删除规则。
     */
    fun removeRule(ruleId: String) {
        allRules.removeAll { it.id == ruleId }
        saveRules()
    }

    /**
     * 更新规则。
     */
    fun updateRule(rule: SkipRule) {
        val index = allRules.indexOfFirst { it.id == rule.id }
        if (index >= 0) {
            allRules[index] = rule
            saveRules()
        }
    }

    /**
     * 重置为内置规则（删除所有用户自定义和修改）。
     */
    fun resetToBuiltIn() {
        prefs.edit().clear().apply()
        rulesFile.delete()
        loadRules()
    }

    /**
     * 加载规则。
     * 首次调用时合并内置规则到本地存储。
     */
    private fun loadRules() {
        if (!rulesFile.exists()) {
            // 首次启动：写入内置规则
            saveBuiltInRules()
        }

        allRules = readRulesFromFile().toMutableList()
        initialized = true
        Log.d(TAG, "已加载 ${allRules.size} 条规则")
    }

    /**
     * 保存规则到文件。
     */
    private fun saveRules() {
        try {
            val jsonArray = JSONArray()
            allRules.forEach { jsonArray.put(it.toJson()) }
            rulesFile.writeText(jsonArray.toString(2))
            Log.d(TAG, "已保存 ${allRules.size} 条规则")
        } catch (e: Exception) {
            Log.e(TAG, "保存规则失败", e)
        }
    }

    /**
     * 首次启动：写入内置规则。
     */
    private fun saveBuiltInRules() {
        val builtIn = BuiltInRules.getRules()
        val jsonArray = JSONArray()
        builtIn.forEach { jsonArray.put(it.toJson()) }
        try {
            rulesFile.parentFile?.mkdirs()
            rulesFile.writeText(jsonArray.toString(2))
            Log.d(TAG, "已写入 ${builtIn.size} 条内置规则")
        } catch (e: Exception) {
            Log.e(TAG, "写入内置规则失败", e)
        }
    }

    /**
     * 从文件读取规则。
     */
    private fun readRulesFromFile(): List<SkipRule> {
        return try {
            if (!rulesFile.exists()) return emptyList()
            val content = rulesFile.readText()
            val jsonArray = JSONArray(content)
            val rules = mutableListOf<SkipRule>()
            for (i in 0 until jsonArray.length()) {
                try {
                    rules.add(SkipRule.fromJson(jsonArray.getJSONObject(i)))
                } catch (e: Exception) {
                    Log.w(TAG, "跳过无效规则 #$i", e)
                }
            }
            rules
        } catch (e: Exception) {
            Log.e(TAG, "读取规则文件失败", e)
            emptyList()
        }
    }

    companion object {
        private const val TAG = "SkipRuleManager"
        private const val PREFS_NAME = "skip_rule_prefs"
        private const val RULES_FILE_NAME = "skip_rules.json"
    }
}
