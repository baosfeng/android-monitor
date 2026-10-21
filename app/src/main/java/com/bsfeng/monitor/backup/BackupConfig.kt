package com.bsfeng.monitor.backup

import android.content.Context

/**
 * 自动备份频率。
 *
 * 默认 [WEEKLY]：跨签名重装 / 换机这类事故按月计，每周一份足够覆盖，又不至于把
 * 5 份配额一周内轮空（每天一份时，「上周改错账目想回滚」就可能已被淘汰）。
 */
enum class BackupFrequency(val value: String, val label: String, val intervalMs: Long) {
    OFF("off", "关闭", 0L),
    DAILY("daily", "每天", 24L * 60L * 60L * 1000L),
    WEEKLY("weekly", "每周", 7L * 24L * 60L * 60L * 1000L),
    ;

    companion object {
        fun fromValue(raw: String?): BackupFrequency =
            values().firstOrNull { it.value == raw } ?: WEEKLY
    }
}

/** 最近一次成功备份落点：公共 Downloads 路径（展示用）+ 本地快速列表里的文件名。 */
data class LastBackup(
    val at: Long,
    val publicPath: String?,
    val localName: String?,
)

/** 备份配置（SharedPreferences `backup_prefs`）：频率与最近一次备份信息。 */
class BackupConfig(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun frequency(): BackupFrequency =
        BackupFrequency.fromValue(prefs.getString(KEY_FREQUENCY, null))

    fun setFrequency(frequency: BackupFrequency) {
        prefs.edit().putString(KEY_FREQUENCY, frequency.value).apply()
    }

    fun lastBackup(): LastBackup? {
        val at = prefs.getLong(KEY_LAST_AT, 0L)
        if (at <= 0L) return null
        return LastBackup(
            at = at,
            publicPath = prefs.getString(KEY_LAST_PUBLIC_PATH, null),
            localName = prefs.getString(KEY_LAST_LOCAL_NAME, null),
        )
    }

    fun recordBackup(at: Long, publicPath: String?, localName: String?) {
        prefs.edit()
            .putLong(KEY_LAST_AT, at)
            .putString(KEY_LAST_PUBLIC_PATH, publicPath)
            .putString(KEY_LAST_LOCAL_NAME, localName)
            .apply()
    }

    companion object {
        const val PREFS_NAME = "backup_prefs"
        private const val KEY_FREQUENCY = "frequency"
        private const val KEY_LAST_AT = "last_backup_at"
        private const val KEY_LAST_PUBLIC_PATH = "last_backup_public_path"
        private const val KEY_LAST_LOCAL_NAME = "last_backup_local_name"
    }
}
