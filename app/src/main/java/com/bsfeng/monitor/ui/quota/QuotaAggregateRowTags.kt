package com.bsfeng.monitor.ui.quota

import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag

internal fun Modifier.rankedRowTagOf(index: Int): Modifier = when (index) {
    0 -> testTag("s18-agg-row-1")
    1 -> testTag("s18-agg-row-2")
    2 -> testTag("s18-agg-row-3")
    else -> this
}

internal fun Modifier.excludedRowTagOf(index: Int): Modifier =
    if (index == 0) testTag("s18-agg-row-4") else this
