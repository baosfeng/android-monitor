package com.bsfeng.monitor.record

data class DailySummary(
    val totalScreenOnMinutes: Long = 0,
    val totalChargingMinutes: Long = 0,
    val chargeCount: Int = 0,
    val topApps: List<AppUsageStat> = emptyList(),
    val wifiChanges: Int = 0
)

data class AppUsageStat(
    val packageName: String,
    val appName: String,
    val totalMinutes: Long,
    val openCount: Int
)
