package com.bsfeng.monitor.ui.trip

// Compose 侧的瓦片叠加层：给定地图视图，算出要哪些块并异步加载。
//
// 分工：坐标与瓦片编排在 TileMath（纯逻辑、可单测），取图缓存在 TileFetcher（Android），
// 这里只负责「什么时候取、取完怎么交给 Canvas」。

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

/** 已就位的一张瓦片：dp 坐标与边长，直接喂给 Canvas。 */
internal data class PlacedTile(
    val image: ImageBitmap,
    val leftDp: Float,
    val topDp: Float,
    val sizeDp: Float,
)

/**
 * 按视图算出需要的瓦片并异步加载。
 *
 * - 瓦片集合随视图与画布尺寸变化重算；同一块走 [TileFetcher] 的内存/磁盘缓存，不重复下载；
 * - 最多 [TileFetcher.MAX_PARALLEL] 张同时在路上；
 * - 单块失败就不画那一块（底图缺角，轨迹照常显示），不阻塞整张图。
 */
@Composable
internal fun rememberMapTiles(
    view: TileView?,
    widthDp: Float,
    heightDp: Float,
    source: TileSource,
): List<PlacedTile> {
    val context = LocalContext.current
    val refs = remember(view, widthDp, heightDp) {
        if (view == null) {
            emptyList()
        } else {
            TileMath.visibleTiles(view, widthDp.toDouble(), heightDp.toDouble())
        }
    }
    val key = remember(refs) {
        refs.joinToString(separator = "|") { it.z.toString() + "/" + it.x + "/" + it.y }
    }
    val currentSource = rememberUpdatedState(source)
    val tiles by produceState(emptyList<PlacedTile>(), key) {
        if (refs.isEmpty()) {
            value = emptyList()
            return@produceState
        }
        value = withContext(Dispatchers.IO) { loadTiles(context, currentSource.value, refs) }
    }
    return tiles
}

private suspend fun loadTiles(
    context: Context,
    source: TileSource,
    refs: List<TileRef>,
): List<PlacedTile> {
    val fetcher = TileFetcher.get(context)
    val gate = Semaphore(TileFetcher.MAX_PARALLEL)
    return coroutineScope {
        refs.map { ref ->
            async {
                gate.withPermit {
                    fetcher.load(source, ref)?.let { bitmap ->
                        PlacedTile(
                            image = bitmap.asImageBitmap(),
                            leftDp = ref.leftDp,
                            topDp = ref.topDp,
                            sizeDp = ref.sizeDp,
                        )
                    }
                }
            }
        }.awaitAll().filterNotNull()
    }
}
