package com.bsfeng.monitor.ui.quota

import android.content.Context
import androidx.core.content.edit

/**
 * 重置时刻显示策略（原型 s19-reset-all / s19-reset-urgent 两档）。
 *
 * 默认 URGENT：只看快见底与已耗尽的行，首屏就该把「快用完的先被看到」。
 * r5 起这个偏好放在额度设置页「主屏展示」组，不再占额度主屏的首屏高度。
 */
internal enum class QuotaResetMode(val storageValue: String) {
    ALL("all"),
    URGENT("urgent"),
    ;

    companion object {
        fun of(value: String?): QuotaResetMode =
            entries.firstOrNull { it.storageValue == value } ?: URGENT
    }
}

/**
 * 额度主屏的展示偏好（唯一一项：重置时刻策略），由额度设置页写入、额度主屏读取。
 *
 * 单独成文件而不是塞进 QuotaSettings：那份 prefs 是「刷新 / 提醒 / 计费模式」的运维口径，
 * 这一项只影响主屏怎么排版，重置它不该牵连数据层。
 */
internal class QuotaScreenPrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var resetMode: QuotaResetMode
        get() = QuotaResetMode.of(prefs.getString(KEY_RESET_MODE, null))
        set(value) = prefs.edit { putString(KEY_RESET_MODE, value.storageValue) }

    private companion object {
        const val PREFS_NAME = "quota_screen"
        const val KEY_RESET_MODE = "reset_mode"
    }
}

/**
 * 「只显示需处理的行」档下，正常行把重置时刻折叠掉。
 *
 * 原型判据是 CSS（[data-reset=urgent] .acct:not(.is-urgent) .when{display:none}），
 * 抽成纯函数才能把「哪些行会少一行字」直接单测，而不是靠肉眼。
 */
internal fun quotaShowsResetWhen(mode: QuotaResetMode, urgent: Boolean): Boolean =
    mode == QuotaResetMode.ALL || urgent
