package com.bsfeng.monitor

import android.app.Application
import android.app.ActivityManager
import android.content.Context
import android.os.Build
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType
import com.bsfeng.monitor.network.EventReporter
import com.bsfeng.monitor.service.scheduleAlarmCheck
import com.bsfeng.monitor.util.AppKillDetector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.StringWriter
import java.io.PrintWriter

class MonitorApp : Application() {
    private val backgroundScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 应用被杀死检测器（API 30+ 兜底轮询） */
    val killDetector = AppKillDetector(this)

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()
        checkSelfKill()
        // 注册 AlarmManager 保活定时器，进程被系统回收后可自动唤醒
        scheduleAlarmCheck(this)
        startPeriodicUpload()
        startKillDetection()
    }

    private fun startKillDetection() {
        backgroundScope.launch {
            while (isActive) {
                delay(30_000L)  // 每 30 秒检测一次
                try {
                    killDetector.detect()
                    killDetector.trimReportedKeys()
                } catch (_: Exception) {
                    // 单次检测失败不影响下次循环
                }
            }
        }
    }

    private fun startPeriodicUpload() {
        backgroundScope.launch {
            val reporter = EventReporter(this@MonitorApp)
            while (isActive) {
                delay(AppConfig.uploadIntervalMs.toLong())
                try {
                    reporter.uploadBatch()
                } catch (_: Exception) {
                    // 单次上传失败不影响下次循环
                }
            }
        }
    }

    private fun installCrashHandler() {
        val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                val sw = StringWriter()
                val pw = PrintWriter(sw)
                throwable.printStackTrace(pw)
                pw.flush()
                EventCollector.emit(EventType.APP_CRASHED, mapOf(
                    "thread_name" to thread.name,
                    "exception" to (throwable.javaClass.name ?: ""),
                    "message" to (throwable.message ?: ""),
                    "stack_trace" to sw.toString().take(500)
                ))
            } catch (_: Exception) {
                // 崩溃处理本身出错，不影响原始流程
            }
            defaultHandler?.uncaughtException(thread, throwable)
        }
    }

    /**
     * 应用重新启动时检查自己上次是否被强制杀死。
     * 如果发现是被用户强制停止（REASON_USER_REQUESTED），
     * 补发 APP_KILLED 事件并立即触发一次上报，
     * 确保此类事件能到达服务端。
     */
    private fun checkSelfKill() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        backgroundScope.launch {
            try {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
                val reasons = am.getHistoricalProcessExitReasons(packageName, 0, 1)
                for (reason in reasons) {
                    if (reason.reason == 8) {  // REASON_USER_REQUESTED
                        val killTime = reason.timestamp
                        val summary = "行为记录 上次被用户强制停止"
                        EventCollector.emit(EventType.APP_KILLED, mapOf(
                            "package_name" to packageName,
                            "app_name" to "行为记录",
                            "exit_reason" to "被用户强制停止",
                            "exit_reason_code" to "8",
                            "kill_timestamp" to killTime.toString()
                        ), summary = summary)

                        // 等待事件写入数据库，然后立即上报
                        delay(1000L)
                        EventReporter(this@MonitorApp).uploadBatch()
                    }
                }
            } catch (_: Exception) {
                // 启动时检查失败不影响正常启动
            }
        }
    }

    companion object {
        @Volatile
        lateinit var instance: MonitorApp
            private set
    }
}
