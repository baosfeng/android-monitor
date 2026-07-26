package com.bsfeng.monitor.record

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import com.bsfeng.monitor.util.AppUtils
import java.util.UUID

/**
 * App 使用时长跟踪器。
 *
 * 记录每次 App 切换的开/关时间，计算使用时长。
 * 息屏时关闭当前 App 会话，亮屏后等待用户切到 App 再开始计时。
 */
class UsageTracker(
    private val onRecord: (Record) -> Unit
) {
    private var currentPkg: String? = null
    private var currentAppName: String? = null
    private var sessionStart: Long = 0L
    @Suppress("unused")
    private var screenOn: Boolean = true

    /**
     * 设置记录回调（用于解决与 RecordCollector 的循环依赖）。
     * 构造时传空 lambda，构造完成后调用此方法设置实际回调。
     */
    private var recordCallback: ((Record) -> Unit)? = null
    fun setOnRecordCallback(callback: (Record) -> Unit) {
        recordCallback = callback
    }

    fun onAppSwitch(packageName: String, appName: String, timestamp: Long) {
        // 关闭上一个 App
        closeCurrentSession(timestamp)
        // 开启新 App
        currentPkg = packageName
        currentAppName = appName
        sessionStart = timestamp
        onRecord(Record(
            id = UUID.randomUUID().toString(),
            type = RecordType.APP_USAGE,
            action = "OPENED",
            timestamp = timestamp,
            label = appName,
            detail = mapOf("app_name" to appName, "package_name" to packageName, "app_label" to AppUtils.getAppName(packageName))
        ))
    }

    fun onScreenOff(timestamp: Long) {
        screenOn = false
        closeCurrentSession(timestamp)
    }

    fun onScreenOn(timestamp: Long) {
        screenOn = true
        // 亮屏后不立即开始计时，等待用户切到具体 App
    }

    private fun closeCurrentSession(timestamp: Long) {
        val pkg = currentPkg ?: return
        val appName = currentAppName ?: return
        val duration = timestamp - sessionStart
        if (duration > 1000) { // 过滤 <1s 的闪切
            onRecord(Record(
                id = UUID.randomUUID().toString(),
                type = RecordType.APP_USAGE,
                action = "CLOSED",
                timestamp = timestamp,
                duration = duration,
                label = appName,
                detail = mapOf(
                    "app_name" to appName,
                    "package_name" to pkg,
                    "duration_ms" to duration.toString()
                )
            ))
        }
        currentPkg = null
        currentAppName = null
    }
}
