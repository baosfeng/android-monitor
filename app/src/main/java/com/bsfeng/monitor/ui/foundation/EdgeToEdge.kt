package com.bsfeng.monitor.ui.foundation

import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.core.view.WindowCompat

/**
 * 全应用唯一的窗口外观入口：系统栏透明（内容画到系统栏之下）+ 系统栏图标显式定色。
 *
 * 只在这里改窗口外观。页面层负责的是「底衬铺到屏幕边」和「内容避让系统栏」，
 * 两件事分开后，新增页面不需要再碰 `enableEdgeToEdge` / `WindowCompat`。
 *
 * - [lightSystemBarIcons] 默认 true：全应用只有浅色底衬，压深色图标才可辨。
 * - 显式写给 InsetsController 是双保险：不同 ROM 对系统栏图标的明暗自动推断不一致。
 */
object MonitorEdgeToEdge {

    fun apply(activity: ComponentActivity, lightSystemBarIcons: Boolean = true) {
        val statusBarStyle = if (lightSystemBarIcons) {
            SystemBarStyle.light(ANDROID_TRANSPARENT, ANDROID_TRANSPARENT)
        } else {
            SystemBarStyle.dark(ANDROID_TRANSPARENT)
        }
        val navigationBarStyle = if (lightSystemBarIcons) {
            SystemBarStyle.light(ANDROID_TRANSPARENT, ANDROID_TRANSPARENT)
        } else {
            SystemBarStyle.dark(ANDROID_TRANSPARENT)
        }
        activity.enableEdgeToEdge(
            statusBarStyle = statusBarStyle,
            navigationBarStyle = navigationBarStyle,
        )
        WindowCompat.getInsetsController(
            activity.window,
            activity.window.decorView,
        ).apply {
            isAppearanceLightStatusBars = lightSystemBarIcons
            isAppearanceLightNavigationBars = lightSystemBarIcons
        }
    }

    private const val ANDROID_TRANSPARENT = android.graphics.Color.TRANSPARENT
}
