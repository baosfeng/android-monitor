package com.bsfeng.monitor.debug

import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.bsfeng.monitor.ui.foundation.MonitorEdgeToEdge
import com.bsfeng.monitor.ui.theme.MonitorTheme
import com.bsfeng.monitor.ui.trip.TripScreen
import com.bsfeng.monitor.ui.trip.TripViewModel

/**
 * 行程页（s16）的 debug 直达宿页（**只在 debug 变体注册**）。
 *
 * 为什么单独开一个 Activity：`TripScreen` 是 `MainActivity` 里的 `showTripScreen` 状态驱动的二级页，
 * 没有深链 extra。调试 CLI 要「一条广播直达行程页」而又不能改生产侧的 `MainActivity`，
 * 就把它单独托起来 —— 只是调试入口，release 包里不存在。
 *
 * 刷新语义（两次踩坑后的结论）：
 * 1. `TripCache` 的键含「分钟」，同分钟 `seed` 会命中旧结果 → 每次进入 / 重入都先 `DebugTripCache.clear()`；
 * 2. **只 `recreate()` 不够**：ViewModel 跨 `recreate()` 存活，而 `TripScreen` 的
 *    `LaunchedEffect(Unit) { ensureLoaded() }` 有 `loaded` 守卫，重算不会发生。所以这里自己持有
 *    ViewModel 并显式 `refresh()` —— 这才是「同分钟 seed 完立刻 open 就能看到」的可靠路径。
 *
 * ⚠️ 依赖 `ui/trip/TripScreen(onBack=…, viewModel=…)` 与 `TripViewModel.refresh()` 的签名：
 *    签名变动时这里会一起编译失败（有意如此，比运行期白屏更早暴露）。
 */
class DebugTripActivity : ComponentActivity() {

    private lateinit var tripViewModel: TripViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MonitorEdgeToEdge.apply(this)
        tripViewModel = ViewModelProvider(this)[TripViewModel::class.java]
        // 直接 adb start 进来（不经 open 命令）时也保证拿到最新数据
        DebugTripCache.clear()
        setContent {
            MonitorTheme {
                TripScreen(onBack = { finish() }, viewModel = tripViewModel)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // 同分钟再次 open：清缓存 + 显式刷新（ensureLoaded 的 loaded 守卫会挡住单纯重建）
        val cacheError = DebugTripCache.clear()
        Log.i(TAG, "再次 open trip：清缓存 cacheCleared=${cacheError == null}，强制 refresh")
        tripViewModel.refresh()
    }

    private companion object {
        const val TAG = "DebugTripActivity"
    }
}
