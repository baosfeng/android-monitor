package com.bsfeng.monitor.ui.quota

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * s19 账号实例的锚点后缀（原型 51 个锚点里，账号相关的按「类型 + 第几个账号」编号）。
 *
 * 原型的示例账号：ollama（主号）/ ollama2（备用号）/ ds1（DeepSeek 账号 1）/ od-a（open-design 工作区 A）。
 * 第一实例的 instanceId 恒等于类型 id（零迁移约定），这里只按**组内序号**映射，不依赖 id 格式。
 */
internal fun accountAnchorSuffix(providerTypeId: String, index: Int): String = when (providerTypeId) {
    "ollama" -> if (index == 0) "ollama" else "ollama" + (index + 1)
    "deepseek" -> "ds" + (index + 1)
    "opendesign" -> if (index == 0) "od-a" else if (index == 1) "od-b" else "od-" + ('a' + index)
    // 原型没有的 provider（opencode-go）不给示例编号：空后缀 → 落到组件级通用锚点
    else -> ""
}

/**
 * 锚点表：族名 + 后缀 → 原型锚点。
 *
 * 为什么用「前缀 + 后缀」而不是一条条字面量：账号数量是运行期决定的，原型只给了示例账号；
 * 前缀以 - 结尾，check-anchors 认「运行时前缀生成」这条证据形态（见 scripts/check-anchors.mjs 判据 2）。
 * 粘贴提示是唯一编号不规整的一族（hint/hint2/hint3/hint4），按示例账号逐条列。
 */
internal fun quotaSettingsAnchor(family: String, suffix: String): String {
    // 空后缀 = 原型没有示例编号的 provider（opencode-go 等）：回落到组件级通用锚点，
    // 这些锚点已登记在 s12a-组件级锚点登记，不必为每个 provider 自造页面级编号。
    if (suffix.isEmpty()) {
        return when (family) {
            "acc" -> "row.account"
            "panel" -> "row.provider"
            "input" -> "field.accountName"
            "reveal" -> "button.icon"
            "paste" -> "button.ghost"
            "save" -> "button.save"
            "clear" -> "button.inline"
            else -> "row.field"
        }
    }
    return when (family) {
        "acc" -> "s19-acc-" + suffix
        "panel" -> "s19-panel-" + suffix
        "input" -> "s19-input-" + suffix
        "reveal" -> "s19-reveal-" + suffix
        "paste" -> "s19-paste-" + suffix
        "save" -> "s19-save-" + suffix
        "clear" -> "s19-clear-" + suffix
        "pasteHint" -> when (suffix) {
            "ollama" -> "s19-paste-hint"
            "ollama2" -> "s19-paste-hint2"
            "ds1" -> "s19-paste-hint3"
            "od-a" -> "s19-paste-hint4"
            else -> "row.field"
        }
        else -> "row.account"
    }
}

internal fun Modifier.accountRowTagOf(suffix: String): Modifier =
    testTag(quotaSettingsAnchor("acc", suffix))

internal fun Modifier.accountPanelTagOf(suffix: String): Modifier =
    testTag(quotaSettingsAnchor("panel", suffix))

internal fun Modifier.accountInputTagOf(suffix: String): Modifier =
    testTag(quotaSettingsAnchor("input", suffix))

internal fun Modifier.accountRevealTagOf(suffix: String): Modifier =
    testTag(quotaSettingsAnchor("reveal", suffix))

internal fun Modifier.accountPasteTagOf(suffix: String): Modifier =
    testTag(quotaSettingsAnchor("paste", suffix))

internal fun Modifier.accountPasteHintTagOf(suffix: String): Modifier =
    testTag(quotaSettingsAnchor("pasteHint", suffix))

internal fun Modifier.accountSaveTagOf(suffix: String): Modifier =
    testTag(quotaSettingsAnchor("save", suffix))

internal fun Modifier.accountClearTagOf(suffix: String): Modifier =
    testTag(quotaSettingsAnchor("clear", suffix))

/** 组尾「添加 <provider> 账号」行：原型只给 ollama / open-design 两条，其余走通用 row.addAccount。 */
internal fun addAccountAnchorOf(providerTypeId: String): String = when (providerTypeId) {
    "ollama" -> "s19-add-ollama"
    "opendesign" -> "s19-add-od"
    else -> "row.addAccount"
}

/** 后台刷新档位 chip：原型逐字 5/15/30/60/关。 */
internal fun refreshChipAnchorOf(minutes: Int): String = when (minutes) {
    5 -> "s19-int-5"
    15 -> "s19-int-15"
    30 -> "s19-int-30"
    60 -> "s19-int-60"
    else -> "s19-int-off"
}

internal fun Modifier.refreshChipTagOf(minutes: Int): Modifier = testTag(refreshChipAnchorOf(minutes))