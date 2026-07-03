package com.bsfeng.monitor.service

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType
import com.bsfeng.monitor.event.ScreenClassifier
import com.bsfeng.monitor.util.AppUtils

class MonitorAccessibilityService : AccessibilityService() {

    private var lastPackageName: String? = null
    private var lastActivityClass: String? = null
    private var lastSwitchTimeMs: Long = System.currentTimeMillis()

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        when (event.eventType) {
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> {
                handleWindowChange(event)
            }
            AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED -> {
                handleNotification(event)
            }
        }
    }

    private fun handleWindowChange(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val className = event.className?.toString() ?: ""
        val now = System.currentTimeMillis()

        // 过滤系统界面
        if (isSystemUi(packageName)) return

        val duration = if (lastPackageName != null) now - lastSwitchTimeMs else 0L

        // app_foreground: App 切换
        if (packageName != lastPackageName) {
            EventCollector.emit(EventType.APP_FOREGROUND, mapOf(
                "package_name" to packageName,
                "app_name" to AppUtils.getAppName(packageName),
                "activity_name" to className.substringAfterLast('.'),
                "activity_class" to className,
                "from_package" to (lastPackageName ?: ""),
                "from_activity" to (lastActivityClass ?: ""),
                "foreground_duration_ms" to duration.toString()
            ))
        }

        // activity_change: 同 App 内界面切换
        if (packageName == lastPackageName && className != lastActivityClass) {
            EventCollector.emit(EventType.ACTIVITY_CHANGE, mapOf(
                "package_name" to packageName,
                "app_name" to AppUtils.getAppName(packageName),
                "from_activity" to (lastActivityClass ?: ""),
                "to_activity" to className,
                "to_class" to className
            ))
        }

        // screen_content_detected: 界面内容识别（全应用覆盖）
        try {
            val content = ScreenClassifier.classify(
                packageName, className, event.text, event.source
            )
            if (content != null) {
                EventCollector.emit(EventType.SCREEN_CONTENT_DETECTED, mapOf(
                    "package_name" to packageName,
                    "app_name" to AppUtils.getAppName(packageName),
                    "screen_title" to (content.screenTitle ?: ""),
                    "screen_type" to content.screenType,
                    "activity_class" to className,
                    "confidence" to content.confidence.toString()
                ))
            }
        } catch (_: Exception) {
            // 提取失败不影响主流程
        }

        lastPackageName = packageName
        lastActivityClass = className
        lastSwitchTimeMs = now
    }

    private fun handleNotification(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        val text = event.text?.joinToString("") ?: ""
        val category = extractCategory(event)

        EventCollector.emit(EventType.NOTIFICATION_RECEIVED, mapOf(
            "package_name" to packageName,
            "app_name" to AppUtils.getAppName(packageName),
            "category" to category,
            "content" to text.take(100)  // 最多 100 字
        ))
    }

    private fun extractCategory(event: AccessibilityEvent): String {
        return when (event.packageName?.toString()) {
            "com.tencent.mm" -> "social"
            "com.google.android.gm", "com.android.email" -> "email"
            else -> "other"
        }
    }

    private fun isSystemUi(packageName: String): Boolean {
        return packageName.startsWith("com.android.systemui") ||
                packageName == "com.miui.systemui" ||
                packageName == "android"
    }

    override fun onInterrupt() {
        // 服务被系统中断
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        return false // 不拦截按键
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        // 服务已连接，可以初始化
    }
}
