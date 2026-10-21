package com.bsfeng.monitor.ui.foundation

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [DrawCache] 复用语义回归（Robolectric：Path 走 android.graphics.Path 实现）。
 * 钉住三件事：同 key 返回同一实例、尺寸变化只让 Brush 失效、clear 可强制重建。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DrawCacheTest {

    @Test
    fun `同 key 的 Path 复用同一实例`() {
        val cache = DrawCache()
        val first = cache.path("area") {
            moveTo(0f, 0f)
            lineTo(10f, 10f)
            close()
        }
        val second = cache.path("area") {
            moveTo(0f, 0f)
            lineTo(20f, 20f)
            close()
        }
        assertSame("同 key 必须复用同一 Path 实例", first, second)
    }

    @Test
    fun `不同 key 各自独立`() {
        val cache = DrawCache()
        val a = cache.path("a") { moveTo(0f, 0f) }
        val b = cache.path("b") { moveTo(1f, 1f) }
        assertNotSame(a, b)
        assertEquals(2, cache.cachedCount())
    }

    @Test
    fun `尺寸不变时 Brush 复用同一实例`() {
        val cache = DrawCache()
        cache.onSize(Size(100f, 50f))
        val first = cache.brush("fill") { Brush.verticalGradient(listOf(Color.Red, Color.Blue)) }
        val second = cache.brush("fill") { Brush.verticalGradient(listOf(Color.Red, Color.Blue)) }
        assertSame("尺寸不变必须复用同一 Brush 实例", first, second)
    }

    @Test
    fun `尺寸变化时 Brush 缓存失效`() {
        val cache = DrawCache()
        cache.onSize(Size(100f, 50f))
        val before = cache.brush("fill") { Brush.verticalGradient(listOf(Color.Red, Color.Blue)) }
        cache.onSize(Size(200f, 50f))
        val after = cache.brush("fill") { Brush.verticalGradient(listOf(Color.Red, Color.Blue)) }
        assertNotSame("画布尺寸变化后 Brush 必须重建", before, after)
    }

    @Test
    fun `clear 后全部重建`() {
        val cache = DrawCache()
        cache.onSize(Size(100f, 50f))
        val path = cache.path("area") { moveTo(0f, 0f) }
        val brush = cache.brush("fill") { Brush.verticalGradient(listOf(Color.Red, Color.Blue)) }
        cache.clear()
        assertEquals(0, cache.cachedCount())
        assertNotSame(path, cache.path("area") { moveTo(0f, 0f) })
        assertNotSame(brush, cache.brush("fill") { Brush.verticalGradient(listOf(Color.Red, Color.Blue)) })
    }
}
