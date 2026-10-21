package com.bsfeng.monitor.ui

import android.view.HapticFeedbackConstants
import com.bsfeng.monitor.ui.foundation.HapticConfig
import com.bsfeng.monitor.ui.foundation.HapticStore
import com.bsfeng.monitor.ui.foundation.HapticStrength
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.constantFor
import com.bsfeng.monitor.ui.foundation.shouldPerformHaptic
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 触觉体系回归：档 → 常量映射（各 SDK 分支）、三重闸门、强度档提升映射、配置落盘口径。
 *
 * 全部是纯 JVM 用例（不引 Robolectric）：被测的都是纯函数 + [HapticConfig] 的内存后端，
 * 因此不依赖设备/模拟器，也不会因为系统设置不同而变绿或变红。
 */
class HapticsTest {

    // ── 1 · 档 → 系统常量（SDK 29 vs 30+） ──

    @Test
    fun `API 29 全部档位只用 29 上存在的常量`() {
        // CONFIRM / REJECT 是 API 30 常量，29 上必须回落，否则 lint InlinedApi + 旧机无反馈
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, constantFor(HapticTone.LIGHT, 29))
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, constantFor(HapticTone.MEDIUM, 29))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, constantFor(HapticTone.HEAVY, 29))
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, constantFor(HapticTone.SUCCESS, 29))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, constantFor(HapticTone.WARNING, 29))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, constantFor(HapticTone.REJECT, 29))
    }

    @Test
    fun `API 30 起 SUCCESS 走 CONFIRM、REJECT 走 REJECT`() {
        assertEquals(HapticFeedbackConstants.CONFIRM, constantFor(HapticTone.SUCCESS, 30))
        assertEquals(HapticFeedbackConstants.REJECT, constantFor(HapticTone.REJECT, 30))
    }

    @Test
    fun `API 30 起其余档位映射不变`() {
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, constantFor(HapticTone.LIGHT, 30))
        assertEquals(HapticFeedbackConstants.KEYBOARD_TAP, constantFor(HapticTone.MEDIUM, 30))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, constantFor(HapticTone.HEAVY, 30))
        assertEquals(HapticFeedbackConstants.LONG_PRESS, constantFor(HapticTone.WARNING, 30))
    }

    @Test
    fun `版本分支在 29 与 30 之间切换且更高版本沿用 30 的映射`() {
        // 边界：29（回落）→ 30（新常量）；36 / 37 与 30 同档
        assertEquals(HapticFeedbackConstants.VIRTUAL_KEY, constantFor(HapticTone.SUCCESS, 29))
        assertEquals(HapticFeedbackConstants.CONFIRM, constantFor(HapticTone.SUCCESS, 30))
        assertEquals(HapticFeedbackConstants.CONFIRM, constantFor(HapticTone.SUCCESS, 36))
        assertEquals(HapticFeedbackConstants.CONFIRM, constantFor(HapticTone.SUCCESS, 37))
        assertEquals(HapticFeedbackConstants.REJECT, constantFor(HapticTone.REJECT, 37))
    }

    @Test
    fun `六档都映射到已知合法常量`() {
        // 判定口径是「落在已知常量集合内」，不是「非零」：
        // HapticFeedbackConstants.LONG_PRESS 的官方值就是 0（见 android.view.HapticFeedbackConstants），
        // 0 是合法的长按常量，不是「无效值」哨兵。用「非零」当判据会把正确映射误判为失败。
        HapticTone.entries.forEach { tone ->
            listOf(29, 30, 37).forEach { sdk ->
                assertTrue(
                    "$tone @ $sdk 映射到未知常量 ${constantFor(tone, sdk)}",
                    constantFor(tone, sdk) in KNOWN_HAPTIC_CONSTANTS,
                )
            }
        }
        // LIGHT ≠ HEAVY：否则强度档退化失效
        assertTrue(constantFor(HapticTone.LIGHT, 30) != constantFor(HapticTone.HEAVY, 30))
    }

    // ── 2 · 三重闸门 ──

    @Test
    fun `三重闸门任意一关都不震`() {
        // 系统触觉总开关关闭（静音 / 用户关触觉）
        assertFalse(shouldPerformHaptic(systemHapticsEnabled = false, viewHapticsEnabled = true, appHapticsEnabled = true))
        // View 自身 isHapticFeedbackEnabled = false
        assertFalse(shouldPerformHaptic(systemHapticsEnabled = true, viewHapticsEnabled = false, appHapticsEnabled = true))
        // 应用内开关关闭
        assertFalse(shouldPerformHaptic(systemHapticsEnabled = true, viewHapticsEnabled = true, appHapticsEnabled = false))
    }

    @Test
    fun `三重闸门全开才震`() {
        assertTrue(shouldPerformHaptic(systemHapticsEnabled = true, viewHapticsEnabled = true, appHapticsEnabled = true))
    }

    @Test
    fun `三关全关也不震（不因为多数为真就放行）`() {
        assertFalse(shouldPerformHaptic(systemHapticsEnabled = false, viewHapticsEnabled = false, appHapticsEnabled = false))
        assertFalse(shouldPerformHaptic(systemHapticsEnabled = false, viewHapticsEnabled = false, appHapticsEnabled = true))
        assertFalse(shouldPerformHaptic(systemHapticsEnabled = false, viewHapticsEnabled = true, appHapticsEnabled = false))
        assertFalse(shouldPerformHaptic(systemHapticsEnabled = true, viewHapticsEnabled = false, appHapticsEnabled = false))
    }

    // ── 3 · 强度档提升映射 ──

    @Test
    fun `STRONG 把轻与中抬一档、最重不超`() {
        assertEquals(HapticTone.MEDIUM, HapticStrength.STRONG.toneFor(HapticTone.LIGHT))
        assertEquals(HapticTone.HEAVY, HapticStrength.STRONG.toneFor(HapticTone.MEDIUM))
        assertEquals(HapticTone.HEAVY, HapticStrength.STRONG.toneFor(HapticTone.HEAVY))
    }

    @Test
    fun `LIGHT 把重与中降一档、最轻不降`() {
        assertEquals(HapticTone.MEDIUM, HapticStrength.LIGHT.toneFor(HapticTone.HEAVY))
        assertEquals(HapticTone.LIGHT, HapticStrength.LIGHT.toneFor(HapticTone.MEDIUM))
        assertEquals(HapticTone.LIGHT, HapticStrength.LIGHT.toneFor(HapticTone.LIGHT))
    }

    @Test
    fun `NORMAL 原样输出全部六档`() {
        HapticTone.entries.forEach { tone ->
            assertEquals(tone, HapticStrength.NORMAL.toneFor(tone))
        }
    }

    @Test
    fun `语义档不受强度档影响`() {
        // SUCCESS / WARNING / REJECT 承载「结果」而非「力度」，强度档不得改写
        listOf(HapticTone.SUCCESS, HapticTone.WARNING, HapticTone.REJECT).forEach { tone ->
            HapticStrength.entries.forEach { strength ->
                assertEquals("$tone @ $strength", tone, strength.toneFor(tone))
            }
        }
    }

    @Test
    fun `强度档作用后再映射常量仍落在合法值`() {
        HapticStrength.entries.forEach { strength ->
            HapticTone.entries.forEach { tone ->
                val c = constantFor(strength.toneFor(tone), 30)
                assertTrue(
                    "$strength + $tone 映射到未知常量 $c",
                    c in KNOWN_HAPTIC_CONSTANTS,
                )
            }
        }
    }

    // ── 4 · 应用内配置（内存后端，验证默认值与落盘口径） ──

    @Test
    fun `默认开启且强度为 NORMAL`() {
        HapticConfig.attachStore(FakeHapticStore())
        assertTrue(HapticConfig.isEnabled)
        assertEquals(HapticStrength.NORMAL, HapticConfig.currentStrength)
    }

    @Test
    fun `已存值优先于默认值`() {
        HapticConfig.attachStore(FakeHapticStore(enabled = false, strength = HapticStrength.STRONG))
        assertFalse(HapticConfig.isEnabled)
        assertEquals(HapticStrength.STRONG, HapticConfig.currentStrength)
    }

    @Test
    fun `改开关与强度立即生效并落盘`() {
        val store = FakeHapticStore()
        HapticConfig.attachStore(store)

        assertTrue(HapticConfig.setEnabled(false))
        assertFalse(HapticConfig.isEnabled)
        assertEquals(false, store.readEnabled())

        assertTrue(HapticConfig.setStrength(HapticStrength.STRONG))
        assertEquals(HapticStrength.STRONG, HapticConfig.currentStrength)
        assertEquals(HapticStrength.STRONG, store.readStrength())
    }

    @Test
    fun `重复设置同一值不判为变更`() {
        val store = FakeHapticStore()
        HapticConfig.attachStore(store)
        // 默认值 = 开启 + NORMAL，再设一次应返回 false（调用方据此决定是否补震动）
        assertFalse(HapticConfig.setEnabled(true))
        assertFalse(HapticConfig.setStrength(HapticStrength.NORMAL))
    }

    @Test
    fun `恢复默认回到开启加 NORMAL`() {
        HapticConfig.attachStore(FakeHapticStore(enabled = false, strength = HapticStrength.LIGHT))
        HapticConfig.resetToDefault()
        assertTrue(HapticConfig.isEnabled)
        assertEquals(HapticStrength.NORMAL, HapticConfig.currentStrength)
    }

    private class FakeHapticStore(
        private var enabled: Boolean? = null,
        private var strength: HapticStrength? = null,
    ) : HapticStore {
        override fun readEnabled(): Boolean? = enabled

        override fun readStrength(): HapticStrength? = strength

        override fun writeEnabled(enabled: Boolean) {
            this.enabled = enabled
        }

        override fun writeStrength(strength: HapticStrength) {
            this.strength = strength
        }
    }

    private companion object {
        /**
         * 本 App 可能映射到的全部 [HapticFeedbackConstants] 常量。
         *
         * 注意 `LONG_PRESS == 0`：Android 官方定义就是 0，0 在这里是**合法值**。
         * 判据取「属于本集合」，不能取「非零」。
         */
        val KNOWN_HAPTIC_CONSTANTS = setOf(
            HapticFeedbackConstants.LONG_PRESS,      // 0
            HapticFeedbackConstants.VIRTUAL_KEY,     // 1
            HapticFeedbackConstants.KEYBOARD_TAP,    // 3
            HapticFeedbackConstants.CONFIRM,         // 16（API 30+）
            HapticFeedbackConstants.REJECT,          // 17（API 30+）
        )
    }
}
