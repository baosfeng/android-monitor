package com.bsfeng.monitor.finance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 自动记账「刚落库」的一次性信号（纯 Kotlin，无 Android 依赖，可 JVM 单测）。
 *
 * 记账列表靠它做「顶部新行高亮 1.6s」：只有自动记账落库（[mark]）会点亮，
 * 用户手写记录、切 Tab / 回前台重新加载都不会触发 —— 高亮是「刚刚多了这笔」的提示，
 * 不是「列表刷新过」的提示。消费方取走后调用 [clear]，同一笔只亮一次。
 */
object FinanceInsertSignal {

    private val _latestId = MutableStateFlow<Long?>(null)

    /** 最近一次自动记账落库的交易 id；null = 没有待消费的新行 */
    val latestId: StateFlow<Long?> = _latestId.asStateFlow()

    /** 自动记账落库成功后调用：带上新行 id，等待列表消费 */
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
