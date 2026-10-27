package com.bsfeng.monitor.finance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 交易「刚落库」的一次性信号（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 记账列表靠它做「顶部新行高亮 1.6s」：**自动记账落库与手动保存新交易都会点亮**
 * （契约 s14a-save / s05-save 要的是「刚刚多了这笔」这条反馈，不区分怎么写进来的）。
 * 切 Tab / 回前台重新加载不触发（那是刷新，不是新增），编辑已有交易也不点亮。
 * 消费方取走后调用 [clear]，同一笔只亮一次。
 */
object FinanceInsertSignal {

    private val _latestId = MutableStateFlow<Long?>(null)

    /** 最近一次落库的交易 id；null = 没有待消费的新行 */
    val latestId: StateFlow<Long?> = _latestId.asStateFlow()

    /** 交易落库成功后调用（自动记账 / 手动保存）：带上新行 id，等待列表消费 */
    fun mark(transactionId: Long) {
        if (transactionId <= 0L) return
        _latestId.value = transactionId
    }

    /** 列表已经点亮该行：清空，避免切 Tab / 回前台再亮一次 */
    fun clear() {
        _latestId.value = null
    }

    /** 当前待消费的新行 id（同步快照） */
    fun peek(): Long? = _latestId.value
}
