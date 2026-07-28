package com.bsfeng.monitor.core.di

import android.app.Application
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.data.repository.DeviceRepositoryImpl
import com.bsfeng.monitor.data.repository.SkipRuleRepositoryImpl
import com.bsfeng.monitor.domain.repository.DeviceRepository
import com.bsfeng.monitor.domain.repository.SkipRuleRepository
import com.bsfeng.monitor.network.EventReporter
import com.bsfeng.monitor.record.RecordCollector
import com.bsfeng.monitor.record.RecordHandlerRegistry
import com.bsfeng.monitor.record.RecordStore
import com.bsfeng.monitor.record.UsageTracker
import com.bsfeng.monitor.skip.SkipRuleEngine
import com.bsfeng.monitor.skip.SkipRuleManager
import com.bsfeng.monitor.util.DebugConfig

/**
 * 手动 DI 容器。
 *
 * 所有依赖通过懒加载创建，按需初始化。
 */
class ServiceContainer(private val app: Application) {

    // ========== 基础设施 ==========

    val databaseHelper: DatabaseHelper by lazy { DatabaseHelper.getInstance(app) }

    val eventReporter: EventReporter by lazy { EventReporter(app) }

    // ========== Record 系统 ==========

    val recordHandlerRegistry: RecordHandlerRegistry by lazy { RecordHandlerRegistry.createDefault() }

    val recordStore: RecordStore by lazy { RecordStore(databaseHelper) }

    /**
     * UsageTracker 构造时传空 lambda，init 块中通过 setOnRecordCallback 设置实际回调。
     */
    val usageTracker: UsageTracker by lazy { UsageTracker { } }

    val recordCollector: RecordCollector by lazy {
        RecordCollector(recordStore, usageTracker, recordHandlerRegistry)
    }

    // ========== 跳过规则 ==========

    val skipRuleManager: SkipRuleManager by lazy { SkipRuleManager(app) }

    val skipRuleEngine: SkipRuleEngine by lazy { SkipRuleEngine(skipRuleManager) }

    // ========== Repository ==========

    val skipRuleRepository: SkipRuleRepository by lazy { SkipRuleRepositoryImpl(skipRuleManager) }

    val deviceRepository: DeviceRepository by lazy { DeviceRepositoryImpl() }

    init {
        DebugConfig.init()
        // 解决 UsageTracker 与 RecordCollector 的循环依赖：
        // UsageTracker 生成 APP_USAGE 记录后，通过回调写入 RecordStore
        usageTracker.setOnRecordCallback { record ->
            recordStore.insert(record)
        }
    }
}

/**
 * 从任意 Context 获取 ServiceContainer 的扩展属性。
 */
val android.content.Context.serviceContainer: ServiceContainer
    get() = (applicationContext as com.bsfeng.monitor.MonitorApp).container
