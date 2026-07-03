package com.bsfeng.monitor

import android.app.Application
import com.bsfeng.monitor.event.EventCollector
import com.bsfeng.monitor.event.EventType
import java.io.StringWriter
import java.io.PrintWriter

class MonitorApp : Application() {
    override fun onCreate() {
        super.onCreate()
        instance = this
        installCrashHandler()
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
