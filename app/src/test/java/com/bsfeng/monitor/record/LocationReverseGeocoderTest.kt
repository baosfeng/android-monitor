package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 逆地理编码编排的纯逻辑用例：缓存命中、失败静默、限频、超时口径、地址截断。
 *
 * 全部不碰 Android / 网络：[GeocodeSource] 是注入的假实现。
 */
class LocationReverseGeocoderTest {

    /** 记录调用次数的假来源；[answers] 按顺序出队，空则返回 null。 */
    private class FakeSource(private vararg val answers: String?) : GeocodeSource {
        var calls = 0
        var lastTimeoutMs = 0L
        private var index = 0
        var throwOnCall = false

        override fun lookup(latitude: Double, longitude: Double, timeoutMs: Long): String? {
            calls++
            lastTimeoutMs = timeoutMs
            if (throwOnCall) throw IllegalStateException("boom")
            val value = answers.getOrNull(index)
            index++
            return value
        }
    }

    private val now = 1_700_000_000_000L

    @Test
    fun `缓存命中不再查询来源`() {
        val source = FakeSource("上海市浦东新区世纪大道 100 号")
        val geocoder = LocationReverseGeocoder(source, clock = { now })

        assertEquals("上海市浦东新区世纪大道 100 号", geocoder.resolve(31.2304, 121.4737))
        assertEquals("第二次命中缓存", 1, source.calls)
        assertEquals("缓存键按 3 位小数量化，同一栋楼复用", "上海市浦东新区世纪大道 100 号",
            geocoder.resolve(31.23041, 121.47369))
        assertEquals("量化后同一格 → 不重复请求", 1, source.calls)
    }

    @Test
    fun `失败返回 null 且不写缓存 下次重试`() {
        val source = FakeSource(null, "上海市黄浦区南京东路")
        var time = now
        val geocoder = LocationReverseGeocoder(source, clock = { time })
        val failuresBefore = LocationReverseGeocoder.failureCount

        assertNull(geocoder.resolve(31.2304, 121.4737))
        assertTrue("失败要记诊断计数", LocationReverseGeocoder.failureCount > failuresBefore)
        // 失败不缓存：过了限频窗口后同样的点会重新查询
        time += LocationReverseGeocoder.MIN_REQUEST_INTERVAL_MS
        assertEquals("上海市黄浦区南京东路", geocoder.resolve(31.2304, 121.4737))
        assertEquals(2, source.calls)
    }

    @Test
    fun `来源抛异常也静默返回 null 并记诊断`() {
        val source = FakeSource("上海市").apply { throwOnCall = true }
        val geocoder = LocationReverseGeocoder(source, clock = { now })
        val failuresBefore = LocationReverseGeocoder.failureCount

        assertNull("异常不外抛，调用方照常落库", geocoder.resolve(31.2304, 121.4737))
        assertTrue(LocationReverseGeocoder.failureCount > failuresBefore)
        assertEquals("IllegalStateException", LocationReverseGeocoder.lastFailure)
    }

    @Test
    fun `限频窗口内不重复请求`() {
        val source = FakeSource("上海市", "北京市")
        val geocoder = LocationReverseGeocoder(source, clock = { now })
        assertEquals("上海市", geocoder.resolve(31.2304, 121.4737))
        // 不同位置、仍在 2 秒窗口内 → 跳过（返回 null，不阻塞）
        assertNull(geocoder.resolve(39.9042, 116.4074))
        assertEquals(1, source.calls)
    }

    @Test
    fun `超时口径不超过 5 秒`() {
        val source = FakeSource("上海市")
        LocationReverseGeocoder(source, clock = { now }).resolve(31.2304, 121.4737)
        assertTrue(LocationReverseGeocoder.TIMEOUT_MS in 1..5_000L)
        assertEquals(LocationReverseGeocoder.TIMEOUT_MS, source.lastTimeoutMs)
    }

    @Test
    fun `超长地址截断到上限`() {
        val long = "上海市浦东新区张江高科技园区碧波路 690 号 3 号楼 501 室（近地铁 2 号线张江高科站）"
        assertTrue("用例文本本身要超过上限", long.length > LocationReverseGeocoder.MAX_ADDRESS_LENGTH)
        val geocoder = LocationReverseGeocoder(FakeSource(long), clock = { now })
        val address = geocoder.resolve(31.2304, 121.4737)
        assertNotNull(address)
        assertEquals(LocationReverseGeocoder.MAX_ADDRESS_LENGTH, address!!.length)
    }

    @Test
    fun `LRU 缓存超过容量淘汰最久未用`() {
        val cache = AddressLruCache(capacity = 2)
        cache.put(31.230, 121.473, "A")
        cache.put(31.231, 121.474, "B")
        cache.get(31.230, 121.473) // 让 A 变成最近使用
        cache.put(31.232, 121.475, "C") // 淘汰 B
        assertEquals(2, cache.size())
        assertEquals("A", cache.get(31.230, 121.473))
        assertNull("B 已被淘汰", cache.get(31.231, 121.474))
        assertEquals("C", cache.get(31.232, 121.475))
    }

    @Test
    fun `串联来源在系统 Geocoder 失败后回落在线上服务`() {
        val chain = ChainedGeocodeSource(listOf(FakeSource(null), FakeSource("上海市静安区")))
        assertEquals("上海市静安区", chain.lookup(31.2304, 121.4737, 3_000L))
    }

    @Test
    fun `串联来源按来源数均分超时预算`() {
        val first = FakeSource(null)
        val second = FakeSource(null)
        ChainedGeocodeSource(listOf(first, second)).lookup(31.2304, 121.4737, 3_000L)
        assertEquals(1_500L, first.lastTimeoutMs)
        assertEquals(1_500L, second.lastTimeoutMs)
    }
}
