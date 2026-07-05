package com.bsfeng.monitor

import android.app.Application

/**
 * 应用入口 — 仅保持最小初始化。
 */
class MonitorApp : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        @Volatile
        lateinit var instance: MonitorApp
            private set
    }
}
