package com.bsfeng.monitor.skip

// 职责：把「纯函数白名单 + 上下文判据」接到匹配器，产出可落库证据与五要素拒绝/放行日志（不碰 AccessibilityNodeInfo）。

import com.bsfeng.monitor.util.DebugConfig

/**
 * 判据的对外入口（[SkipRuleMatcher] 调用），同时负责产出**落库证据**与**可追溯日志**。
 *
 * 证据为什么必须落库：DB 里原本只有 `selector` 字符串，误判后无法回溯"点的是什么字、
 * 被哪条判据放行"（2026-11-02 小红书误判就是这样查不出原因的）。因此判定结论与命中文案
 * 一起写进 `detail`（[SplashEvidence]）。
 *
 * 日志为什么必须带五要素：判据拒绝时用户完全无感（不弹跳过、不落记录），
 * 「没有广告」与「有广告但被误杀」在用户视角无法区分；只给原因不给包名/页面的话，
 * 排查时得靠时间戳去对齐隔壁「进入 App」日志，一次冷启动几十条根本对不上。
 */
internal object SplashGate {

    /** 落库证据（写进 `AD_SKIPPED` / `AD_SKIP_UNCONFIRMED` 的 detail） */
    data class SplashEvidence(
        /** 命中节点文案（截断，避免整段页面文字入库） */
        val matchedText: String,
        /** 判据结论串（含具体数值） */
        val verdictReason: String
    )

    /**
     * 判定所处的页面上下文：日志的 pkg / activity 两要素（elapsed 取自 [SplashCandidate]）。
     *
     * @param packageName 前台包名
     * @param activity 当前 Activity（可能为空：widget 类名被过滤掉时）
     */
    data class ScreenContext(val packageName: String, val activity: String)

    /** 文案截断长度：够看出是"跳过此步"还是"跳过5"，又不至于把正文塞进事件表 */
    private const val MAX_EVIDENCE_TEXT = 32

    /** 日志里 reason 的上限（结论串本身很短，这里只是防御） */
    private const val MAX_LOG_REASON = 64

    /** 日志前缀：`grep 'SkipEngine: 点击前判据拒绝'` 即可捞出全部误杀记录 */
    private const val LOG_PREFIX = "SkipEngine: 点击前判据"

    /**
     * 复核一条候选。
     *
     * @return 放行时返回证据；拒绝返回 null（已写五要素调试日志）
     */
    fun check(
        candidate: SplashCandidate,
        ruleName: String,
        selector: String,
        context: ScreenContext,
        graceMs: Long = SkipConfig.SPLASH_WORDING_GRACE_MS,
        textOnlyGraceMs: Long = SkipConfig.SPLASH_TEXT_ONLY_GRACE_MS
    ): SplashEvidence? {
        val verdict = SplashCandidateVerdict.judge(candidate, graceMs, textOnlyGraceMs)
        val text = evidenceText(candidate)
        if (!verdict.accepted) {
            DebugConfig.log(LOG_PREFIX + "拒绝" + fields(context, candidate, text, verdict.reason, ruleName, selector))
            return null
        }
        DebugConfig.log(LOG_PREFIX + "放行" + fields(context, candidate, text, verdict.reason, ruleName, selector))
        return SplashEvidence(matchedText = text, verdictReason = verdict.reason.take(MAX_LOG_REASON))
    }

    /**
     * 日志字段串：` · pkg= · activity= · text= · reason= · elapsed= · 规则= · 选择器=`。
     *
     * 顺序固定，便于 `grep '点击前判据拒绝' | grep <包名>` 直接定位单次误杀。
     */
    private fun fields(
        context: ScreenContext,
        candidate: SplashCandidate,
        text: String,
        reason: String,
        ruleName: String,
        selector: String
    ): String =
        " · pkg=" + context.packageName +
            " · activity=" + context.activity.ifEmpty { "?" } +
            " · text=" + text.ifEmpty { "?" } +
            " · reason=" + reason.take(MAX_LOG_REASON) +
            " · elapsed=" + candidate.elapsedSinceEnterMs + "ms" +
            " · 规则=" + ruleName +
            " · 选择器=" + selector

    /** 证据文案：文本/描述优先，纯结构候选回落 view-id（否则证据是空的，等于没记） */
    private fun evidenceText(candidate: SplashCandidate): String {
        val raw = candidate.rawText ?: candidate.viewId ?: candidate.ancestorViewIds.firstOrNull().orEmpty()
        val text = raw.trim()
        return if (text.length <= MAX_EVIDENCE_TEXT) text else text.take(MAX_EVIDENCE_TEXT)
    }
}
