package com.bsfeng.monitor.skip

import android.content.Context
import androidx.core.content.edit
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

    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val customRulesFile = File(context.filesDir, CUSTOM_RULES_FILE_NAME)
    private val subscriptionsDir = File(context.filesDir, SUBSCRIPTIONS_DIR_NAME)

    /** v2 早期版本的单订阅文件（多订阅迁移用，迁移后删除） */
    private val legacySubscriptionFile = File(context.filesDir, LEGACY_SUBSCRIPTION_FILE_NAME)

    private var builtInRules: List<SkipRule> = emptyList()
    private var subscriptionRules: List<SkipRule> = emptyList()
    private var customRules: List<SkipRule> = emptyList()
    private var disabledIds: MutableSet<String> = mutableSetOf()
    private var subscriptionMetas: MutableList<SubscriptionMeta> = mutableListOf()

    init {
        load()
    }

    // ==================== 加载与保存 ====================

    /** 重新加载全部规则（订阅更新后调用） */
    fun load() {
        migrateLegacyRulesIfNeeded()
        customRules = readRules(customRulesFile)
        subscriptionMetas = readSubscriptionMetas().toMutableList()
        migrateSingleSubscriptionIfNeeded()
        // 内置规则的时间闸门跟随监控窗口（两者必须一致，否则调大窗口不生效）
        builtInRules = BuiltInRules.getRules(SkipConfig.watchWindowMs)
        subscriptionRules = readAllSubscriptionRules()
        disabledIds = (prefs.getStringSet(KEY_DISABLED_IDS, emptySet()) ?: emptySet()).toMutableSet()
        prefs.edit { putInt(KEY_BUILT_IN_VERSION, BuiltInRules.BUILT_IN_VERSION) }
        SelectorCache.clear()
        Log.d(TAG, "规则加载完成: 内置 " + builtInRules.size + " 条, 订阅 " + subscriptionRules.size +
            " 条（" + subscriptionMetas.size + " 个订阅源）, 自定义 " + customRules.size + " 条, 禁用 " + disabledIds.size + " 条")
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
        subscriptionMetas = mutableListOf()
        prefs.edit {
            remove(KEY_DISABLED_IDS)
            remove(KEY_SUBSCRIPTION_METAS)
            remove(LEGACY_KEY_SUBSCRIPTION_META)
        }
        disabledIds = mutableSetOf()
        load()
    }

    // ==================== 订阅（支持多个订阅源） ====================

    /** 全部订阅源（按更新时间排序，未订阅时为空列表） */
    fun subscriptionMetas(): List<SubscriptionMeta> = subscriptionMetas.sortedBy { it.updatedAt }

    /** 指定地址的订阅源 */
    fun subscriptionMeta(url: String): SubscriptionMeta? = subscriptionMetas.find { it.url == url }

    /**
     * 保存某个订阅源的拉取结果。
     *
     * 每个订阅源独立一个文件（[SubscriptionMeta.fileName]）——更新订阅 A 不会覆盖订阅 B 的规则。
     */
    fun saveSubscription(url: String, version: Long, rules: List<SkipRule>) {
        subscriptionsDir.mkdirs()
        val fileName = fileNameOf(url)
        saveRules(File(subscriptionsDir, fileName), rules)
        val meta = SubscriptionMeta(
            url = url,
            version = version,
            ruleCount = rules.size,
            updatedAt = System.currentTimeMillis(),
            fileName = fileName
        )
        subscriptionMetas = (subscriptionMetas.filter { it.url != url } + meta).toMutableList()
        persistSubscriptionMetas()
        subscriptionRules = readAllSubscriptionRules()
        SelectorCache.clear()
        Log.i(TAG, "订阅已更新: " + url + " version=" + version + " 规则=" + rules.size +
            "（共 " + subscriptionMetas.size + " 个订阅源）")
    }

    /**
     * 清空订阅。
     *
     * @param url 指定订阅地址；null 表示清空全部订阅源
     */
    fun clearSubscription(url: String? = null) {
        val targets = if (url == null) subscriptionMetas.toList() else subscriptionMetas.filter { it.url == url }
        targets.forEach { File(subscriptionsDir, it.fileName).delete() }
        subscriptionMetas = subscriptionMetas.filter { meta -> targets.none { it.url == meta.url } }.toMutableList()
        persistSubscriptionMetas()
        subscriptionRules = readAllSubscriptionRules()
        SelectorCache.clear()
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
    internal fun fileNameOf(url: String): String =
        SUBSCRIPTION_FILE_PREFIX + Integer.toHexString(url.hashCode()) + ".json"

    private fun readAllSubscriptionRules(): List<SkipRule> = subscriptionMetas.flatMap { meta ->
        readRules(File(subscriptionsDir, meta.fileName))
    }

    private fun persistSubscriptionMetas() {
        val array = JSONArray()
        subscriptionMetas.forEach { array.put(it.toJson()) }
        prefs.edit { putString(KEY_SUBSCRIPTION_METAS, array.toString()) }
    }

    private fun readSubscriptionMetas(): List<SubscriptionMeta> {
        val text = prefs.getString(KEY_SUBSCRIPTION_METAS, null) ?: return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                val url = json.optString("url")
                if (url.isBlank()) {
                    null
                } else {
                    SubscriptionMeta(
                        url = url,
                        version = json.optLong("version", 0L),
                        ruleCount = json.optInt("ruleCount", 0),
                        updatedAt = json.optLong("updatedAt", 0L),
                        fileName = json.optString("fileName").ifBlank { fileNameOf(url) }
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "读取订阅清单失败", e)
            emptyList()
        }
    }

    /**
     * 单订阅 → 多订阅迁移。
     *
     * v2 早期版本把订阅规则写在 `files/skip_subscription.json`、元信息写在 prefs
     * `subscription_meta`；升级后搬进多订阅目录，用户不必重新添加订阅地址。
     */
    private fun migrateSingleSubscriptionIfNeeded() {
        val legacyMetaText = prefs.getString(LEGACY_KEY_SUBSCRIPTION_META, null)
        if (legacyMetaText == null && !legacySubscriptionFile.exists()) return
        try {
            val url = legacyMetaText?.let { runCatching { JSONObject(it).optString("url") }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
            if (url != null && legacySubscriptionFile.exists() && subscriptionMetas.none { it.url == url }) {
                val legacyMeta = JSONObject(legacyMetaText!!)
                val fileName = fileNameOf(url)
                subscriptionsDir.mkdirs()
                legacySubscriptionFile.copyTo(File(subscriptionsDir, fileName), overwrite = true)
                subscriptionMetas = (subscriptionMetas + SubscriptionMeta(
                    url = url,
                    version = legacyMeta.optLong("version", 0L),
                    ruleCount = legacyMeta.optInt("ruleCount", 0),
                    updatedAt = legacyMeta.optLong("updatedAt", 0L),
                    fileName = fileName
                )).toMutableList()
                persistSubscriptionMetas()
                Log.i(TAG, "已迁移单订阅数据: " + url)
            }
            legacySubscriptionFile.delete()
            prefs.edit { remove(LEGACY_KEY_SUBSCRIPTION_META) }
        } catch (e: Exception) {
            Log.w(TAG, "迁移单订阅数据失败，保留原文件", e)
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
            file.parentFile?.mkdirs()
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
        val updatedAt: Long,
        /** 该订阅规则所在的文件名（位于 `files/subscriptions/`） */
        val fileName: String
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("url", url)
            put("version", version)
            put("ruleCount", ruleCount)
            put("updatedAt", updatedAt)
            put("fileName", fileName)
        }
    }

    private companion object {
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
