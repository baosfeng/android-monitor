package com.bsfeng.monitor.skip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 开屏跳过文案白名单（纯函数，无需 Robolectric）。
 *
 * 白名单是**默认拒绝**的第一道闸：只有明确表示"跳过开屏广告"的写法才通过。
 * 反例全部来自真实页面（小红书详情页/引导浮层）。
 */
class SplashSkipWordingTest {

    // ==================== 正常路径：真实开屏写法必须通过 ====================

    @Test
    fun `简繁整词跳过通过`() {
        assertTrue(SplashSkipWording.isSplashSkipWording("跳过"))
        assertTrue(SplashSkipWording.isSplashSkipWording("跳過"))
        assertTrue(SplashSkipWording.isSplashSkipWording(" 跳过 "))
    }

    @Test
    fun `跳过加秒数通过`() {
        listOf("跳过5", "跳过 5", "跳过5秒", "跳过 5 秒", "跳过15", "跳过 5s", "跳过5S").forEach {
            assertTrue("应通过：$it", SplashSkipWording.isSplashSkipWording(it))
        }
    }

    @Test
    fun `跳过广告写法通过`() {
        listOf("跳过广告", "跳過廣告", "跳过广告5", "跳过广告 5秒", "跳过广告 15s").forEach {
            assertTrue("应通过：$it", SplashSkipWording.isSplashSkipWording(it))
        }
    }

    @Test
    fun `倒计时在前的写法通过`() {
        listOf("5跳过", "5s跳过", "5秒跳过", "5|跳过", "5·跳过", "5 跳过").forEach {
            assertTrue("应通过：$it", SplashSkipWording.isSplashSkipWording(it))
        }
    }

    @Test
    fun `英文写法通过`() {
        listOf("Skip", "skip", "SKIP", "Skip Ad", "skip ad", "Skip 5", "Skip Ad 5s", "5s Skip").forEach {
            assertTrue("应通过：$it", SplashSkipWording.isSplashSkipWording(it))
        }
    }

    @Test
    fun `全角数字通过`() {
        assertTrue("全角数字是真实写法（部分 ROM 倒计时）", SplashSkipWording.isSplashSkipWording("跳过５"))
        assertTrue(SplashSkipWording.isSplashSkipWording("跳过 ５ 秒"))
    }

    @Test
    fun `关闭广告通过`() {
        assertTrue(SplashSkipWording.isSplashSkipWording("关闭广告"))
        assertTrue(SplashSkipWording.isSplashSkipWording("關閉廣告"))
        assertTrue(SplashSkipWording.isSplashSkipWording("关闭广告5"))
    }

    // ==================== 反例：正常页面的导航/引导文案必须拒绝 ====================

    @Test
    fun `导航与引导类跳过文案一律拒绝`() {
        listOf(
            "跳过此步", "跳过引导", "跳过设置", "跳过登录", "跳过这一步",
            "跳过教程", "跳过验证", "跳过介绍", "跳过新手引导", "跳过该步骤",
            "跳过暂不设置", "跳过并进入"
        ).forEach {
            assertFalse("必须拒绝：$it", SplashSkipWording.isSplashSkipWording(it))
        }
    }

    @Test
    fun `繁体引导文案同样拒绝`() {
        listOf("跳過此步", "跳過引導", "跳過設定").forEach {
            assertFalse("必须拒绝：$it", SplashSkipWording.isSplashSkipWording(it))
        }
    }

    @Test
    fun `英文引导文案拒绝`() {
        listOf("Skip tutorial", "Skip this step", "Skip setup", "Skip intro", "Skip login").forEach {
            assertFalse("必须拒绝：$it", SplashSkipWording.isSplashSkipWording(it))
        }
    }

    @Test
    fun `不相关内容拒绝`() {
        listOf("首页", "确定", "广告", "", " ", "跳过跳过跳过跳过跳过跳过跳过跳过跳过跳过").forEach {
            assertFalse("必须拒绝：$it", SplashSkipWording.isSplashSkipWording(it))
        }
    }

    // ==================== 边界条件 ====================

    @Test
    fun `null 与空串拒绝`() {
        assertFalse(SplashSkipWording.isSplashSkipWording(null))
        assertFalse(SplashSkipWording.isSplashSkipWording(""))
        assertFalse(SplashSkipWording.isSplashSkipWording("   "))
    }

    @Test
    fun `超长文案拒绝`() {
        assertFalse("开屏按钮不可能这么长", SplashSkipWording.isSplashSkipWording("跳过" + "啊".repeat(20)))
    }

    @Test
    fun `三位数倒计时拒绝`() {
        assertFalse("开屏倒计时不超过两位数", SplashSkipWording.isSplashSkipWording("跳过100"))
    }

    // ==================== 辅助判定 ====================

    @Test
    fun `广告字样判定`() {
        assertTrue(SplashSkipWording.mentionsAd("跳过广告"))
        assertTrue(SplashSkipWording.mentionsAd("Skip Ad"))
        assertFalse("子串不算：load 里没有独立的 ad", SplashSkipWording.mentionsAd("loading"))
        assertFalse(SplashSkipWording.mentionsAd("跳过5"))
    }

}
