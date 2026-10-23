package com.bsfeng.monitor.debug

import android.util.Log
import com.bsfeng.monitor.ui.trip.clearTripCache

/**
 * 行程缓存（`ui/trip/TripViewModel.kt` 的 `TripCache`）的 debug 侧清理口。
 *
 * 走**直接调用**而不是反射：debug 包同样开了 R8（`isMinifyEnabled = true`），
 * `TripCache` 的方法是文件级 private object 的成员，打包后方法名不可靠 —— 早先版本用
 * `Class.forName` + `getDeclaredMethod("clear")` 在模拟器上恒返回 false（缓存没清，
 * 同分钟 `open trip` 看到的还是旧时间轴）。现在由 main 侧暴露一个 `internal fun clearTripCache()`
 * （release 无调用方，R8 会裁掉），debug 包直接调它：引用可见 → R8 保留 → 与名称无关 → 稳定生效。
 *
 * 失败处置：缓存没清会让验证方看到旧数据，所以这里**不吞异常** —— 记一条 `W/DebugTripCache`，
 * 并把原因交给调用方写进 `RESULT` 的 `cacheClearedError` 字段。
 */
internal object DebugTripCache {

    private const val TAG = "DebugTripCache"

    /** @return null = 缓存已清；非 null = 失败原因（调用方应写进结果行，而不是当作已刷新） */
    fun clear(): String? = try {
        clearTripCache()
        null
    } catch (t: Throwable) {
        Log.w(TAG, "清行程缓存失败", t)
        t.javaClass.simpleName + ": " + (t.message ?: "")
    }
}
