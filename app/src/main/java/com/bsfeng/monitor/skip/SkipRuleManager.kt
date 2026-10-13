package com.bsfeng.monitor.skip

import android.content.Context
import android.util.Log
import com.bsfeng.monitor.domain.model.SkipRule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 跳过规则管理器。
 *
 * 规则来源（按匹配优先级从高到低）：
 * 1. **用户自定义规则**：`files/skip_rules.json`（仅存用户新增/修改的规则）
 * 2. **订阅规则**：`files/skip_subscription.json`（由 [GkdSubscription] 拉取写入，GKD 订阅格式）
 * 3. **内置规则**：见 [BuiltInRules]（全局开屏规则 + 少量应用规则）
 *
 * 启停状态**不复制规则副本**，而是把被禁用的规则 ID 存进 SharedPreferences
 * （`disabled_ids`），这样内置规则升级时不会丢用户设置。
 */
class SkipRuleManager(private val context: Context) {

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val customRulesFile = File(context.filesDir, CUSTOM_RULES_FILE_NAME)
    private val subscriptionFile = File(context.filesDir, SUBSCRIPTION_FILE_NAME)

    private var builtInRules: List<SkipRule> = emptyList()
    private var subscriptionRules: List<SkipRule> = emptyList()
    private var customRules: List<SkipRule> = emptyList()
    private var disabledIds: MutableSet<String> = mutableSetOf()
    private var subscriptionMeta: SubscriptionMeta? = null

    init {
        load()
    }

    // ==================== 加载与保存 ====================

    /** 重新加载全部规则（订阅更新后调用） */
    fun load() {
        migrateLegacyRulesIfNeeded()
        builtInRules = BuiltInRules.getRules()
        customRules = readRules(customRulesFile)
        subscriptionRules = readRules(subscriptionFile)
        disabledIds = (prefs.getStringSet(KEY_DISABLED_IDS, emptySet()) ?: emptySet()).toMutableSet()
        subscriptionMeta = readSubscriptionMeta()
        prefs.edit().putInt(KEY_BUILT_IN_VERSION, BuiltInRules.BUILT_IN_VERSION).apply()
        SelectorCache.clear()
        Log.d(TAG, "规则加载完成: 内置 " + builtInRules.size + " 条, 订阅 " + subscriptionRules.size +
            " 条, 自定义 " + customRules.size + " 条, 禁用 " + disabledIds.size + " 条")
    }

    /** 全部规则（含用户启停状态），自定义 → 订阅 → 内置 */
    fun getAllRules(): List<SkipRule> =
        (customRules + subscriptionRules + builtInRules).map { it.copy(enabled = it.id !in disabledIds) }

    /** 已启用的规则 */
    fun getEnabledRules(): List<SkipRule> = getAllRules().filter { it.enabled }

    /**
     * 查找指定 App 当前应参与匹配的规则：应用规则优先，其后是全局规则。
     *
     * @param packageName 前台包名
     */
    fun findRulesFor(packageName: String): List<SkipRule> {
        if (isGloballyExcluded(packageName)) {
            return getAllRules().filter { it.enabled && !it.isGlobal && it.app == packageName }
        }
        val enabled = getEnabledRules()
        return enabled.filter { !it.isGlobal && it.app == packageName } + enabled.filter { it.isGlobal }
    }

    /** 全局规则是否对该包名生效（系统 UI/桌面/浏览器等默认排除） */
    fun isGloballyExcluded(packageName: String): Boolean = packageName in BuiltInRules.GLOBAL_EXCLUDED_PACKAGES

    /** 按 ID 查找规则 */
    fun getRule(ruleId: String): SkipRule? = getAllRules().find { it.id == ruleId }

    // ==================== 启停与增删 ====================

    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        if (enabled) {
            disabledIds.remove(ruleId)
        } else {
            disabledIds.add(ruleId)
        }
        prefs.edit().putStringSet(KEY_DISABLED_IDS, disabledIds).apply()
    }

    /** 新增/更新自定义规则 */
    fun addRule(rule: SkipRule) {
        customRules = customRules.filter { it.id != rule.id } + rule
        saveRules(customRulesFile, customRules)
        disabledIds.remove(rule.id)
        prefs.edit().putStringSet(KEY_DISABLED_IDS, disabledIds).apply()
    }

    fun updateRule(rule: SkipRule) = addRule(rule)

    /**
     * 删除规则：自定义规则直接删除；内置/订阅规则改为禁用（文件不可变）。
     */
    fun removeRule(ruleId: String) {
        val isCustom = customRules.any { it.id == ruleId }
        if (isCustom) {
            customRules = customRules.filter { it.id != ruleId }
            saveRules(customRulesFile, customRules)
        } else {
            setRuleEnabled(ruleId, false)
        }
    }

    /** 重置为内置规则：清空自定义规则与订阅缓存，恢复全部内置规则启用 */
    fun resetToBuiltIn() {
        customRules = emptyList()
        customRulesFile.delete()
        subscriptionRules = emptyList()
        subscriptionFile.delete()
        subscriptionMeta = null
        prefs.edit().remove(KEY_DISABLED_IDS).remove(KEY_SUBSCRIPTION_META).apply()
        disabledIds = mutableSetOf()
        load()
    }

    // ==================== 订阅 ====================

    /** 当前订阅元信息（未添加订阅时为 null） */
    fun subscriptionMeta(): SubscriptionMeta? = subscriptionMeta

    /** 保存订阅结果（拉取成功后调用） */
    fun saveSubscription(url: String, version: Long, rules: List<SkipRule>) {
        subscriptionRules = rules
        saveRules(subscriptionFile, rules)
        subscriptionMeta = SubscriptionMeta(url = url, version = version, ruleCount = rules.size, updatedAt = System.currentTimeMillis())
        prefs.edit().putString(KEY_SUBSCRIPTION_META, subscriptionMeta!!.toJson().toString()).apply()
        SelectorCache.clear()
        Log.i(TAG, "订阅已更新: " + url + " version=" + version + " 规则=" + rules.size)
    }

    /** 清空订阅 */
    fun clearSubscription() {
        subscriptionRules = emptyList()
        subscriptionFile.delete()
        subscriptionMeta = null
        prefs.edit().remove(KEY_SUBSCRIPTION_META).apply()
        SelectorCache.clear()
    }

    private fun readSubscriptionMeta(): SubscriptionMeta? {
        val text = prefs.getString(KEY_SUBSCRIPTION_META, null) ?: return null
        return try {
            val json = JSONObject(text)
            SubscriptionMeta(
                url = json.getString("url"),
                version = json.optLong("version", 0L),
                ruleCount = json.optInt("ruleCount", 0),
                updatedAt = json.optLong("updatedAt", 0L)
            )
        } catch (e: Exception) {
            Log.w(TAG, "读取订阅元信息失败", e)
            null
        }
    }

    // ==================== 文件读写 ====================

    /**
     * v1 → v2 迁移：v1 会把**整份内置规则快照**写进 `files/skip_rules.json`，
     * 升级后这些规则会被当成"用户自定义规则"加载（数量大且选择器陈旧）。
     *
     * 判定：内置版本 < v2 起点且该文件规则数 ≥ [LEGACY_RULE_THRESHOLD] 时，视为 v1 快照，
     * 备份为 [LEGACY_BACKUP_FILE_NAME] 后清空，让内置规则库接管。
     */
    private fun migrateLegacyRulesIfNeeded() {
        val legacy = readRules(customRulesFile)
        if (legacy.size < LEGACY_RULE_THRESHOLD) return
        // v1 内置规则的 ID 全部以 "_splash" 结尾；用户自定义规则不会整份满足该特征
        if (!legacy.all { it.id.endsWith(LEGACY_RULE_ID_SUFFIX) }) return
        try {
            val backup = File(context.filesDir, LEGACY_BACKUP_FILE_NAME)
            customRulesFile.copyTo(backup, overwrite = true)
            customRulesFile.delete()
            Log.i(TAG, "已迁移 v1 规则文件（" + legacy.size + " 条内置规则快照）→ 备份 " + LEGACY_BACKUP_FILE_NAME)
        } catch (e: Exception) {
            Log.w(TAG, "迁移 v1 规则文件失败，保留原文件", e)
        }
    }

    private fun readRules(file: File): List<SkipRule> {
        if (!file.exists()) return emptyList()
        return try {
            parseSkipRules(JSONArray(file.readText()))
        } catch (e: Exception) {
            Log.e(TAG, "读取规则文件失败: " + file.name, e)
            emptyList()
        }
    }

    private fun saveRules(file: File, rules: List<SkipRule>) {
        try {
            val array = JSONArray()
            rules.forEach { array.put(it.toJson()) }
            file.writeText(array.toString(2))
        } catch (e: Exception) {
            Log.e(TAG, "保存规则文件失败: " + file.name, e)
        }
    }

    /** 订阅元信息 */
    data class SubscriptionMeta(
        val url: String,
        val version: Long,
        val ruleCount: Int,
        val updatedAt: Long
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("url", url)
            put("version", version)
            put("ruleCount", ruleCount)
            put("updatedAt", updatedAt)
        }
    }

    private companion object {
        const val TAG = "SkipRuleManager"
        const val PREFS_NAME = "skip_rule_prefs"
        const val CUSTOM_RULES_FILE_NAME = "skip_rules.json"
        const val SUBSCRIPTION_FILE_NAME = "skip_subscription.json"

        /** v1 规则文件备份名（迁移时生成） */
        const val LEGACY_BACKUP_FILE_NAME = "skip_rules_v1_backup.json"

        /** v1 内置规则 ID 后缀（用于识别 v1 规则文件快照） */
        const val LEGACY_RULE_ID_SUFFIX = "_splash"

        /** 判定"v1 内置规则快照"的最小规则数 */
        const val LEGACY_RULE_THRESHOLD = 20
        const val KEY_DISABLED_IDS = "disabled_ids"
        const val KEY_BUILT_IN_VERSION = "built_in_version"
        const val KEY_SUBSCRIPTION_META = "subscription_meta"
    }
}
