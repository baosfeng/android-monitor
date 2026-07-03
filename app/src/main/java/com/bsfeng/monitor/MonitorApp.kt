package com.bsfeng.monitor

import android.app.Application
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType
import com.bsfeng.monitor.network.EventReporter
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

    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()
        startPeriodicUpload()
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

    companion object {
        @Volatile
        lateinit var instance: MonitorApp
            private set
    }
}
