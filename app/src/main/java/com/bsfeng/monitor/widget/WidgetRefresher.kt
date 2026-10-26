package com.bsfeng.monitor.widget

import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.updateAll
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicLong

/**
 * 组件刷新入口：判断「有没有添加到桌面」→ IO 协程读数据 → 交给 Glance 重画。
 *
 * 刷新源（见 docs/小组件/设计.md）：
 * - [refreshAll]：`AlarmReceiver` 的 60 秒心跳兜底，30 秒内重复调用直接丢弃；
 * - [refreshFinance]：记账写操作的 `FinanceMutationSignal`，10 秒节流（一笔一笔记账时不至于连刷）。
 *
 * 为什么节流记在内存：进程活着时才能收到这些信号，进程死了就由下一次心跳重建，
 * 落盘只会多一次写盘与一个「上次刷新时间」的失效清理。
 */
object WidgetRefresher {

    private const val TAG = "WidgetRefresher"

    /** 心跳 60 秒一跳，节流 30 秒：心跳重叠（系统补发）时不会连着刷两遍。 */
    private const val ALL_THROTTLE_MS = 30_000L

    /** 记账信号按笔触发，10 秒内的第二笔等下一次信号/心跳，避免高频写库。 */
    private const val FINANCE_THROTTLE_MS = 10_000L

    private val lastAllAt = AtomicLong(0L)
    private val lastFinanceAt = AtomicLong(0L)

    /** 刷新全部 6 个组件（未添加的自动跳过）。 */
    fun refreshAll(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (!claim(lastAllAt, now, ALL_THROTTLE_MS)) return
        dispatch(context, TARGETS)
    }

    /** 只刷记账组件（记账写操作后调用）。 */
    fun refreshFinance(context: Context) {
        val now = SystemClock.elapsedRealtime()
        if (!claim(lastFinanceAt, now, FINANCE_THROTTLE_MS)) return
        dispatch(context, listOf(FINANCE))
    }

    private fun dispatch(context: Context, targets: List<WidgetTarget>) {
        val app = context.applicationContext
        // 广播回调返回后进程随时可能被回收：刷新必须挂在独立作用域上，不能依赖调用方的生命周期
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        targets.forEach { target ->
            scope.launch {
                // 逐个 catch：一个组件异常不能连带另外 5 个（它们在同一个广播里被刷新）
                runCatching { updateIfPresent(app, target) }
                    .onFailure { Log.w(TAG, "刷新 " + target.receiver.simpleName + " 失败", it) }
            }
        }
    }

    private suspend fun updateIfPresent(context: Context, target: WidgetTarget) {
        val ids = runCatching {
            AppWidgetManager.getInstance(context)
                .getAppWidgetIds(ComponentName(context, target.receiver))
        }.getOrNull() ?: IntArray(0)
        // 没添加到桌面就到此为止：updateAll 会先读 Glance 的 datastore，命中后再触发 provideGlance（读库），
        // 桌面上一块都没有时整条链路纯属浪费电量 —— 这是每分钟一次的心跳，代价要算清楚。
        if (ids.isEmpty()) return
        // updateAll 是 Glance 唯一的公开更新入口（AppWidgetId 构造器标了 @RestrictTo(LIBRARY_GROUP)），
        // 它按组件类名反查 id 列表后逐个 update；上面已经确认过有实例，这里不会空转。
        target.widget.updateAll(context)
    }

    /**
     * 抢刷新权（CAS）。
     *
     * 用 [SystemClock.elapsedRealtime] 而不是墙钟：用户改系统时间不该让节流失效或永久卡住。
     */
    private fun claim(lastAt: AtomicLong, nowMs: Long, throttleMs: Long): Boolean {
        while (true) {
            val previous = lastAt.get()
            if (nowMs - previous < throttleMs) return false
            if (lastAt.compareAndSet(previous, nowMs)) return true
        }
    }

    private class WidgetTarget(
        val receiver: Class<out GlanceAppWidgetReceiver>,
        val widget: GlanceAppWidget,
    )

    private val FINANCE = WidgetTarget(FinanceWidgetReceiver::class.java, FinanceWidget())

    private val TARGETS = listOf(
        FINANCE,
        WidgetTarget(HealthWidgetReceiver::class.java, HealthWidget()),
        WidgetTarget(QuotaWidgetReceiver::class.java, QuotaWidget()),
        WidgetTarget(AdSkipWidgetReceiver::class.java, AdSkipWidget()),
        WidgetTarget(QuickAddWidgetReceiver::class.java, QuickAddWidget()),
        WidgetTarget(TransferWidgetReceiver::class.java, TransferWidget()),
    )
}
