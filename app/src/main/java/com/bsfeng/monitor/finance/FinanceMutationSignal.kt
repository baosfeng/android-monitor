package com.bsfeng.monitor.finance

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 记账数据的进程内变更信号（纯 Kotlin，无 Android 依赖，便于单测）。
 *
 * 写操作成功后 [bump] 一次，UI 订阅 [version] 重新加载当前浏览月 ——
 * 自动记账（`AutoBookEngine.persist` → [FinanceManager.addTransaction]）走的是同一个
 * 单例 Manager，落库后 UI 不用等用户手动操作就能看到。
 *
 * 口径：
 * - 版本号单调递增，[MutableStateFlow] 对相等值去重，所以同一版本不会重复触发加载；
 * - 并发 bump 用 `compareAndSet` 重试，不丢更新、不倒退（直接赋值会让「先读到 1 后写入」的线程把版本压低）；
 * - 读路径不 bump —— 订阅方「加载 → 再加载」不会自激成循环。
 */
class FinanceMutationSignal {

    private val _version = MutableStateFlow(0L)

    /** 当前数据版本号：每次成功的写操作 +1。 */
    val version: StateFlow<Long> = _version.asStateFlow()

    /**
     * 标记数据已变更，返回新版本号。
     *
     * 非 suspend、不切线程：所有写操作都在 `Dispatchers.IO` 的后台协程里调用，这里只做一次 CAS。
     */
    fun bump(): Long {
        while (true) {
            val current = _version.value
            val next = current + 1
            if (_version.compareAndSet(current, next)) return next
        }
    }

    /** 当前版本号的同步快照（不等 Flow 派发，用于「我这次写入之后有没有别人再写」之类的判定）。 */
    fun current(): Long = _version.value
}
