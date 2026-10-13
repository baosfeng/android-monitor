package com.bsfeng.monitor.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import android.view.accessibility.AccessibilityManager

/**
 * 无障碍服务启用状态判定。
 *
 * ## 为什么需要三重判定
 * 真机（小米 14 / HyperOS，2026-10-13 实测）出现「`dumpsys accessibility` 显示服务已绑定、
 * 引擎日志正常，但设置页显示 ❌ 未开启」：
 * - 只看 [AccessibilityManager.getEnabledAccessibilityServiceList]：该列表在部分 ROM 上会滞后
 *   （服务刚绑定或系统 UI 重建期间返回空），导致误报"未开启"；
 * - 只看 [Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES]：反映的是用户勾选状态，
 *   与服务进程是否真的连上没有必然关系（本次 install -r 后就有短暂不一致）。
 *
 * 因此：**用户已勾选（设置项/列表任一命中）或 服务实例已连接**，都算"已开启"。
 */
object AccessibilityStatus {

    /**
     * 无障碍服务类名（与 `AndroidManifest.xml` 中 service 声明一致）。
     */
    internal const val SERVICE_CLASS = "com.bsfeng.monitor.service.MonitorAccessibilityService"

    /**
     * 无障碍服务是否已开启。
     *
     * @param context 任意 Context
     * @param serviceRunning [com.bsfeng.monitor.service.MonitorAccessibilityService.isRunning]
     */
    fun isEnabled(context: Context, serviceRunning: Boolean): Boolean =
        serviceRunning || isEnabledInSettings(context) || isEnabledInManager(context)

    /**
     * 系统设置里是否勾选了本服务（权威来源，`Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`）。
     *
     * 该设置项的格式为 `包名/类名:包名/类名`，类名可能是相对写法（`.service.Xxx`）。
     */
    internal fun isEnabledInSettings(context: Context): Boolean = try {
        val flatten = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        )
        flatten?.split(':')?.any { entry ->
            // ComponentName.unflattenFromString 在类名为相对写法时也能解析出完整类名
            ComponentName.unflattenFromString(entry.trim())?.let { matches(it, context.packageName) } == true
        } == true
    } catch (_: Exception) {
        false
    }

    /**
     * [AccessibilityManager] 报告的服务列表里是否有本服务。
     *
     * 用 [AccessibilityServiceInfo.FEEDBACK_ALL_MASK] 而不是 `FEEDBACK_GENERIC`：
     * 后者会把反馈类型声明不一致的服务过滤掉（本项目声明的是 feedbackGeneric，但仍按全类型取，避免 ROM 差异）。
     */
    internal fun isEnabledInManager(context: Context): Boolean = try {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        manager?.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            ?.any { it.resolveInfo?.serviceInfo?.packageName == context.packageName } == true
    } catch (_: Exception) {
        false
    }

    /** 组件是否指向本应用的无障碍服务（兼容类名的相对写法） */
    private fun matches(component: ComponentName, packageName: String): Boolean {
        if (component.packageName != packageName) return false
        val className = component.className.removePrefix(".")
        return className == SERVICE_CLASS || SERVICE_CLASS.endsWith(".$className")
    }
}
