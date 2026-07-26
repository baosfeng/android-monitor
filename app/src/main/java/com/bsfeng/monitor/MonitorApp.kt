package com.bsfeng.monitor

import android.app.Application
import android.content.Context
import com.bsfeng.monitor.core.di.ServiceContainer
import com.bsfeng.monitor.record.RecordReceiver

/**
 * 应用入口 — 初始化 DI 容器并注册系统广播接收器。
 */
class MonitorApp : Application() {

    lateinit var container: ServiceContainer
        private set

    private lateinit var recordReceiver: RecordReceiver

    override fun onCreate() {
        super.onCreate()
        instance = this
        container = ServiceContainer(this)
        registerRecordReceiver()
    }

    private fun registerRecordReceiver() {
        recordReceiver = RecordReceiver()
        val filters = RecordReceiver.createFilters()
        registerReceiver(recordReceiver, filters[0], Context.RECEIVER_EXPORTED)
        registerReceiver(recordReceiver, filters[1], Context.RECEIVER_EXPORTED)
    }

    companion object {
        @Volatile
        lateinit var instance: MonitorApp
            private set
    }
}
