package com.bsfeng.monitor

import android.app.Application
import com.bsfeng.monitor.core.di.ServiceContainer

/**
 * 应用入口 — 仅初始化 DI 容器。
 */
class MonitorApp : Application() {

    lateinit var container: ServiceContainer
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = ServiceContainer(this)
    }

    companion object {
        @Volatile
        lateinit var instance: MonitorApp
            private set
    }
}
