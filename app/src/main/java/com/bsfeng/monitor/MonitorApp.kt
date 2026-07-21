package com.bsfeng.monitor

import android.app.Application
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.network.EventReporter
import com.bsfeng.monitor.pipeline.EventPipeline
import com.bsfeng.monitor.pipeline.EventPipelineImpl
import com.bsfeng.monitor.pipeline.NavigationContextTracker
import com.bsfeng.monitor.pipeline.RawEventCollector
import com.bsfeng.monitor.pipeline.handler.DatabaseHandler
import com.bsfeng.monitor.pipeline.handler.LogHandler
import com.bsfeng.monitor.pipeline.handler.UploadHandler
import com.bsfeng.monitor.pipeline.handler.UploadQueue
import com.bsfeng.monitor.skip.SkipRuleEngine
import com.bsfeng.monitor.skip.SkipRuleManager
import com.bsfeng.monitor.util.DebugConfig

/**
 * 应用入口 — 仅保持最小初始化。
 */
class MonitorApp : Application() {

    private lateinit var pipeline: EventPipeline
    private lateinit var collector: RawEventCollector
    private lateinit var navTracker: NavigationContextTracker

    private var skipRuleManager: SkipRuleManager? = null
    private var skipRuleEngine: SkipRuleEngine? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        DebugConfig.init()
        skipRuleManager = SkipRuleManager(this)
        skipRuleEngine = SkipRuleEngine(skipRuleManager!!)
        initPipeline()
    }

    fun initPipeline() {
        val db = DatabaseHelper.getInstance(this)
        val reporter = EventReporter(this)
        val uploadQueue = UploadQueue()

        navTracker = NavigationContextTracker()
        pipeline = EventPipelineImpl()

        pipeline.register(DatabaseHandler(db))
        pipeline.register(UploadHandler(uploadQueue, reporter))
        pipeline.register(LogHandler())

        collector = RawEventCollector(pipeline, navTracker)
    }

    fun getCollector(): RawEventCollector = collector
    fun getNavTracker(): NavigationContextTracker = navTracker
    fun getSkipRuleManager(): SkipRuleManager = skipRuleManager!!
    fun getSkipRuleEngine(): SkipRuleEngine = skipRuleEngine!!

    companion object {
        @Volatile
        lateinit var instance: MonitorApp
            private set
    }
}
