package com.bsfeng.monitor.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.bsfeng.monitor.ui.theme.AppBrandColors
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 应用图标的 Bitmap 缓存。
 *
 * 列表滚动时逐行调用 PackageManager 会掉帧，因此：
 * 1) LruCache 缓存（内部已做同步，可跨线程读）；
 * 2) 真实取图只在 Dispatchers.IO 上做；
 * 3) [peek] 供 Composable 首帧同步命中缓存、避免先渲染兜底再跳成真图标。
 */
internal object AppIconCache {

    private const val MAX_ENTRIES = 96

    /** 取图边长（px）：144 足够 96dp 内的显示精度，又不至于让缓存过大。 */
    private const val ICON_PX = 144

    private val cache = LruCache<String, Bitmap>(MAX_ENTRIES)

    /** 同步读缓存；未命中返回 null（不触发加载）。 */
    fun peek(packageName: String): Bitmap? = cache.get(packageName)

    /** 命中缓存直接返回，否则在 IO 线程取图并写入缓存；取不到返回 null。 */
    suspend fun load(context: Context, packageName: String): Bitmap? {
        peek(packageName)?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                context.packageManager.getApplicationIcon(packageName)
                    .toBitmap(ICON_PX, ICON_PX)
                    .also { cache.put(packageName, it) }
            }.getOrNull()
        }
    }
}

/** 原型 .app-icon 的尺寸（30px 方块）。 */
private val APP_ICON_SIZE: Dp = 30.dp

/**
 * 应用图标块（替代原型 .app-icon）：优先显示应用自身的图标，
 * 取不到时回落「品牌色底 + 应用首字」—— 这就是原型 background:var(--app,var(--fg-2)) 的兜底语义。
 *
 * 判断规则（原型 s02 的规律）：行标题里应用名在前（应用事件）用本组件；
 * 系统事件（屏幕点亮 / Wi-Fi 连接 / 充电中 …）仍用 [TypeIconDot]。
 *
 * @param packageName 应用包名（记录里带 pkg）；为 null 时直接走兜底
 * @param appName 应用显示名，兜底时取首字
 */
@Composable
fun AppIcon(
    packageName: String?,
    appName: String,
    modifier: Modifier = Modifier,
    size: Dp = APP_ICON_SIZE,
) {
    val t = LocalDesignTokens.current
    val context = LocalContext.current
    val bitmap by produceState(
        initialValue = packageName?.let { AppIconCache.peek(it) },
        key1 = packageName,
    ) {
        value = packageName?.let { AppIconCache.load(context, it) }
    }

    val bmp = bitmap
    if (bmp != null) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = appName,
            contentScale = ContentScale.Crop,
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(t.radiusSm)),
        )
    } else {
        Box(
            modifier = modifier
                .size(size)
                .clip(RoundedCornerShape(t.radiusSm))
                .background(AppBrandColors.colorOf(packageName) ?: t.fg2)
                .semantics { contentDescription = appName },
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = appName.take(1),
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
            )
        }
    }
}
