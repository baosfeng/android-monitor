package com.bsfeng.monitor.core.di

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
 * 手动 DI 容器 — 取代 MonitorApp 中分散的 getter。
 *
 * 所有依赖通过懒加载创建，按需初始化。
 * 后续可逐步将构造器注入推广到各组件。
 */
class ServiceContainer(private val app: Application) {

    // ========== 基础设施 ==========

    val databaseHelper: DatabaseHelper by lazy { DatabaseHelper.getInstance(app) }

    val eventReporter: EventReporter by lazy { EventReporter(app) }

    val uploadQueue: UploadQueue by lazy { UploadQueue() }

    // ========== Pipeline ==========

    val pipeline: EventPipeline by lazy { EventPipelineImpl() }

    val navTracker: NavigationContextTracker by lazy { NavigationContextTracker() }

    val collector: RawEventCollector by lazy { RawEventCollector(pipeline, navTracker) }

    // ========== 跳过规则 ==========

    val skipRuleManager: SkipRuleManager by lazy { SkipRuleManager(app) }

    val skipRuleEngine: SkipRuleEngine by lazy { SkipRuleEngine(skipRuleManager) }

    init {
        DebugConfig.init()
        initPipeline()
    }

    private fun initPipeline() {
        pipeline.register(DatabaseHandler(databaseHelper))
        pipeline.register(UploadHandler(uploadQueue, eventReporter))
        pipeline.register(LogHandler())
    }
}

/**
 * 从任意 Context 获取 ServiceContainer 的扩展属性。
 */
val android.content.Context.serviceContainer: ServiceContainer
    get() = (applicationContext as com.bsfeng.monitor.MonitorApp).container
