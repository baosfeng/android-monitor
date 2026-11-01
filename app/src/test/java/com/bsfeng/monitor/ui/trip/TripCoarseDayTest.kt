package com.bsfeng.monitor.ui.trip

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Locale
import kotlin.math.abs

/**
 * 粗坐标日与平滑的接线测试：历史 3 位小数坐标（≈110m）**不做平滑**。
 *
 * 反面证据在同一个类里：把同一几何换成 6 位小数，中间点就会被邻点平均掉 ——
 * 说明「没被移动」确实是跳过了平滑，而不是平滑恰好没起作用。
 */
class TripCoarseDayTest {

    /** 与库里同一写法：精度位由 [decimals] 决定（历史 3 位、新采集 6 位）。 */
    private fun location(ts: Long, lat: Double, lng: Double, accuracy: Float, decimals: Int = 3): Record = Record(
        id = "loc-" + ts,
        type = RecordType.LOCATION,
        action = "COORDINATE",
        timestamp = ts,
        label = "路上",
        detail = mapOf(
            "lat" to String.format(Locale.US, "%.${decimals}f", lat),
            "lng" to String.format(Locale.US, "%.${decimals}f", lng),
            "accuracy_m" to accuracy.toInt().toString(),
        ),
    )

    /** 三点：中间那点偏离首尾弦约 85m；精度 300m 时拐点保护阈值 600m，平滑必然把它拉回弦上。 */
    private fun trip(midLat: Double, midLng: Double, decimals: Int): List<Record> = listOf(
        location(0, 39.900, 116.300, 300f, decimals),
        location(60_000, midLat, midLng, 300f, decimals),
        location(120_000, 39.902, 116.300, 300f, decimals),
    )

    @Test
    fun `三点粗坐标被判定为历史粗坐标日`() {
        val samples = TripTrace.samples(trip(39.901, 116.301, 3))
        assertEquals(3, samples.size)
        assertTrue("3 位小数占比 100%，应判粗坐标日", TracePrecision.isCoarseDay(samples))
    }

    @Test
    fun `粗坐标日的中间点原样保留不被平滑`() {
        val samples = TripTrace.samples(trip(39.901, 116.301, 3))
        assertEquals(39.901, samples[1].lat, 0.0)
        assertEquals("粗坐标日跳过平滑：中间点必须还是原始量化值", 116.301, samples[1].lng, 0.0)
    }

    @Test
    fun `同一几何换成六位小数就会被平滑`() {
        // 首尾点也要落在网格外（尾部带非零的第 5/6 位）：3 位小数的量化产物不会长这样，
        // 否则这一天会被当成粗坐标日，恰好掩盖平滑是否接上。
        val samples = TripTrace.samples(
            listOf(
                location(0, 39.900050, 116.300050, 300f, 6),
                location(60_000, 39.901100, 116.301150, 300f, 6),
                location(120_000, 39.902050, 116.300050, 300f, 6),
            ),
        )
        assertEquals(3, samples.size)
        assertTrue(
            "6 位小数日应当被平滑：中间点的经度应被邻点拉离原值",
            abs(samples[1].lng - 116.30115) > 1e-5,
        )
    }
}
