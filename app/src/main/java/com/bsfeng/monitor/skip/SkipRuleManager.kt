package com.bsfeng.monitor.skip

// 来源：原 skip/SkipRuleManager.kt；职责：规则管理器的状态与公开入口（加载/查询/启停/增删/重置），
// 订阅子系统见 SkipRuleSubscription.kt，文件读写与迁移见 SkipRuleStorage.kt。

import android.content.Context
import androidx.core.content.edit
import android.util.Log
import com.bsfeng.monitor.domain.model.SkipRule
import java.io.File

/**
 * 跳过规则管理器。
 *
 * 规则来源（按匹配优先级从高到低）：
 * 1. **用户自定义规则**：`files/skip_rules.json`（仅存用户新增/修改的规则）
 * 2. **订阅规则**：`files/subscriptions/sub_<hash>.json`，**每个订阅地址一个文件**，
 *    由 [GkdSubscription] 拉取写入（GKD 订阅格式）；可同时添加多个订阅源
 * 3. **内置规则**：见 [BuiltInRules]（全局开屏规则 + 少量应用规则）
 *
 * 启停状态**不复制规则副本**，而是把被禁用的规则 ID 存进 SharedPreferences
 * （`disabled_ids`），这样内置规则升级时不会丢用户设置。
 *
 * 订阅源清单存在 prefs 的 `subscription_metas`（JSON 数组），规则文件与清单一一对应：
 * 更新/删除某个订阅只动它自己的文件，不会影响其它订阅源。
 */
class SkipRuleManager(private val context: Context) {

    internal val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    internal val customRulesFile = File(context.filesDir, CUSTOM_RULES_FILE_NAME)
    internal val subscriptionsDir = File(context.filesDir, SUBSCRIPTIONS_DIR_NAME)

    /** v2 早期版本的单订阅文件（多订阅迁移用，迁移后删除） */
    internal val legacySubscriptionFile = File(context.filesDir, LEGACY_SUBSCRIPTION_FILE_NAME)

    /** 订阅源清单与订阅文件读写 */
    internal val subscription = SkipRuleSubscription(context, prefs, subscriptionsDir, legacySubscriptionFile)

    private var builtInRules: List<SkipRule> = emptyList()
    internal var subscriptionRules: List<SkipRule> = emptyList()
    internal var customRules: List<SkipRule> = emptyList()
    internal var disabledIds: MutableSet<String> = mutableSetOf()

    init {
        load()
    }

    // ==================== 加载与保存 ====================

    /** 重新加载全部规则（订阅更新后调用） */
    fun load() {
        migrateLegacyRulesIfNeeded()
        customRules = readRules(customRulesFile)
        subscription.loadMetas()
        // 内置规则的时间闸门跟随监控窗口（两者必须一致，否则调大窗口不生效）
        builtInRules = BuiltInRules.getRules(SkipConfig.watchWindowMs)
        subscriptionRules = subscription.readAllRules()
        disabledIds = (prefs.getStringSet(KEY_DISABLED_IDS, emptySet()) ?: emptySet()).toMutableSet()
        prefs.edit { putInt(KEY_BUILT_IN_VERSION, BuiltInRules.BUILT_IN_VERSION) }
        SelectorCache.clear()
        Log.d(TAG, "规则加载完成: 内置 " + builtInRules.size + " 条, 订阅 " + subscriptionRules.size +
            " 条（" + subscription.metas.size + " 个订阅源）, 自定义 " + customRules.size + " 条, 禁用 " + disabledIds.size + " 条")
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
        val enabled = getEnabledRules()
        val appRules = enabled.filter { !it.isGlobal && it.app == packageName }
        if (isGloballyExcluded(packageName)) return appRules
        // 全局规则可由订阅用 excludedApps 逐 App 禁用（GKD globalGroups[].apps[{enable:false}]）
        return appRules + enabled.filter { it.isGlobal && packageName !in it.excludedApps }
    }

    /** 全局规则是否对该包名生效（系统 UI/桌面/浏览器等默认排除） */
    fun isGloballyExcluded(packageName: String): Boolean = packageName in BuiltInRules.GLOBAL_EXCLUDED_PACKAGES

    /** 按 ID 查找规则 */
    fun getRule(ruleId: String): SkipRule? = getAllRules().find { it.id == ruleId }

    /**
     * 设置监控窗口（毫秒）并重载内置规则的时间闸门。
     *
     * 订阅/自定义规则保留各自声明的 `matchTime`（尊重规则作者意图），只有内置规则跟随该配置。
     */
    fun setWatchWindowMs(ms: Long) {
        SkipConfig.setWatchWindowMs(ms)
        load()
        Log.i(TAG, "监控窗口已更新: " + SkipConfig.watchWindowMs + "ms")
    }

    // ==================== 启停与增删 ====================

    fun setRuleEnabled(ruleId: String, enabled: Boolean) {
        if (enabled) {
            disabledIds.remove(ruleId)
        } else {
            disabledIds.add(ruleId)
        }
        prefs.edit { putStringSet(KEY_DISABLED_IDS, disabledIds) }
    }

    /** 新增/更新自定义规则 */
    fun addRule(rule: SkipRule) {
        customRules = customRules.filter { it.id != rule.id } + rule
        saveRules(customRulesFile, customRules)
        disabledIds.remove(rule.id)
        prefs.edit { putStringSet(KEY_DISABLED_IDS, disabledIds) }
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

    /** 重置为内置规则：清空自定义规则与全部订阅，恢复全部内置规则启用 */
    fun resetToBuiltIn() {
        customRules = emptyList()
        customRulesFile.delete()
        subscriptionsDir.listFiles()?.forEach { it.delete() }
        legacySubscriptionFile.delete()
        subscriptionRules = emptyList()
        subscription.resetMetas()
        prefs.edit {
            remove(KEY_DISABLED_IDS)
            remove(KEY_SUBSCRIPTION_METAS)
            remove(LEGACY_KEY_SUBSCRIPTION_META)
        }
        disabledIds = mutableSetOf()
        load()
    }

    // ==================== 订阅（委托 SkipRuleSubscription） ====================

    /** 全部订阅源（按更新时间排序，未订阅时为空列表） */
    fun subscriptionMetas(): List<SubscriptionMeta> = subscription.metas.sortedBy { it.updatedAt }

    /** 指定地址的订阅源 */
    fun subscriptionMeta(url: String): SubscriptionMeta? = subscription.metas.find { it.url == url }

    /**
     * 保存某个订阅源的拉取结果。
     *
     * 每个订阅源独立一个文件（[SubscriptionMeta.fileName]）——更新订阅 A 不会覆盖订阅 B 的规则。
     */
    fun saveSubscription(url: String, version: Long, rules: List<SkipRule>) {
        subscription.save(url, version, rules)
        subscriptionRules = subscription.readAllRules()
        Log.i(TAG, "订阅已更新: " + url + " version=" + version + " 规则=" + rules.size +
            "（共 " + subscription.metas.size + " 个订阅源）")
    }

    /**
     * 清空订阅。
     *
     * @param url 指定订阅地址；null 表示清空全部订阅源
     */
    fun clearSubscription(url: String? = null) {
        subscription.clear(url)
        subscriptionRules = subscription.readAllRules()
        Log.i(TAG, if (url == null) "已清空全部订阅" else "已清除订阅: " + url)
    }

    /** 上次订阅自动检查时间（毫秒；0 表示从未检查） */
    fun lastSubscriptionCheckAt(): Long = prefs.getLong(KEY_SUBSCRIPTION_CHECK_AT, 0L)

    /** 记录订阅自动检查时间 */
    fun markSubscriptionChecked(atMs: Long) {
        prefs.edit { putLong(KEY_SUBSCRIPTION_CHECK_AT, atMs) }
    }

    /**
     * 订阅地址 → 稳定的文件名。
     *
     * 同一地址总是映射到同一文件，便于"更新"直接覆盖；不同地址不会互相覆盖。
     */
    internal fun fileNameOf(url: String): String = SkipRuleSubscription.fileNameOf(url)

    // ==================== 文件读写（委托 SkipRuleStorage） ====================

    internal fun readRules(file: File): List<SkipRule> = SkipRuleStorage.readRules(file)

    internal fun saveRules(file: File, rules: List<SkipRule>) = SkipRuleStorage.saveRules(file, rules)

    private fun migrateLegacyRulesIfNeeded() =
        SkipRuleStorage.migrateLegacyRulesIfNeeded(customRulesFile, context.filesDir)

    /** 订阅元信息 */
    data class SubscriptionMeta(
        val url: String,
        val version: Long,
        val ruleCount: Int,
        val updatedAt: Long,
        /** 该订阅规则所在的文件名（位于 `files/subscriptions/`） */
        val fileName: String
    ) {
        fun toJson(): org.json.JSONObject = org.json.JSONObject().apply {
            put("url", url)
            put("version", version)
            put("ruleCount", ruleCount)
            put("updatedAt", updatedAt)
            put("fileName", fileName)
        }
    }

    internal companion object {
        const val TAG = "SkipRuleManager"
        const val PREFS_NAME = "skip_rule_prefs"
        const val CUSTOM_RULES_FILE_NAME = "skip_rules.json"

        /** 多订阅存放目录 */
        const val SUBSCRIPTIONS_DIR_NAME = "subscriptions"

        /** 订阅规则文件名前缀 */
        const val SUBSCRIPTION_FILE_PREFIX = "sub_"

        /** v2 早期版本的单订阅文件名（迁移用） */
        const val LEGACY_SUBSCRIPTION_FILE_NAME = "skip_subscription.json"

        /** v1 规则文件备份名（迁移时生成） */
        const val LEGACY_BACKUP_FILE_NAME = "skip_rules_v1_backup.json"

        /** v1 内置规则 ID 后缀（用于识别 v1 规则文件快照） */
        const val LEGACY_RULE_ID_SUFFIX = "_splash"

        /** 判定"v1 内置规则快照"的最小规则数 */
        const val LEGACY_RULE_THRESHOLD = 20
        const val KEY_DISABLED_IDS = "disabled_ids"
        const val KEY_BUILT_IN_VERSION = "built_in_version"

        /** 订阅源清单（JSON 数组） */
        const val KEY_SUBSCRIPTION_METAS = "subscription_metas"

        /** 单订阅时代的元信息键（迁移用） */
        const val LEGACY_KEY_SUBSCRIPTION_META = "subscription_meta"
        const val KEY_SUBSCRIPTION_CHECK_AT = "subscription_check_at"
    }
}
