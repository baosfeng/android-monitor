package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 精度分级与「低置信点不许覆盖更优位置」的用例。
 *
 * 反向验证（见 .open-design/impl-notes/location-fix.md）：把 [LocationQuality.shouldAccept]
 * 降级成旧的「一刀切 100m」判定，[networkCoarseDotDoesNotCoverBetterGps] 与
 * [indoorNetworkDotIsRecorded] 会变红 —— 这两条就是这个 bug 的防复发锚点。
 */
class LocationQualityTest {

    private val now = 1_700_000_000_000L

    private fun point(
        provider: String,
        accuracy: Float,
        lat: Double = 31.2304,
        lng: Double = 121.4737,
        ageMs: Long = 0L,
    ) = LocationSample(provider, lat, lng, accuracy, now - ageMs)

    /** 纬度 0.001 度约 111m：用它造位移。 */
    private fun north(lat: Double, meters: Double) = lat + meters / 111_000.0

    // ===== 分级：正常路径 =====

    @Test
    fun `GPS 100米内是高置信 100到500米是低置信`() {
        assertEquals(FixConfidence.HIGH, LocationQuality.confidenceOf("gps", 5f))
        assertEquals(FixConfidence.HIGH, LocationQuality.confidenceOf("gps", 100f))
        assertEquals(FixConfidence.LOW, LocationQuality.confidenceOf("gps", 100.1f))
        assertEquals(FixConfidence.LOW, LocationQuality.confidenceOf("gps", 499f))
    }

    @Test
    fun `室内NETWORK 300米不再被一刀切丢掉`() {
        // 旧实现：300m > 100m 直接丢 -> 室内用户「掉线」。新实现记成低置信。
        assertEquals(FixConfidence.LOW, LocationQuality.confidenceOf("network", 300f))
        assertTrue("300m 的室内点必须还能落库", LocationQuality.isAcceptable("network", 300f))
        assertEquals(FixConfidence.LOW, LocationQuality.confidenceOf("passive", 480f))
    }

    // ===== 分级：边界 =====

    @Test
    fun `精度门槛按严格小于卡边界`() {
        assertEquals("正好 500m 的 NETWORK 点没有信息量", FixConfidence.REJECTED, LocationQuality.confidenceOf("network", 500f))
        assertEquals(FixConfidence.LOW, LocationQuality.confidenceOf("network", 499.9f))
        assertEquals(FixConfidence.REJECTED, LocationQuality.confidenceOf("gps", 500f))
        assertEquals(FixConfidence.LOW, LocationQuality.confidenceOf("gps", 499.9f))
    }

    @Test
    fun `系统未给精度一律不可用`() {
        assertEquals(FixConfidence.REJECTED, LocationQuality.confidenceOf("gps", 0f))
        assertEquals(FixConfidence.REJECTED, LocationQuality.confidenceOf("network", -1f))
        assertFalse(LocationQuality.isAcceptable("gps", 0f))
    }

    @Test
    fun `未知provider按低置信处理而不是高置信`() {
        // fused 是系统融合 provider，与 gps 同级；真正未知的 provider 只能算低置信
        assertEquals(FixConfidence.HIGH, LocationQuality.confidenceOf("fused", 60f))
        assertEquals(FixConfidence.LOW, LocationQuality.confidenceOf("something-else", 60f))
    }

    // ===== passive 与 gps / fused 同口径（task-5 的回归锚点） =====

    @Test
    fun `passive 按精度分级 不再按 provider 名一刀切`() {
        // 原 bug：passive 落进 else 分支，永远不高置信。而 PROVIDER_PRIORITY 把 passive 排第一，
        // 系统缓存里那条 5 米的 passive 点因此永远判 LOW -> 模式永远 STILL。
        assertEquals("5 米的 passive 点必须高置信", FixConfidence.HIGH, LocationQuality.confidenceOf("passive", 5f))
        assertEquals(FixConfidence.HIGH, LocationQuality.confidenceOf("passive", 100f))
        assertEquals(FixConfidence.LOW, LocationQuality.confidenceOf("passive", 100.1f))
        assertEquals(FixConfidence.LOW, LocationQuality.confidenceOf("passive", 300f))
        assertEquals("正好 500m 没有信息量", FixConfidence.REJECTED, LocationQuality.confidenceOf("passive", 500f))
    }

    @Test
    fun `passive 与 gps 同精度时判定一致`() {
        listOf(5f, 100f, 200f, 499f, 500f).forEach { accuracy ->
            assertEquals(
                "passive 与 gps 在 " + accuracy + "m 上必须同口径",
                LocationQuality.confidenceOf("gps", accuracy),
                LocationQuality.confidenceOf("passive", accuracy),
            )
        }
    }

    @Test
    fun `passive 高置信点可以落库 不受低置信位移门槛限制`() {
        // 后果链第二段：passive 判 LOW 时，两个 LOW 之间要求位移 >150m 才落库 -> 轨迹被节流成稀疏
        val previous = point("passive", 5f)
        val candidate = point("passive", 5f, lat = north(31.2304, 40.0))
        assertTrue("两个高置信 passive 点，40m 位移就该落库", LocationQuality.shouldAccept(candidate, previous, now))
    }

    @Test
    fun `低置信 passive 点仍不许覆盖更优位置`() {
        val goodPassive = point("passive", 5f)
        val coarsePassive = point("passive", 300f)
        assertFalse(LocationQuality.shouldAccept(coarsePassive, goodPassive, now))
    }

    // ===== 质量闸：正常路径 =====

    @Test
    fun `第一个点永远被接受`() {
        assertTrue(LocationQuality.shouldAccept(point("network", 400f), null, now))
        assertTrue(LocationQuality.shouldAccept(point("gps", 8f), null, now))
    }

    @Test
    fun `高置信点不会因为没怎么动被丢`() {
        val previous = point("gps", 8f)
        val candidate = point("gps", 9f, lat = north(31.2304, 3.0))
        assertTrue("10 秒节奏的轨迹点必须能落库", LocationQuality.shouldAccept(candidate, previous, now))
    }

    // ===== 质量闸：反向用例（抓原 bug） =====

    @Test
    fun `networkCoarseDotDoesNotCoverBetterGps`() {
        // 原 bug：缓存里的 80m NETWORK 粗点被当成有效点位落库，画出来就是漂移
        val goodGps = point("gps", 8f)
        val coarseNetwork = point("network", 80f)
        assertFalse(
            "高置信点在手时，原地不动的低置信点不许覆盖它",
            LocationQuality.shouldAccept(coarseNetwork, goodGps, now),
        )
    }

    @Test
    fun `indoorNetworkDotIsRecorded`() {
        // 原 bug：室内 300m 的 NETWORK 点被 100m 一刀切丢掉，用户以为掉线了
        assertTrue(LocationQuality.isAcceptable("network", 300f))
        assertTrue(LocationQuality.shouldAccept(point("network", 300f), null, now))
    }

    @Test
    fun `低置信点真走远了才替换高置信位置`() {
        val goodGps = point("gps", 8f)
        val farNetwork = point("network", 90f, lat = north(31.2304, 400.0))
        assertTrue(LocationQuality.shouldAccept(farNetwork, goodGps, now))
    }

    @Test
    fun `两个低置信点之间原地乱跳被吸收`() {
        val previous = point("network", 300f)
        val jitter = point("network", 300f, lat = north(31.2304, 20.0))
        assertFalse("误差圈里 20m 的抖动不值得多落一条", LocationQuality.shouldAccept(jitter, previous, now))
    }

    @Test
    fun `低置信点精度明显改善时允许覆盖`() {
        val previous = point("network", 300f)
        val better = point("network", 200f, lat = north(31.2304, 10.0))
        assertTrue("改善 100m >= 20m，值得更新", LocationQuality.shouldAccept(better, previous, now))
    }

    @Test
    fun `已有点位stale时新点直接接上轨迹`() {
        val old = point("gps", 5f, ageMs = LocationPolicy.MAX_AGE_MS + 1)
        val candidate = point("network", 400f)
        assertTrue("30 分钟前的点位不构成约束", LocationQuality.shouldAccept(candidate, old, now))
    }

    // ===== 质量闸：异常路径 =====

    @Test
    fun `精度不可用的点在任何情况下都不落库`() {
        assertFalse(LocationQuality.shouldAccept(point("gps", 0f), null, now))
        assertFalse(LocationQuality.shouldAccept(point("network", 900f), null, now))
        assertFalse(LocationQuality.isAcceptable("passive", 5000f))
    }

    @Test
    fun `pick 按分级挑点 不再一刀切100米`() {
        // 旧实现 isUsable 走 100m 门槛：室内 350m 的 NETWORK 点被丢掉 -> pick 返回 null（掉线）
        val indoorNetwork = point("network", 350f)
        val picked = LocationPolicy.pick(listOf(indoorNetwork), now)
        assertEquals("350m 仍在容差内，必须能挑出来", "network", picked?.provider)
        assertNull("超过 500m 仍然没有信息量", LocationPolicy.pick(listOf(point("network", 600f)), now))
    }
}
