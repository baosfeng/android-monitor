package com.bsfeng.monitor.ui.foundation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Path

/**
 * 绘制对象复用：让「尺寸不变时复用 Path / Brush」成为默认写法。
 *
 * 为什么：`drawWithCache` 的 build 块只在尺寸 / 状态失效时重跑，但把 `Path()` /
 * `Brush.xxxGradient(...)` 写进 `onDrawBehind` 的写法仍会在每帧 new 一次 —— 分配开销与
 * GC 抖动都落在 UI 线程，滚动时表现为偶发长帧。这里把对象交给 [DrawCache] 按 key 持有：
 *
 * - [path]：同 key 复用同一 [Path] 实例（重写前 `reset`，不产生新对象）；
 * - [brush]：同 key 复用同一 [Brush]；画布尺寸变化时由 [onSize] 自动整体失效；
 * - key 请带上影响结果的输入（如颜色、数据版本），尺寸类输入由 [onSize] 负责。
 *
 * 用法（composition 里 `rememberDrawCache()`，drawWithCache 的 build 块内调用）：
 * ```
 * val cache = rememberDrawCache()
 * Modifier.drawWithCache {
 *     cache.onSize(size)
 *     val area = cache.path("area") { moveTo(0f, size.height); /* ... */ }
 *     val fill = cache.brush(line) { Brush.verticalGradient(listOf(top, bottom)) }
 *     onDrawBehind { drawPath(area, fill, alpha = progress.value) }
 * }
 * ```
 *
 * 边界：`Path` 每次 [path] 调用都会被重写，同一个 key 不要跨绘制周期保存返回值；
 * 缓存按 key 无限增长的风险由调用方控制（key 取少量常量 / 颜色即可）。
 */
class DrawCache {

    private val paths = mutableMapOf<Any, Path>()
    private val brushes = mutableMapOf<Any, Brush>()
    private var canvasSize: Size? = null

    /**
     * 进入一次绘制构建：画布尺寸变化时清空 Brush 缓存（shader 与尺寸绑定）；
     * Path 只按坐标重写，实例继续复用。每次 build 块开头调用一次即可。
     */
    fun onSize(size: Size) {
        if (canvasSize != size) {
            canvasSize = size
            brushes.clear()
        }
    }

    /** 同 key 复用 [Path] 实例：重写前 reset，避免每帧 / 每次尺寸变化都 new。 */
    fun path(key: Any, build: Path.() -> Unit): Path =
        (paths[key] ?: Path().also { paths[key] = it }).also {
            it.reset()
            it.build()
        }

    /** 同 key 复用 [Brush]；key 里请带上颜色 / 主题等输入，尺寸变化由 [onSize] 处理。 */
    fun brush(key: Any, build: () -> Brush): Brush =
        brushes[key] ?: build().also { brushes[key] = it }

    /** 清空全部缓存（主题切换、数据源重置等需要强制重建的场景）。 */
    fun clear() {
        paths.values.forEach { it.reset() }
        paths.clear()
        brushes.clear()
        canvasSize = null
    }

    /** 已持有的对象数（测试与诊断用）。 */
    internal fun cachedCount(): Int = paths.size + brushes.size
}

/** composition 级持有：跨越重组与绘制构建复用同一组绘制对象。 */
@Composable
fun rememberDrawCache(): DrawCache = remember { DrawCache() }
