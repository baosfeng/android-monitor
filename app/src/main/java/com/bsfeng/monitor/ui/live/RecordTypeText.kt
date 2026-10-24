package com.bsfeng.monitor.ui.live

// 从 ui/live/LiveCards.kt 拆出：事件类型与来源的文案 / 图标映射 —— recordTypeIcon、recordTypeLabel、recordSourceLabel、recordSubtitle。负责「一条记录该显示什么名字、什么图标」。

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AirplanemodeActive
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.BatteryStd
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.LocationOn
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.Phone
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import com.bsfeng.monitor.domain.model.Record
import com.bsfeng.monitor.domain.model.RecordType

fun recordTypeIcon(type: RecordType): ImageVector = when (type) {
    RecordType.SCREEN -> Icons.Outlined.Smartphone
    RecordType.APP_USAGE -> Icons.Outlined.Apps
    RecordType.CHARGING -> Icons.Outlined.Bolt
    RecordType.BATTERY -> Icons.Outlined.BatteryStd
    RecordType.WIFI -> Icons.Outlined.Wifi
    RecordType.AUDIO -> Icons.Outlined.MusicNote
    RecordType.PACKAGE -> Icons.Outlined.Build
    RecordType.POWER_SAVE -> Icons.Outlined.BatteryStd
    RecordType.AIRPLANE -> Icons.Outlined.AirplanemodeActive
    RecordType.PHONE_STATE -> Icons.Outlined.Phone
    RecordType.LOCATION -> Icons.Outlined.LocationOn
    RecordType.PAGE -> Icons.Outlined.Article
}

fun recordTypeLabel(type: RecordType): String = when (type) {
    RecordType.SCREEN -> "屏幕"
    RecordType.APP_USAGE -> "前台使用"
    RecordType.CHARGING -> "充电"
    RecordType.BATTERY -> "电量"
    RecordType.WIFI -> "网络"
    RecordType.AUDIO -> "音频"
    RecordType.PACKAGE -> "应用"
    RecordType.POWER_SAVE -> "省电"
    RecordType.AIRPLANE -> "飞行模式"
    RecordType.PHONE_STATE -> "通话"
    RecordType.LOCATION -> "定位"
    RecordType.PAGE -> "页面"
}

/** 采集来源短名（对齐原型行副标题的「· 无障碍服务 / 广播接收」）。 */
fun recordSourceLabel(type: RecordType): String = when (type) {
    RecordType.APP_USAGE, RecordType.PAGE -> "无障碍服务"
    RecordType.AUDIO -> "音频焦点"
    RecordType.LOCATION -> "位置服务"
    else -> "广播接收"
}

fun recordSubtitle(record: Record): String =
    record.type.let { recordTypeLabel(it) } + "事件 · " + recordSourceLabel(record.type)

// ───────────────────────── 页头 / 图标按钮 ─────────────────────────
