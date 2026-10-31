package com.bsfeng.monitor.ui

import com.bsfeng.monitor.record.LocationFailureReason
import com.bsfeng.monitor.record.LocationPermissionStatus
import com.bsfeng.monitor.record.LocationStatus
import com.bsfeng.monitor.ui.components.BadgeTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 采集状态文案（LocationHealthText）单元测试。
 *
 * 对应「过一会就掉线」的可观测性：用户能不能从设置页一眼看出「现在到底还在不在采」。
 * 纯函数，因此四档判定与三种原因文案都在这里钉死。
 */
class LocationHealthTextTest {

    private fun status(
        sinceLastSuccess: Long? = null,
        subscriptionActive: Boolean = true,
        failure: LocationFailureReason = LocationFailureReason.NONE,
        accuracy: Float? = null,
    ) = LocationStatus(
        lastSuccessAtMs = sinceLastSuccess?.let { 1_000_000L - it },
        msSinceLastSuccess = sinceLastSuccess,
        lastFailure = failure,
        subscriptionActive = subscriptionActive,
        lastAccuracyMeters = accuracy,
    )

    @Test
    fun `没有状态时判未运行`() {
        assertEquals(LocationHealthLevel.OFF, LocationHealthText.levelOf(null))
        assertEquals("未运行", LocationHealthText.badge(null))
        assertEquals("读取采集状态失败", LocationHealthText.describe(null))
    }

    @Test
    fun `订阅在跑但还没成功过判等待中`() {
        val level = LocationHealthText.levelOf(status(subscriptionActive = true))
        assertEquals(LocationHealthLevel.WAITING, level)
        assertEquals("等待中", LocationHealthText.badge(status()))
        assertTrue(LocationHealthText.describe(status()).contains("还没有成功定位"))
    }

    @Test
    fun `订阅没建立且从未成功判未运行`() {
        assertEquals(LocationHealthLevel.OFF, LocationHealthText.levelOf(status(subscriptionActive = false)))
    }

    @Test
    fun `六分钟内成功过判采集中`() {
        val level = LocationHealthText.levelOf(status(sinceLastSuccess = 60_000L, accuracy = 18f))
        assertEquals(LocationHealthLevel.GOOD, level)
        assertEquals("采集中", LocationHealthText.badge(status(60_000L)))
        val text = LocationHealthText.describe(status(60_000L, accuracy = 18f))
        assertTrue(text, text.contains("1 分钟前"))
        assertTrue(text, text.contains("±18m"))
        assertTrue("采集中不该出现失败原因", !text.contains("权限"))
    }

    @Test
    fun `刚超过新鲜窗口判等待中`() {
        val level = LocationHealthText.levelOf(status(sinceLastSuccess = 8 * 60_000L))
        assertEquals(LocationHealthLevel.WAITING, level)
    }

    @Test
    fun `长时间无成功判断档并给出原因`() {
        val s = status(
            sinceLastSuccess = 40 * 60_000L,
            failure = LocationFailureReason.ACTIVE_FIX_TIMEOUT,
        )
        assertEquals(LocationHealthLevel.STALE, LocationHealthText.levelOf(s))
        assertEquals("已断档", LocationHealthText.badge(s))
        val text = LocationHealthText.describe(s)
        assertTrue(text, text.contains("40 分钟前"))
        assertTrue("断档必须给出可执行原因", text.contains("省电策略"))
    }

    @Test
    fun `超过一小时按小时显示`() {
        val text = LocationHealthText.describe(status(sinceLastSuccess = 3 * 3600_000L))
        assertTrue(text, text.contains("3 小时前"))
    }

    @Test
    fun `订阅状态如实呈现`() {
        assertTrue(LocationHealthText.describe(status(60_000L, subscriptionActive = true)).contains("持续订阅在跑"))
        assertTrue(LocationHealthText.describe(status(60_000L, subscriptionActive = false)).contains("持续订阅未建立"))
    }

    @Test
    fun `每种失败原因都有面向用户的可执行文案`() {
        LocationFailureReason.entries.forEach { reason ->
            val text = LocationHealthText.reasonText(reason)
            assertTrue("原因 " + reason + " 缺文案", text.isNotBlank())
            assertTrue(
                "文案不该是裸枚举名：" + text,
                !text.contains("_" ) && text != reason.name,
            )
        }
        assertTrue(LocationHealthText.reasonText(LocationFailureReason.PERMISSION).contains("始终允许"))
    }

    @Test
    fun `精度缺失时不拼空的精度括号`() {
        val text = LocationHealthText.describe(status(60_000L, accuracy = null))
        assertTrue(text, !text.contains("±"))
    }

    // ===== 权限四态文案（P0：只给大致位置时不能显示「已就绪」） =====

    @Test
    fun `只给大致位置时徽标摘要与色调都按原型走`() {
        val copy = LocationHealthText.locationCopyOf(LocationPermissionStatus.COARSE_ONLY)
        assertEquals("大致位置", copy.badge)
        assertEquals(BadgeTone.Warn, copy.tone)
        assertEquals("仅大致位置 · 误差可达数百米", copy.summary)
    }

    @Test
    fun `只给大致位置时任何一处文案都不出现已就绪`() {
        val copy = LocationHealthText.locationCopyOf(LocationPermissionStatus.COARSE_ONLY)
        listOf(
            copy.badge,
            copy.summary,
            copy.check,
            copy.stepsHint,
            copy.step2Title,
            copy.step2Desc,
            copy.step3Title,
            copy.step3Desc,
        ).forEach { text ->
            assertFalse("大致位置态不该出现「已就绪」：$text", text.contains("已就绪"))
        }
    }

    @Test
    fun `只有已就绪态才出现已就绪三个字`() {
        LocationPermissionStatus.entries.forEach { status ->
            val copy = LocationHealthText.locationCopyOf(status)
            val hasReady = copy.badge.contains("已就绪") || copy.check.contains("已就绪")
            assertEquals(
                "只有 GRANTED 能显示已就绪，实际 $status",
                status == LocationPermissionStatus.GRANTED,
                hasReady,
            )
        }
    }

    @Test
    fun `大致位置态第 2 步是改成精确位置且步骤总数仍是 3`() {
        val coarse = LocationHealthText.locationCopyOf(LocationPermissionStatus.COARSE_ONLY)
        assertEquals("改成「精确位置」", coarse.step2Title)
        assertEquals("应用权限 › 位置信息 › 使用精确位置", coarse.step2Desc)
        assertTrue("第 2 步未完成", coarse.doneSteps < 2)
        assertTrue("第 1 步已完成", coarse.doneSteps >= 1)
        // 三步清单写死 3 步：doneSteps 取值域必须落在 0..3
        LocationPermissionStatus.entries.forEach { status ->
            val step = LocationHealthText.locationCopyOf(status).doneSteps
            assertTrue("doneSteps 越界：$status=$step", step in 0..3)
        }
    }

    @Test
    fun `大致位置态第 3 步是选始终允许且清单仍是三步`() {
        // 原型「仅大致位置」屏：第 1 步开启定位（已完成）、第 2 步改成精确位置、第 3 步选「始终允许」
        val coarse = LocationHealthText.locationCopyOf(LocationPermissionStatus.COARSE_ONLY)
        assertEquals("选「始终允许」", coarse.step3Title)
        assertEquals("应用权限 › 位置信息 › 始终允许", coarse.step3Desc)
        assertTrue("三步清单头部要提到精确位置：" + coarse.stepsHint, coarse.stepsHint.contains("改成精确位置"))
    }

    @Test
    fun `其余三态第 3 步是关掉省电限制`() {
        listOf(
            LocationPermissionStatus.NOT_GRANTED,
            LocationPermissionStatus.MISSING_BACKGROUND,
            LocationPermissionStatus.GRANTED,
        ).forEach { status ->
            assertEquals("$status 的第 3 步", "关掉省电限制", LocationHealthText.locationCopyOf(status).step3Title)
        }
    }

    @Test
    fun `其余三态第 2 步仍是选始终允许`() {
        listOf(
            LocationPermissionStatus.NOT_GRANTED,
            LocationPermissionStatus.MISSING_BACKGROUND,
            LocationPermissionStatus.GRANTED,
        ).forEach { status ->
            assertEquals(
                "$status 的第 2 步不该被改成精确位置",
                "选「始终允许」",
                LocationHealthText.locationCopyOf(status).step2Title,
            )
        }
    }

    @Test
    fun `只有已就绪是成功色其余都是中性或警告`() {
        LocationPermissionStatus.entries.forEach { status ->
            val expected = when (status) {
                LocationPermissionStatus.GRANTED -> BadgeTone.Success
                LocationPermissionStatus.NOT_GRANTED -> BadgeTone.Neutral
                else -> BadgeTone.Warn
            }
            assertEquals("$status 的色调", expected, LocationHealthText.locationCopyOf(status).tone)
        }
    }

}