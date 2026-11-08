package com.bsfeng.monitor.ui

import java.time.Instant
import java.time.ZoneId

internal fun monthDayLabel(timestamp: Long): String {
    val date = Instant.ofEpochMilli(timestamp).atZone(ZoneId.systemDefault()).toLocalDate()
    return date.monthValue.toString() + " 月 " + date.dayOfMonth + " 日"
}
