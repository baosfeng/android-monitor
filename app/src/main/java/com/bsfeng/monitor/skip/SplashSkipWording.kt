package com.bsfeng.monitor.skip

// 职责：开屏跳过的「语义词白名单」—— 只有明确表示"跳过开屏广告"的文案才算候选（纯函数，无 Android 依赖）。

/**
 * 开屏跳过文案白名单（默认拒绝，不是默认接受）。
 *
 * ## 为什么用白名单而不是关键词
 * 全局规则用 `[text*="跳过"]` 做包含匹配，正常页面里同样存在大量含"跳过"的文案。
 * 真机证据（2026-11-02，小米 14）：小红书 17:33:58~17:34:02 连续多条命中全局规则，
 * 复核结果 `confirmed=false`，用户当时在详情页正常浏览 —— 命中的是导航/引导类文案，
 * 不是开屏跳过按钮。
 *
 * 因此这里**正向枚举**所有可接受的写法。已覆盖的真实写法：
 *
 * | 写法 | 例子 |
 * |---|---|
 * | 跳过 | 跳过 / 跳過 |
 * | 跳过 + 秒数 | 跳过5 / 跳过 5 / 跳过5秒 / 跳过 5s |
 * | 跳过 + 广告 | 跳过广告 / 跳过广告5 / 跳过广告 5秒 |
 * | 倒计时在前 | 5s跳过 / 5|跳过 / 5·跳过 |
 * | 英文 | Skip / Skip Ad / Skip 5 / Skip Ad 5s |
 * | 关闭广告 | 关闭广告 / 关闭广告5 |
 *
 * 反例（一律拒绝）：跳过此步 / 跳过引导 / 跳过设置 / 跳过登录 / 跳过这一步 / 跳过教程。
 */
internal object SplashSkipWording {

    /** 归一化后的最大长度：超长文案不可能是开屏按钮（Splash 按钮文案普遍 2~8 字） */
    private const val MAX_LEN = 16

    /** 全角数字 → 半角（部分 App 的倒计时用全角数字） */
    private const val FULL_WIDTH_DIGITS = "０１２３４５６７８９"

    /** 可选的前置倒计时："5" / "5s" / "5秒" / "5|" / "5·" / "5:" */
    private const val COUNT_PREFIX = "(\\d{1,2}\\s*(?:s|秒)?\\s*[|/·:：]?\\s*)?"

    /** 可选的后置秒数单位 */
    private const val SECOND_SUFFIX = "(?:秒|s|sec|secs|second|seconds)?"

    private val CN_SKIP = Regex(
        "^" + COUNT_PREFIX + "(跳过|跳過)\\s*(广告|廣告)?\\s*(\\d{1,2})?\\s*" + SECOND_SUFFIX + "$",
        RegexOption.IGNORE_CASE
    )

    private val CN_CLOSE = Regex(
        "^" + COUNT_PREFIX + "(关闭广告|關閉廣告)\\s*(\\d{1,2})?\\s*" + SECOND_SUFFIX + "$",
        RegexOption.IGNORE_CASE
    )

    private val EN_SKIP = Regex(
        "^" + COUNT_PREFIX + "skip(\\s*ad)?\\s*(\\d{1,2})?\\s*" + SECOND_SUFFIX + "$",
        RegexOption.IGNORE_CASE
    )

    /**
     * 文案是否是合法的「跳过开屏广告」措辞。
     *
     * @param raw 节点 text / contentDescription
     * @return true = 属于白名单（仍须通过 [SplashCandidateVerdict] 的广告语境判定）
     */
    fun isSplashSkipWording(raw: String?): Boolean {
        val text = normalize(raw)
        if (text.isEmpty() || text.length > MAX_LEN) return false
        return CN_SKIP.matches(text) || CN_CLOSE.matches(text) || EN_SKIP.matches(text)
    }

    /** 文案里是否自带广告标识（"跳过广告"这类措辞本身就是广告语境的强证据） */
    fun mentionsAd(raw: String?): Boolean {
        val text = normalize(raw)?.lowercase() ?: return false
        if (text.contains("广告") || text.contains("廣告")) return true
        return Regex("(^|[^a-z])ad([^a-z]|$)").containsMatchIn(text)
    }

    /** 去掉首尾空白并把全角数字换成半角 */
    private fun normalize(raw: String?): String {
        val trimmed = raw?.trim() ?: return ""
        if (trimmed.isEmpty()) return ""
        val sb = StringBuilder(trimmed.length)
        for (ch in trimmed) {
            val idx = FULL_WIDTH_DIGITS.indexOf(ch)
            sb.append(if (idx >= 0) ('0' + idx) else ch)
        }
        return sb.toString()
    }
}
