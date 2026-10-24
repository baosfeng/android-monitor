package com.bsfeng.monitor.skip

// 来源：原 skip/SkipRuleManager.kt 的文件读写与迁移部分；职责：规则 JSON 文件读写与 v1 内置规则快照迁移。

import android.util.Log
import com.bsfeng.monitor.domain.model.SkipRule
import org.json.JSONArray
import java.io.File

/** 规则文件读写与历史数据迁移（无状态工具，由 [SkipRuleManager] 调用） */
internal object SkipRuleStorage {

    /** 读取规则文件；文件不存在返回空列表，内容损坏时记日志并返回空列表 */
    fun readRules(file: File): List<SkipRule> {
        if (!file.exists()) return emptyList()
        return try {
            parseSkipRules(JSONArray(file.readText()))
        } catch (e: Exception) {
            Log.e(SkipRuleManager.TAG, "读取规则文件失败: " + file.name, e)
            emptyList()
        }
    }

    /** 写入规则文件（缩进 2 空格，便于人工排查）；写失败只记日志，不影响内存态 */
    fun saveRules(file: File, rules: List<SkipRule>) {
        try {
            file.parentFile?.mkdirs()
            val array = JSONArray()
            rules.forEach { array.put(it.toJson()) }
            file.writeText(array.toString(2))
        } catch (e: Exception) {
            Log.e(SkipRuleManager.TAG, "保存规则文件失败: " + file.name, e)
        }
    }

    /**
     * v1 → v2 迁移：v1 会把**整份内置规则快照**写进 `files/skip_rules.json`，
     * 升级后这些规则会被当成"用户自定义规则"加载（数量大且选择器陈旧）。
     *
     * 判定：该文件规则数 ≥ [SkipRuleManager.LEGACY_RULE_THRESHOLD] 且 ID 全部以
     * [SkipRuleManager.LEGACY_RULE_ID_SUFFIX] 结尾时，视为 v1 快照，备份为
     * [SkipRuleManager.LEGACY_BACKUP_FILE_NAME] 后清空，让内置规则库接管。
     *
     * @param customRulesFile 自定义规则文件
     * @param filesDir 应用私有目录（备份文件落在这里）
     */
    fun migrateLegacyRulesIfNeeded(customRulesFile: File, filesDir: File) {
        val legacy = readRules(customRulesFile)
        if (legacy.size < SkipRuleManager.LEGACY_RULE_THRESHOLD) return
        // v1 内置规则的 ID 全部以 "_splash" 结尾；用户自定义规则不会整份满足该特征
        if (!legacy.all { it.id.endsWith(SkipRuleManager.LEGACY_RULE_ID_SUFFIX) }) return
        try {
            val backup = File(filesDir, SkipRuleManager.LEGACY_BACKUP_FILE_NAME)
            customRulesFile.copyTo(backup, overwrite = true)
            customRulesFile.delete()
            Log.i(SkipRuleManager.TAG, "已迁移 v1 规则文件（" + legacy.size + " 条内置规则快照）→ 备份 " +
                SkipRuleManager.LEGACY_BACKUP_FILE_NAME)
        } catch (e: Exception) {
            Log.w(SkipRuleManager.TAG, "迁移 v1 规则文件失败，保留原文件", e)
        }
    }
}
