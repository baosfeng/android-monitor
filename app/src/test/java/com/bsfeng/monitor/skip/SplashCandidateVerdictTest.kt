package com.bsfeng.monitor.skip

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 点击前判据闸门（纯函数，无需 Robolectric）。
 *
 * 核心契约：**默认拒绝** —— 没有"这是开屏广告"的证据就不点。
 * 反例来自真机（2026-11-02 小红书详情页连续误判），正例来自 QQ阅读真实开屏快照结构
 * （`splash_root > TextView[text="跳过5"]`，父链含 `ywad_gdt_splash_container`）。
 */
class SplashCandidateVerdictTest {

    private val grace = SkipConfig.SPLASH_WORDING_GRACE_MS
    private val textGrace = SkipConfig.SPLASH_TEXT_ONLY_GRACE_MS

    private fun candidate(
        text: String? = null,
        desc: String? = null,
        viewId: String? = null,
        ancestors: List<String> = emptyList(),
        adMarker: Boolean = false,
        elapsedMs: Long = 0L
    ) = SplashCandidate(
        text = text,
        contentDescription = desc,
        viewId = viewId,
        ancestorViewIds = ancestors,
        screenHasAdMarker = adMarker,
        elapsedSinceEnterMs = elapsedMs
    )

    private fun judge(c: SplashCandidate) = SplashCandidateVerdict.judge(c, grace)

    // ==================== 反例（本次要修的问题） ====================

    @Test
    fun `详情页的跳过此步即使极早期也拒绝`() {
        val v = judge(candidate(text = "跳过此步", elapsedMs = 100L))
        assertFalse("语义词不在白名单，多早都不能点：$v", v.accepted)
        assertTrue("拒绝原因要能定位", v.reason.contains("白名单"))
    }

    @Test
    fun `引导类文案在宽限窗内也拒绝`() {
        listOf("跳过引导", "跳过设置", "跳过登录", "跳过这一步").forEach {
            val v = judge(candidate(text = it, elapsedMs = 0L))
            assertFalse("必须拒绝：$it → $v", v.accepted)
        }
    }

    @Test
    fun `无声明的整词跳过超过宽限窗即拒绝`() {
        val v = judge(candidate(text = "跳过", elapsedMs = textGrace + 1))
        assertFalse("无广告语境的整词跳过不得越过宽限窗放行：$v", v.accepted)
        assertTrue("原因需带具体时长与阈值：" + v.reason, v.reason.contains("超过短宽限窗"))
    }

    @Test
    fun `纯文本候选的宽限窗比结构候选更短`() {
        assertTrue(
            "纯文本窗内最后一毫秒仍放行",
            judge(candidate(text = "跳过", elapsedMs = textGrace)).accepted
        )
        assertFalse("纯文本越界即拒绝", judge(candidate(text = "跳过", elapsedMs = textGrace + 1)).accepted)
        assertTrue(
            "纯结构候选可用更长的窗",
            judge(candidate(viewId = "com.demo:id/btn_skip", elapsedMs = grace)).accepted
        )
        assertTrue("纯文本窗必须明显短于结构窗（否则残留误判面没收敛）", textGrace < grace)
    }

    @Test
    fun `倒计时不是独立接受条件`() {
        // 「跳过 1」在多步骤向导的第 1 步真实存在，倒计时本身不构成广告证据
        val early = judge(candidate(text = "跳过 1", elapsedMs = 100L))
        assertTrue("极早期仍可放行，但理由是短宽限窗而不是倒计时", early.accepted)
        assertTrue("理由必须是宽限窗：" + early.reason, early.reason.contains("短宽限窗"))

        val late = judge(candidate(text = "跳过 1", elapsedMs = textGrace + 1))
        assertFalse("超过短宽限窗后，光有倒计时不足以放行：" + late.reason, late.accepted)
    }

    @Test
    fun `倒计时叠加广告标识时作为加分证据`() {
        val v = judge(candidate(text = "跳过 5", adMarker = true, elapsedMs = 20_000L))
        assertTrue(v.accepted)
        assertTrue("结论里应体现倒计时是附加证据：" + v.reason, v.reason.contains("倒计时"))
    }

    // ==================== 正例：真实开屏结构必须通过 ====================

    @Test
    fun `首帧出现的跳过N文本放行`() {
        val v = judge(candidate(text = "跳过5", elapsedMs = 200L))
        assertTrue("冷启动首帧的跳过N必须放行：$v", v.accepted)
    }

    @Test
    fun `跳过广告N文本`() {
        assertTrue(judge(candidate(text = "跳过广告 5")).accepted)
        assertTrue(judge(candidate(text = "跳過廣告5")).accepted)
    }

    @Test
    fun `Skip Ad 文本`() {
        assertTrue(judge(candidate(text = "Skip Ad")).accepted)
        assertTrue(judge(candidate(text = "Skip 5")).accepted)
    }

    @Test
    fun `日历的 countdown_container 不算广告容器`() {
        // 真机 2026-11-05：日历倒数日组件的 countdown_container 被当成广告容器放行并落库 AD_SKIPPED
        val v = judge(
            candidate(
                viewId = "com.android.calendar:id/countdown_container",
                ancestors = listOf(
                    "com.android.calendar:id/countdown_container",
                    "com.android.calendar:id/main_content"
                ),
                elapsedMs = 100L
            )
        )
        assertFalse("倒计时命名是通用 UI 组件名，无广告标识时连宽限窗也不给：$v", v.accepted)
        assertTrue("拒绝原因要能看出弱证据未生效：" + v.reason, v.reason.contains("倒计时"))
        assertTrue("无文案时还要求跳过语义：" + v.reason, v.reason.contains("跳过语义"))
    }

    @Test
    fun `countdown 与 splash 同链时强证据仍然生效`() {
        val v = judge(
            candidate(
                viewId = "com.demo:id/count_down_skip",
                ancestors = listOf("com.demo:id/splash_container"),
                elapsedMs = 5_000L
            )
        )
        assertTrue("强证据在，倒计时命名不影响放行：$v", v.accepted)
        assertTrue("结论应指出命中的广告容器：" + v.reason, v.reason.contains("广告容器"))
    }

    @Test
    fun `平安好车主真实结构：splash_time_tv 的跳过 4s`() {
        val v = judge(
            candidate(
                text = "跳过 4s",
                ancestors = listOf("com.pingan.carowner:id/splash_time_tv"),
                elapsedMs = 8_000L
            )
        )
        assertTrue("真实开屏页必须放行：$v", v.accepted)
        assertTrue(v.reason.contains("广告容器"))
    }

    @Test
    fun `QQ阅读真实结构：splash_root 内的跳过5`() {
        // 真机快照（docs/开屏跳过/规则实测记录.md §3）：TextView[text="跳过5"] 位于
        // splash_root > splash_container > ywad_gdt_splash_container 之内
        val v = judge(
            candidate(
                text = "跳过5",
                viewId = "com.qq.reader:id/skip",
                ancestors = listOf(
                    "com.qq.reader:id/ywad_gdt_splash_container",
                    "com.qq.reader:id/splash_container",
                    "com.qq.reader:id/splash_root"
                ),
                elapsedMs = 2_000L
            )
        )
        assertTrue("真实开屏页必须放行：$v", v.accepted)
    }

    @Test
    fun `同屏广告标识放行晚到的整词跳过`() {
        val v = judge(candidate(text = "跳过", adMarker = true, elapsedMs = 9_000L))
        assertTrue("有广告标识时不受宽限窗限制：$v", v.accepted)
        assertTrue(v.reason.contains("同屏"))
    }

    @Test
    fun `广告容器内的整词跳过放行`() {
        val v = judge(
            candidate(
                text = "跳过",
                ancestors = listOf("com.demo:id/ksad_splash_container"),
                elapsedMs = 8_000L
            )
        )
        assertTrue("容器证据等同广告语境：$v", v.accepted)
    }

    // ==================== 纯结构候选（无文案，靠 view-id） ====================

    @Test
    fun `无文案时由广告 SDK viewId 放行`() {
        val v = judge(candidate(viewId = "com.demo:id/tt_splash_skip_btn", elapsedMs = 5_000L))
        assertTrue("ID 自带广告 SDK 命名：$v", v.accepted)
    }

    @Test
    fun `无文案且无广告线索超过宽限窗则拒绝`() {
        val v = judge(candidate(viewId = "com.demo:id/btn_skip", elapsedMs = grace + 1))
        assertFalse("泛化 skip ID 不算广告证据：$v", v.accepted)
    }

    @Test
    fun `无文案但在宽限窗内放行`() {
        assertTrue(judge(candidate(viewId = "com.demo:id/btn_skip", elapsedMs = 500L)).accepted)
    }

    // ==================== 边界 ====================

    @Test
    fun `描述里带跳过与文本等价`() {
        assertTrue(judge(candidate(desc = "跳过广告")).accepted)
        assertFalse(judge(candidate(desc = "跳过引导", elapsedMs = 0L)).accepted)
    }

    @Test
    fun `文案优先于描述`() {
        // 文本合规 + 描述是广告 → 走文本白名单后仍由广告字样放行
        assertTrue(judge(candidate(text = "跳过", desc = "跳过广告")).accepted)
    }

    @Test
    fun `负时长不能借宽限窗放行`() {
        assertFalse("时钟异常时不得误放行", judge(candidate(text = "跳过", elapsedMs = -5_000L)).accepted)
    }

    @Test
    fun `空白文案按无文案处理并受宽限窗约束`() {
        assertFalse(judge(candidate(text = "   ", elapsedMs = grace + 1)).accepted)
    }
}
