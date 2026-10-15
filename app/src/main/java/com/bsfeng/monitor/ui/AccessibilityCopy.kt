package com.bsfeng.monitor.ui

/**
 * 无障碍服务状态文案的唯一来源（s01 概览 / s02 实时 / s05 设置共用）。
 *
 * 数据源统一为 [com.bsfeng.monitor.util.AccessibilityStatus] 三重判定的结果
 * （MainViewModel / OverviewViewModel 分别写入 accessibilityEnabled /
 * accessibilityServiceRunning），避免各屏各写一套口径导致自相矛盾
 * （设备实证：s02 空态写「无障碍服务已就绪」，s05 同一时刻写「未授权」）。
 */

/**
 * 状态行文案（s05 设置页）—— 不含运行时长的「仅状态」口径。
 *
 * s09 设置页要的是原型的两段式「已授权 · 已运行 6 小时 12 分」（带时长），由同文件的
 * [s09AccessibilityStatusText] 承载；此处的 running 文案保留给「只报状态、不报时长」的场景，
 * 措辞仍只有本文件一处出处。
 */
internal fun accessibilityStatusText(enabled: Boolean, running: Boolean): String = when {
    running -> "已授权 · 服务运行中"
    enabled -> "已开启（服务未连接，稍候或重进 App 生效）"
    else -> "未授权 · 点击右侧按钮到系统设置开启"
}

/**
 * s09 设置页状态行文案（原型 .open-design/08-prototype.html:4318：`已授权 · 已运行 6 小时 12 分`）。
 *
 * 原型只有两段：「已运行」已隐含服务在运行，再叠一句「服务运行中」会让三段合计超出 TokenRow
 * 的单行宽度，在窄屏（小米 14 ≈ 400dp 逻辑宽）被省略号截掉运行时长，用户看不到时长。
 *
 * [uptimeText] 为运行时长文案（如「6 小时 12 分」），格式由调用方按原型口径产出后传入；
 * 整句措辞仍在本文件产出，各屏不各写一套。enabled=false / running=false 两态原型没有，
 * 沿用 [accessibilityStatusText] 的如实口径。
 */
internal fun s09AccessibilityStatusText(
    enabled: Boolean,
    running: Boolean,
    uptimeText: String,
): String = if (running) "已授权 · 已运行 $uptimeText" else accessibilityStatusText(enabled, running)

/**
 * 无数据空态文案（s01 / s02）。
 *
 * 已授权时沿用原型措辞（parts/s02-live.html:137-138）；未授权时如实说明，
 * 不再无条件宣称「已就绪」。
 */
internal fun noEventEmptyText(enabled: Boolean): String = if (enabled) {
    "无障碍服务已就绪。屏幕、应用、网络与充电一旦变化，就会按小时汇总到这里。"
} else {
    "无障碍服务未授权（设置 → 无障碍服务 → 去开启）。开启后，屏幕、应用、网络与充电事件会自动汇总到这里。"
}
