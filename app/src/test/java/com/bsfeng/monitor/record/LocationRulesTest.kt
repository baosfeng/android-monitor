package com.bsfeng.monitor.record

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * 定位权限四态判定（LocationPermissionRules）单元测试。
 *
 * 复现的 P0 缺陷：Android 12+ 用户只授予 ACCESS_COARSE_LOCATION 时，旧实现把它当「已就绪」
 * （isFineGranted = FINE || COARSE），于是设置页绿标、行程页永远空白 —— 系统只派 network 点
 * （500~3000m），被 LocationQuality 的精度闸整批丢弃。
 *
 * 这里用表驱动把三入参的 8 种组合全部钉死，再单独锁优先级与「不能退化成三态」。
 */
class LocationRulesTest {

    /** 8 种入参组合 → 期望状态（顺序固定：fine / coarse / background）。 */
    private val matrix = listOf(
        Triple(false, false, false) to LocationPermissionStatus.NOT_GRANTED,
        Triple(false, false, true) to LocationPermissionStatus.NOT_GRANTED,
        Triple(false, true, false) to LocationPermissionStatus.COARSE_ONLY,
        Triple(false, true, true) to LocationPermissionStatus.COARSE_ONLY,
        Triple(true, false, false) to LocationPermissionStatus.MISSING_BACKGROUND,
        Triple(true, false, true) to LocationPermissionStatus.GRANTED,
        Triple(true, true, false) to LocationPermissionStatus.MISSING_BACKGROUND,
        Triple(true, true, true) to LocationPermissionStatus.GRANTED,
    )

    @Test
    fun `三入参八种组合全部判定正确`() {
        assertEquals("组合矩阵必须是全部 8 种", 8, matrix.size)
        matrix.forEach { (args, expected) ->
            val (fine, coarse, background) = args
            assertEquals(
                "fine=$fine coarse=$coarse background=$background",
                expected,
                LocationPermissionRules.resolveStatus(fine, coarse, background),
            )
        }
    }

    @Test
    fun `只看粗定位不能被判成已就绪`() {
        // 缺陷回归锚点：旧实现 (FINE||COARSE, background=true) 直接给 GRANTED
        val status = LocationPermissionRules.resolveStatus(
            fineGranted = false,
            coarseGranted = true,
            backgroundGranted = true,
        )
        assertNotEquals(LocationPermissionStatus.GRANTED, status)
        assertEquals(LocationPermissionStatus.COARSE_ONLY, status)
    }

    @Test
    fun `大致位置的优先级高于缺后台定位`() {
        // 两者同时成立时先修精度：后台授权了也采不到可用点
        assertEquals(
            LocationPermissionStatus.COARSE_ONLY,
            LocationPermissionRules.resolveStatus(false, true, false),
        )
        assertEquals(
            LocationPermissionStatus.MISSING_BACKGROUND,
            LocationPermissionRules.resolveStatus(true, true, false),
        )
    }

    @Test
    fun `两种前台权限都没有时无论后台如何都判未授权`() {
        // 优先级最高：NOT_GRANTED 压过一切（含 background=true 的残留授权）
        assertEquals(
            LocationPermissionStatus.NOT_GRANTED,
            LocationPermissionRules.resolveStatus(false, false, true),
        )
    }

    @Test
    fun `枚举里确有粗定位态且可用于穷举`() {
        val names = LocationPermissionStatus.entries.map { it.name }
        assertEquals(4, names.size)
        assertEquals(
            listOf("GRANTED", "MISSING_BACKGROUND", "COARSE_ONLY", "NOT_GRANTED"),
            names,
        )
    }
}
