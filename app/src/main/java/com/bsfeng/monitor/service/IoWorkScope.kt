package com.bsfeng.monitor.service

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * 有生命周期宿主的 IO 协程容器。
 *
 * 为什么不能现场 new `CoroutineScope(Dispatchers.IO + SupervisorJob())`：那个 scope 没有句柄，
 * 服务 onDestroy / 广播任务收尾后子协程仍在写库、写备份，无法取消；AlarmReceiver 还会
 * 每次心跳都新建一个。宿主持有本类，在 onDestroy 或 goAsync 的 finally 里 [cancel]。
 */
internal class IoWorkScope(dispatcher: CoroutineDispatcher = Dispatchers.IO) {
    private val supervisor = SupervisorJob()

    /** 子任务作用域；取消后 [scope].isActive 变为 false。 */
    val scope: CoroutineScope = CoroutineScope(supervisor + dispatcher)

    /** 宿主还没销毁时才是 true。 */
    val isActive: Boolean get() = supervisor.isActive

    /** 取消所有子任务并拒绝后续提交（幂等）。 */
    fun cancel() {
        supervisor.cancel()
    }
}
