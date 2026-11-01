package com.bsfeng.monitor.totp

import android.app.Activity
import android.view.WindowManager

/**
 * 防截屏 / 防录屏 —— 唯一碰 FLAG_SECURE 的地方。
 *
 * 加 flag 后系统截图、录屏、最近任务缩略图都是空白。
 * 开关由 TotpSettings.screenshotProtectionEnabled 决定，进入屏时按当前值调用。
 */
object TotpSecureScreen {

    /** 设置（enabled = true）或清除（false）窗口的 FLAG_SECURE。 */
    fun apply(activity: Activity, enabled: Boolean) {
        if (enabled) {
            activity.window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        } else {
            activity.window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
    }

    /** 当前窗口是否已开启防截屏。 */
    fun isEnabled(activity: Activity): Boolean =
        (activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE) != 0
}
