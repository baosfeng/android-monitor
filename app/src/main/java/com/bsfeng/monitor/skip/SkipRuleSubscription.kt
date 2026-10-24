package com.bsfeng.monitor.skip

// 来源：原 skip/SkipRuleManager.kt 的订阅部分；职责：多订阅源清单（读写持久化）、订阅文件保存/清除、
// 以及单订阅 → 多订阅的历史数据迁移。

import android.content.SharedPreferences
import androidx.core.content.edit
import android.util.Log
import com.bsfeng.monitor.domain.model.SkipRule
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 多订阅源存储。
 *
 * 规则文件与订阅清单（prefs `subscription_metas`）一一对应：更新/删除某个订阅只动它自己的文件，
 * 不影响其它订阅源。由 [SkipRuleManager] 持有，不对外暴露。
 */
internal class SkipRuleSubscription(
    private val context: android.content.Context,
    private val prefs: SharedPreferences,
    private val subscriptionsDir: File,
    private val legacySubscriptionFile: File
) {

    /** 订阅源清单（内存态；变更后需调用 [persistMetas]） */
    var metas: MutableList<SkipRuleManager.SubscriptionMeta> = mutableListOf()
        private set

    /** 从 prefs 读取清单，并顺带执行单订阅 → 多订阅迁移 */
    fun loadMetas() {
        metas = readMetas().toMutableList()
        migrateSingleSubscriptionIfNeeded()
    }

    /** 清空内存态清单（[SkipRuleManager.resetToBuiltIn] 使用） */
    fun resetMetas() {
        metas = mutableListOf()
    }

    /** 读取全部订阅源规则（按清单顺序拼接） */
    fun readAllRules(): List<SkipRule> = metas.flatMap { meta ->
        SkipRuleStorage.readRules(File(subscriptionsDir, meta.fileName))
    }

    /**
     * 保存某个订阅源的拉取结果。
     *
     * 每个订阅源独立一个文件（[SkipRuleManager.SubscriptionMeta.fileName]）——更新订阅 A 不会覆盖订阅 B 的规则。
     */
    fun save(url: String, version: Long, rules: List<SkipRule>) {
        subscriptionsDir.mkdirs()
        val fileName = fileNameOf(url)
        SkipRuleStorage.saveRules(File(subscriptionsDir, fileName), rules)
        val meta = SkipRuleManager.SubscriptionMeta(
            url = url,
            version = version,
            ruleCount = rules.size,
            updatedAt = System.currentTimeMillis(),
            fileName = fileName
        )
        metas = (metas.filter { it.url != url } + meta).toMutableList()
        persistMetas()
        SelectorCache.clear()
    }

    /**
     * 清空订阅。
     *
     * @param url 指定订阅地址；null 表示清空全部订阅源
     */
    fun clear(url: String?) {
        val targets = if (url == null) metas.toList() else metas.filter { it.url == url }
        targets.forEach { File(subscriptionsDir, it.fileName).delete() }
        metas = metas.filter { meta -> targets.none { it.url == meta.url } }.toMutableList()
        persistMetas()
        SelectorCache.clear()
    }

    private fun persistMetas() {
        val array = JSONArray()
        metas.forEach { array.put(it.toJson()) }
        prefs.edit { putString(SkipRuleManager.KEY_SUBSCRIPTION_METAS, array.toString()) }
    }

    private fun readMetas(): List<SkipRuleManager.SubscriptionMeta> {
        val text = prefs.getString(SkipRuleManager.KEY_SUBSCRIPTION_METAS, null) ?: return emptyList()
        return try {
            val array = JSONArray(text)
            (0 until array.length()).mapNotNull { index ->
                val json = array.optJSONObject(index) ?: return@mapNotNull null
                val url = json.optString("url")
                if (url.isBlank()) {
                    null
                } else {
                    SkipRuleManager.SubscriptionMeta(
                        url = url,
                        version = json.optLong("version", 0L),
                        ruleCount = json.optInt("ruleCount", 0),
                        updatedAt = json.optLong("updatedAt", 0L),
                        fileName = json.optString("fileName").ifBlank { fileNameOf(url) }
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(SkipRuleManager.TAG, "读取订阅清单失败", e)
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
        val legacyMetaText = prefs.getString(SkipRuleManager.LEGACY_KEY_SUBSCRIPTION_META, null)
        if (legacyMetaText == null && !legacySubscriptionFile.exists()) return
        try {
            val url = legacyMetaText?.let { runCatching { JSONObject(it).optString("url") }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
            if (url != null && legacySubscriptionFile.exists() && metas.none { it.url == url }) {
                val legacyMeta = JSONObject(legacyMetaText!!)
                val fileName = fileNameOf(url)
                subscriptionsDir.mkdirs()
                legacySubscriptionFile.copyTo(File(subscriptionsDir, fileName), overwrite = true)
                metas = (metas + SkipRuleManager.SubscriptionMeta(
                    url = url,
                    version = legacyMeta.optLong("version", 0L),
                    ruleCount = legacyMeta.optInt("ruleCount", 0),
                    updatedAt = legacyMeta.optLong("updatedAt", 0L),
                    fileName = fileName
                )).toMutableList()
                persistMetas()
                Log.i(SkipRuleManager.TAG, "已迁移单订阅数据: " + url)
            }
            legacySubscriptionFile.delete()
            prefs.edit { remove(SkipRuleManager.LEGACY_KEY_SUBSCRIPTION_META) }
        } catch (e: Exception) {
            Log.w(SkipRuleManager.TAG, "迁移单订阅数据失败，保留原文件", e)
        }
    }

    companion object {
        /**
         * 订阅地址 → 稳定的文件名。
         *
         * 同一地址总是映射到同一文件，便于"更新"直接覆盖；不同地址不会互相覆盖。
         */
        fun fileNameOf(url: String): String =
            SkipRuleManager.SUBSCRIPTION_FILE_PREFIX + Integer.toHexString(url.hashCode()) + ".json"
    }
}
