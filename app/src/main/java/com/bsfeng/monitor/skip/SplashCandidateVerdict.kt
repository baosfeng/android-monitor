package com.bsfeng.monitor.skip

// 职责：跳过候选的点击前判据闸门（纯函数）—— 默认拒绝，只有拿到"这是开屏广告"的证据才放行。

/**
 * 一次跳过候选的判定输入（由 [SplashAdContextProbe] 从无障碍节点采集，本类不含 Android 依赖）。
 *
 * @param text 节点文本
 * @param contentDescription 节点描述
 * @param viewId 节点 viewIdResourceName（可能为 null）
 * @param ancestorViewIds 自命中节点向上到根的 viewId 链（不含自身）
 * @param screenHasAdMarker 同屏是否读到广告标识文本（广告/推广/赞助/AD）
 * @param elapsedSinceEnterMs 自「切换到当前 App」起的毫秒数
 */
internal data class SplashCandidate(
    val text: String? = null,
    val contentDescription: String? = null,
    val viewId: String? = null,
    val ancestorViewIds: List<String> = emptyList(),
    val screenHasAdMarker: Boolean = false,
    val elapsedSinceEnterMs: Long = 0L
) {
    /** 判定用的原始文案：文本优先，其次描述 */
    val rawText: String? get() = text?.takeIf { it.isNotBlank() } ?: contentDescription?.takeIf { it.isNotBlank() }

    /** 纯结构候选：没有任何可读文案，语义全靠 view-id / 容器承担 */
    val isStructural: Boolean get() = rawText == null
}

/**
 * 判定结果（[reason] 直接进 DebugConfig 日志与落库 detail，便于真机排查"为什么被放行/拒绝"）。
 *
 * @param accepted 是否允许点击
 * @param reason 判定依据（含具体数值/文案）
 */
internal class SplashVerdict(val accepted: Boolean, val reason: String) {
    companion object {
        fun accept(reason: String) = SplashVerdict(true, reason)
        fun reject(reason: String) = SplashVerdict(false, reason)
    }

    override fun toString(): String = (if (accepted) "ACCEPT:" else "REJECT:") + reason
}

/**
 * 跳过候选的**点击前**判据闸门（默认拒绝）。
 *
 * ## 为什么需要它
 * 全局规则的 `[text*="跳过"]` 是包含匹配，正常页面里同样有含"跳过"的文案。真机证据
 * （2026-11-02）：小红书 17:33:57~17:34:04 连续 5 条命中全局规则（confirmed 2 true / 3 false），
 * 用户当时在小红书详情页正常浏览。时间闸门（`matchTime`）只能限制"什么时候可以点"，
 * 拦不住"点的是不是开屏按钮"。
 *
 * ## 为什么点击后复核救不了（重要）
 * [SkipClickVerifier] 的 `confirmed` 语义是"**同一选择器**是否还匹配得到"，**不是**"广告是否消失"。
 * 点掉一个引导浮层后选择器同样匹配不到 → 判成 true → 记成"跳过成功（已确认广告消失）"。
 * 所以误判防线必须在**点击前**：本类拦的是 true 那侧，A 项（未确认不记成功）拦的是 false 那侧。
 *
 * ## 判据（按顺序）
 * | # | 条件 | 结果 |
 * |---|---|---|
 * | 1 | 有文案但不在 [SplashSkipWording] 白名单 | **拒绝**（跳过此步/跳过引导/跳过设置…） |
 * | 2 | 文案自带"广告"字样 | 接受 |
 * | 3 | 同屏存在广告标识文本 | 接受 |
 * | 4 | 自身或祖先 viewId 命中广告 SDK 命名 | 接受（splash / gdt / ywad / ksad / advert / ad_container / tt_ad） |
 * | 5 | **有倒计时 + 上述任一证据** | 接受（见下"倒计时只是加分项"） |
 * | 6 | 纯结构候选（无文案）且 ID 带跳过语义、在 [graceMs] 内 | 接受 |
 * | 7 | 纯文本候选在 [textOnlyGraceMs] 内 | 接受（更短窗） |
 * | 8 | 其余 | **拒绝** |
 *
 * **倒计时只是加分项，不是充分条件**：「跳过 1」这类文案在多步骤向导里真实存在（第 1 步）。
 * 因此规则 5 要求倒计时**叠加**规则 2~4 的至少一条证据；纯倒计时文案仍走规则 7 的短窗。
 *
 * ## 两个宽限窗（默认拒绝的最后一道口子）
 * - [graceMs]（1.5 秒）：用于**纯结构候选**（靠 viewId 命中），且 ID 必须带跳过语义
 *   （skip / 跳过 / 跳過）—— count_down 这类倒计时命名没有跳过语义，不给宽限（日历首页首帧就有）。
 * - [textOnlyGraceMs]（0.8 秒）：用于「只有合规文案、无任何广告证据」的候选 —— 这是本类
 *   残留的误判面，故意取更短：引导浮层通常要等应用初始化完才绘制，0.8 秒已覆盖冷启动首帧。
 *
 * ## 已知代价（宁可漏跳不可误点）
 * 无广告证据、且 [textOnlyGraceMs] 之后才出现的纯文本"跳过"会被拒 —— 见设计文档「误杀场景」。
 */
internal object SplashCandidateVerdict {

    /** 广告 SDK 容器命名（祖先或自身 viewId）—— 单独命中即可当广告证据 */
    private val AD_CONTAINER_PATTERN = Regex(
        "splash|gdt|ywad|ksad|advert|ad_?container|tt_ad",
        RegexOption.IGNORE_CASE
    )

    /**
     * 倒计时类命名（countdown / count_down）是**弱证据**：单独命中不算广告容器。
     *
     * 倒计时是通用 UI 组件名，日历、倒数日、番茄钟都有。真机 2026-11-05 21:25:04
     * 日历 com.android.calendar 的 countdown_container 被当成广告容器放行并落库 AD_SKIPPED。
     * 它只能叠加文案「广告」字样或同屏广告标识才作证据，而那两条在规则 2/3 已单独放行。
     */
    private val WEAK_AD_CONTAINER_PATTERN = Regex("count_?down", RegexOption.IGNORE_CASE)

    /** viewId 中「独立单词 ad」的边界写法：/ad_xx、ad_banner、_ad_ */
    private val AD_WORD_PATTERN = Regex("(^|[/_])ad([_/]|$)", RegexOption.IGNORE_CASE)

    /**
     * 纯结构候选的「跳过语义」命名：宽限窗只留给跳过控件本身。
     *
     * count / down 不是广告证据，也不是跳过语义——倒计时是通用 UI 组件名（日历倒数日、番茄钟、
     * 抢购倒计时）。全局规则用 count_down 当选择器是为了捞「ID 里带 count_down 的跳过按钮」，
     * 因此无文案时还要求 ID 链里出现跳过语义，否则日历首页首帧就会借宽限窗被放行。
     */
    private val SKIP_SEMANTIC_PATTERN = Regex("skip|跳过|跳過", RegexOption.IGNORE_CASE)

    /** 倒计时数字 */
    private val COUNTDOWN_PATTERN = Regex("\\d")

    fun judge(
        candidate: SplashCandidate,
        graceMs: Long = SkipConfig.SPLASH_WORDING_GRACE_MS,
        textOnlyGraceMs: Long = SkipConfig.SPLASH_TEXT_ONLY_GRACE_MS
    ): SplashVerdict {
        val raw = candidate.rawText

        // 1. 有文案但不在白名单 → 直接拒绝（「跳过此步」「跳过引导」在这里就被挡掉）
        if (raw != null && !SplashSkipWording.isSplashSkipWording(raw)) {
            return SplashVerdict.reject("文案不在开屏跳过白名单（text=" + preview(raw) + "）")
        }

        // 2. 文案自带广告字 → 强证据（"跳过广告"本身就说清了语境）
        if (raw != null && SplashSkipWording.mentionsAd(raw)) {
            return SplashVerdict.accept("文案自带广告标识（" + preview(raw) + "）")
        }

        // 3. 同屏广告标识
        if (candidate.screenHasAdMarker) {
            return SplashVerdict.accept("同屏存在广告标识文本" + countdownSuffix(raw))
        }

        // 4. 广告 SDK 容器（强证据；倒计时命名是弱证据，不在此列）
        adContainerOf(candidate)?.let {
            return SplashVerdict.accept("位于广告容器内（" + it + "）" + countdownSuffix(raw))
        }

        // 5. 倒计时单独出现不再放行（「跳过 1」在多步骤向导里真实存在），
        //    它已在上面作为附加证据拼进 3/4 的结论；此处只做记录，不影响放行。
        // 6/7. 兜底宽限窗：纯结构候选还要求 ID 带跳过语义，纯文本候选用更短的 textOnlyGraceMs
        if (candidate.isStructural) {
            if (candidate.elapsedSinceEnterMs !in 0..graceMs) {
                return SplashVerdict.reject(
                    "纯结构候选无广告语境且已超过宽限窗（" + candidate.elapsedSinceEnterMs + "ms > " +
                        graceMs + "ms）" + weakContainerHint(candidate)
                )
            }
            if (!hasSkipSemantic(candidate)) {
                return SplashVerdict.reject(
                    "纯结构候选无广告语境且 viewId 无跳过语义（" + idPreview(candidate) + "）" +
                        weakContainerHint(candidate)
                )
            }
            return SplashVerdict.accept(
                "纯结构候选 viewId 带跳过语义，在进入 App 后 " + graceMs + "ms 宽限窗内"
            )
        }
        return if (candidate.elapsedSinceEnterMs in 0..textOnlyGraceMs) {
            SplashVerdict.accept(
                "纯文本候选无广告语境，在进入 App 后 " + textOnlyGraceMs + "ms 短宽限窗内"
            )
        } else {
            SplashVerdict.reject(
                "纯文本候选无广告语境且已超过短宽限窗（" + candidate.elapsedSinceEnterMs + "ms > " +
                    textOnlyGraceMs + "ms）" + weakContainerHint(candidate)
            )
        }
    }

    /** 倒计时只是加分证据：结论里带一句，不改判定 */
    private fun countdownSuffix(raw: String?): String =
        if (raw != null && COUNTDOWN_PATTERN.containsMatchIn(raw)) "（且有倒计时）" else ""

    /** 能否作为「跳过控件」：宽限窗只对这种 ID 生效 */
    private fun hasSkipSemantic(candidate: SplashCandidate): Boolean =
        (candidate.ancestorViewIds + listOfNotNull(candidate.viewId))
            .any { SKIP_SEMANTIC_PATTERN.containsMatchIn(it) }

    /** 拒绝理由里带一个 ID（截断），说明是哪个节点没跳过语义 */
    private fun idPreview(candidate: SplashCandidate): String =
        (candidate.viewId ?: candidate.ancestorViewIds.lastOrNull()).orEmpty().take(40)

    /** 弱证据只写进拒绝理由（便于真机回溯"为什么这个 countdown 没放行"），不参与放行 */
    private fun weakContainerHint(candidate: SplashCandidate): String {
        val ids = candidate.ancestorViewIds + listOfNotNull(candidate.viewId)
        return if (ids.any { WEAK_AD_CONTAINER_PATTERN.containsMatchIn(it) }) "（倒计时命名不算广告容器）" else ""
    }

    private fun adContainerOf(candidate: SplashCandidate): String? {
        val ids = candidate.ancestorViewIds + listOfNotNull(candidate.viewId)
        return ids.firstOrNull { id ->
            AD_CONTAINER_PATTERN.containsMatchIn(id) || AD_WORD_PATTERN.containsMatchIn(id)
        }
    }

    /** 日志用短预览（截断，避免把整段页面文案打进日志） */
    private fun preview(raw: String?): String {
        val text = raw?.trim().orEmpty()
        return if (text.length <= 12) text else text.take(12) + "…"
    }
}
