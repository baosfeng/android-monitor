package com.bsfeng.monitor.core.di

import com.bsfeng.monitor.domain.model.Record
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * APP_USAGE 落库的进程级 IO 通道（把同步阻塞写库从主线程挪走）。
 *
 * 为什么需要：无障碍服务的 1 秒前台轮询在**主线程**触发 [com.bsfeng.monitor.record.UsageTracker]
 * 的回调，而 [com.bsfeng.monitor.record.RecordStore.insertOrMerge] 是同步阻塞的 —— 它还先跑一条
 * 5 分钟窗口的 `label` 范围扫描（`label` 无索引），切 App 瞬间叠上 Binder 卡顿就有 ANR 风险。
 *
 * 两条口径：
 * - **回调本身必须立即返回**：[submit] 只做入队，绝不在调用线程写库；
 * - **顺序必须保持**：切 App 时「结算旧 App → 开新 session」两条记录按提交顺序落库，
 *   因此用 [Dispatchers.IO] 的单并发视图而不是裸线程池（后者可能让后一条先跑）。
 *
 * 单测：`UsageRecordSinkTest`（注入测试调度器断言调用线程不被占用、失败不外抛）。
 */
internal class UsageRecordSink(
    private val write: (Record) -> Unit,
    dispatcher: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1),
    private val onError: (Throwable) -> Unit = {},
) {

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    /** 入队即返回；写库异常只上报，不影响采集链路。 */
    fun submit(record: Record) {
        scope.launch {
            runCatching { write(record) }.onFailure(onError)
        }
    }
}
