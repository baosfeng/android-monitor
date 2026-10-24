package com.bsfeng.monitor.service

// 从 service/MonitorAccessibilityService.kt 拆出：系统界面包名判定（纯函数）。
// 这些包名不应计入「前台 App」、也不参与页面轨迹。

/**
 * 是否属于系统界面 / 输入法 / 搜索框等「不该当成前台 App」的包名。
 *
 * 桌面不在此列：桌面由 [com.bsfeng.monitor.service.MonitorAccessibilityService] 的
 * isHomeApp 单独判定（需要解析 HOME Intent，有缓存状态）。
 */
internal fun isSystemUiPackage(packageName: String): Boolean {
    return packageName.startsWith("com.android.systemui") ||
            packageName == "com.miui.systemui" ||
            packageName == "com.miui.personalassistant" ||
            packageName == "com.miui.securitycenter" ||
            packageName == "com.miui.misound" ||
            packageName == "miui.systemui.plugin" ||
            packageName == "com.sohu.inputmethod.sogou" ||
            packageName == "com.baidu.input" ||
            packageName.startsWith("com.android.inputmethod") ||
            packageName == "com.android.quicksearchbox" ||
            packageName == "android"
}
