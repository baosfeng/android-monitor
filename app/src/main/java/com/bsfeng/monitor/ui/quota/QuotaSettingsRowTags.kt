package com.bsfeng.monitor.ui.quota

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

internal fun Modifier.providerRowTagOf(providerId: String): Modifier = when (providerId) {
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
