package com.bsfeng.monitor.ui.quota

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

/**
 * 设置页账号行的锚点。
 *
 * 老锚点按 provider **类型**逐字映射（不改号）—— 第一实例的 instanceId 恒等于类型 id，
 * 所以第一行仍然命中 `s19-provider-ollama` 这类既有锚点；
 * 同类型的第二个账号起用 `s19-account-<类型>-#N` 的形态，保持「一行一个锚点」。
 */
internal fun Modifier.providerRowTagOf(instanceId: String, providerTypeId: String): Modifier =
    if (instanceId == providerTypeId) providerRowTagOf(providerTypeId) else testTag("row.account")

internal fun Modifier.providerRowTagOf(providerTypeId: String): Modifier = when (providerTypeId) {
    "ollama" -> testTag("s19-provider-ollama")
    "deepseek" -> testTag("s19-provider-deepseek")
    "opencode-go" -> testTag("s19-provider-opencode")
    "opendesign" -> testTag("s19-provider-opendesign")
    else -> this
}

internal fun Modifier.refreshChipTagOf(hours: Int): Modifier = when (hours) {
    0 -> testTag("s19-refresh-off")
    1 -> testTag("s19-refresh-1h")
    3 -> testTag("s19-refresh-3h")
    6 -> testTag("s19-refresh-6h")
    else -> this
}
