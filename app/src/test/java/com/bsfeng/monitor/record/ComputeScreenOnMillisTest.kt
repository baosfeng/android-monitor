package com.bsfeng.monitor.record

import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * computeScreenOnMillis 单元测试：UNLOCKED→OFF 配对计算亮屏时长。
 */
class ComputeScreenOnMillisTest {

    private fun screenRecord(action: String, timestamp: Long): Record =
        Record(
            id = "$action-$timestamp",
            type = RecordType.SCREEN,
            action = action,
            timestamp = timestamp,
            label = ""
        )

    /** 基准时间：当天 10:00 */
    private val T0 = 1_700_000_000_000L

    @Test
    fun `空列表返回 0`() {
        assertEquals(0L, computeScreenOnMillis(emptyList()))
    }

    @Test
    fun `正常配对累加亮屏时长`() {
        // 10:00 解锁 → 10:05 熄屏 = 5 分钟;10:10 解锁 → 10:20 熄屏 = 10 分钟
        val records = listOf(
            screenRecord("UNLOCKED", T0),
            screenRecord("OFF", T0 + 5 * 60_000L),
            screenRecord("UNLOCKED", T0 + 10 * 60_000L),
            screenRecord("OFF", T0 + 20 * 60_000L)
        )
        assertEquals(15 * 60_000L, computeScreenOnMillis(records))
    }

    @Test
    fun `无配对 OFF 的 UNLOCKED 按估算值计算`() {
        // 10:00 解锁后统计区间内未熄屏
        val records = listOf(screenRecord("UNLOCKED", T0))
        assertEquals(5 * 60_000L, computeScreenOnMillis(records))
    }

    @Test
    fun `无配对 UNLOCKED 的 OFF 按估算值计算`() {
        // 亮屏未解锁直接熄屏(跨天边界或锁屏通知场景)
        val records = listOf(screenRecord("OFF", T0))
        assertEquals(5 * 60_000L, computeScreenOnMillis(records))
    }

    @Test
    fun `多个无配对记录分别估算`() {
        val records = listOf(
            screenRecord("UNLOCKED", T0),
            screenRecord("OFF", T0 + 60_000L),
            screenRecord("UNLOCKED", T0 + 120_000L)
        )
        // 第一对配对 1 分钟 + 末尾未配对估算 5 分钟
        assertEquals(60_000L + 5 * 60_000L, computeScreenOnMillis(records))
    }

    @Test
    fun `自定义估算值生效`() {
        val records = listOf(screenRecord("UNLOCKED", T0))
        assertEquals(3 * 60_000L, computeScreenOnMillis(records, estimateMs = 3 * 60_000L))
    }

    @Test
    fun `OFF 时间早于 UNLOCKED 时不产生负时长`() {
        // 异常顺序:OFF 在前 UNLOCKED 在后,不配对也不产生负值
        val records = listOf(
            screenRecord("OFF", T0),
            screenRecord("UNLOCKED", T0 + 60_000L)
        )
        // OFF 无配对估算 5 分钟 + 末尾 UNLOCKED 无配对估算 5 分钟
        assertEquals(10 * 60_000L, computeScreenOnMillis(records))
    }

    @Test
    fun `非 SCREEN 事件不影响配对逻辑`() {
        val records = listOf(
            screenRecord("UNLOCKED", T0),
            screenRecord("OFF", T0 + 5 * 60_000L),
            Record(
                id = "x",
                type = RecordType.WIFI,
                action = "CONNECTED",
                timestamp = T0 + 60_000L,
                label = ""
            )
        )
        assertEquals(5 * 60_000L, computeScreenOnMillis(records))
    }
}
