package com.bsfeng.monitor.totp

import android.content.Context
import androidx.core.content.edit

/**
 * 验证器安全开关 —— 三项默认全开（用户确认的安全默认：只留开关，不放默认）。
 *
 * 单独一类是为了让设置页、列表页、组装层读同一份口径，不在界面里写字面量默认值。
 */
class TotpSettings(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** 防截屏：给窗口加 FLAG_SECURE（验证码页生效）。 */
    var screenshotProtectionEnabled: Boolean
        get() = prefs.getBoolean(KEY_SCREENSHOT_PROTECTION, true)
        set(value) = prefs.edit { putBoolean(KEY_SCREENSHOT_PROTECTION, value) }

    /** 复制即焚：复制验证码后 30 秒自动清空剪贴板。 */
    var clipboardAutoClearEnabled: Boolean
        get() = prefs.getBoolean(KEY_CLIPBOARD_AUTO_CLEAR, true)
        set(value) = prefs.edit { putBoolean(KEY_CLIPBOARD_AUTO_CLEAR, value) }

    /** 后台遮罩：App 切后台时遮住验证码内容。 */
    var backgroundMaskEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_MASK, true)
        set(value) = prefs.edit { putBoolean(KEY_BACKGROUND_MASK, value) }

    /** 三项全开（复位用）。 */
    fun resetToDefaults() {
        prefs.edit {
            putBoolean(KEY_SCREENSHOT_PROTECTION, true)
            putBoolean(KEY_CLIPBOARD_AUTO_CLEAR, true)
            putBoolean(KEY_BACKGROUND_MASK, true)
        }
    }

    companion object {
        internal const val PREFS_NAME = "totp_settings"
        private const val KEY_SCREENSHOT_PROTECTION = "screenshot_protection"
        private const val KEY_CLIPBOARD_AUTO_CLEAR = "clipboard_auto_clear"
        private const val KEY_BACKGROUND_MASK = "background_mask"

        /** 复制即焚的默认倒计时（毫秒）。 */
        const val CLIPBOARD_CLEAR_MILLIS = 30_000L
    }
}
