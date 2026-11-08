package com.bsfeng.monitor.ui.quota

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import com.bsfeng.monitor.domain.quota.QuotaRange

/**
 * 用量卡的三套锚点：同一张卡在三个 provider 面板里各叫各的
 * （s18-*-usage / s18-*-range-* / s18-*-models-*）。
 *
 * 为什么单独成文件（基建审计 I4）：这层是「变体 / 范围 → 锚点」的纯映射，
 * 原先写在 [QuotaUsageCard] 的 @Composable 文件里，整个 facade 类被覆盖率排除，
 * 于是这段逻辑既不进分母也没人测。现在它是纯字符串函数（无 Compose 依赖、可纯 JVM 单测），
 * 调用点只剩一行 `Modifier.testTag(…)`。
 *
 * 为什么用花括号体而不是 `= when …`：scripts/check-anchors.mjs 对表达式体函数只取
 * 前 8 行做锚点取证，分支一长后半截会被判「缺失」；花括号体按括号配平整段读取，
 * 锚点字面量才追得到（实测表达式体会丢 s18-legacy-range-* / s18-ds-* 共 9 条）。
 * 锚点值一律写成字面量：账本对账是逐字 grep 的，拼字符串扫不到。
 */
enum class UsageCardVariant { OLLAMA_USD, OLLAMA_LEGACY, DEEPSEEK }

/** 卡片容器锚点。 */
internal fun cardTag(variant: UsageCardVariant): String {
    return when (variant) {
        UsageCardVariant.OLLAMA_USD -> "s18-ollama-usage"
        UsageCardVariant.OLLAMA_LEGACY -> "s18-legacy-usage"
        UsageCardVariant.DEEPSEEK -> "s18-deepseek-usage"
    }
}

/** 标题右侧范围文案锚点（只随范围切换改写，本身不可点）。 */
internal fun rangeLabelTag(variant: UsageCardVariant): String {
    return when (variant) {
        UsageCardVariant.OLLAMA_USD -> "s18-usage-range-label"
        UsageCardVariant.OLLAMA_LEGACY -> "s18-legacy-range-label"
        UsageCardVariant.DEEPSEEK -> "s18-ds-range-label"
    }
}

/** 三个范围项的锚点。 */
internal fun rangeTag(variant: UsageCardVariant, range: QuotaRange): String {
    return when (variant) {
        UsageCardVariant.OLLAMA_USD -> when (range) {
            QuotaRange.LAST_24_HOURS -> "s18-range-24"
            QuotaRange.LAST_7_DAYS -> "s18-range-7"
            QuotaRange.LAST_30_DAYS -> "s18-range-30"
        }
        UsageCardVariant.OLLAMA_LEGACY -> when (range) {
            QuotaRange.LAST_24_HOURS -> "s18-legacy-range-24"
            QuotaRange.LAST_7_DAYS -> "s18-legacy-range-7"
            QuotaRange.LAST_30_DAYS -> "s18-legacy-range-30"
        }
        UsageCardVariant.DEEPSEEK -> when (range) {
            QuotaRange.LAST_24_HOURS -> "s18-ds-range-24"
            QuotaRange.LAST_7_DAYS -> "s18-ds-range-7"
            QuotaRange.LAST_30_DAYS -> "s18-ds-range-30"
        }
    }
}

/**
 * 按模型 Top 3 区块的锚点；旧版计划（OLLAMA_LEGACY）没有按模型明细，返回 null 表示不加锚点。
 *
 * null 而不是空串：空串的 testTag 在语义树里仍是一个「有标识」的节点，
 * 运行时审计会把它当已登记锚点，反而掩盖「这块不该有标识」。
 */
internal fun modelsTag(variant: UsageCardVariant, range: QuotaRange): String? {
    return when (variant) {
        UsageCardVariant.OLLAMA_USD -> when (range) {
            QuotaRange.LAST_24_HOURS -> "s18-models-24"
            QuotaRange.LAST_7_DAYS -> "s18-models-7"
            QuotaRange.LAST_30_DAYS -> "s18-models-30"
        }
        UsageCardVariant.DEEPSEEK -> when (range) {
            QuotaRange.LAST_24_HOURS -> "s18-ds-models-24"
            QuotaRange.LAST_7_DAYS -> "s18-ds-models-7"
            QuotaRange.LAST_30_DAYS -> "s18-ds-models-30"
        }
        UsageCardVariant.OLLAMA_LEGACY -> null
    }
}

/**
 * 按模型区块的 Modifier：旧版计划没有明细（[modelsTag] 为 null）时原样返回，不加标识。
 *
 * 保留这一层 Modifier 包装而不是让调用点自己拼：锚点字面量必须落在 testTag(...) 的实参上
 * 才能被 scripts/check-anchors.mjs 从调用点追到（写成本地 val 再传给 testTag 会断链）。
 */
internal fun Modifier.modelsTagOf(variant: UsageCardVariant, range: QuotaRange): Modifier =
    modelsTag(variant, range)?.let { testTag(it) } ?: Modifier
