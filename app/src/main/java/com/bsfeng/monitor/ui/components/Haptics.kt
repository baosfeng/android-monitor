package com.bsfeng.monitor.ui.components

import android.view.View
import com.bsfeng.monitor.ui.foundation.HapticTone
import com.bsfeng.monitor.ui.foundation.performHaptic

/**
 * 确认类触觉反馈 —— **兼容 shim**，新代码请直接用 `view.performHaptic(HapticTone.SUCCESS)`。
 *
 * 保留原因：仓库里有 40+ 处调用本函数，一次性改到 tone 版会把改动面铺满所有屏；保留它同时也让
 * 这些旧调用点**立刻获得**三重闸门（系统开关 / View 开关 / 应用开关）与强度档换算。
 *
 * 语义等价于 [HapticTone.SUCCESS]：API 30+ 走 `HapticFeedbackConstants.CONFIRM`，
 * API 29 回落 `VIRTUAL_KEY`（`CONFIRM` 是 API 30 常量，minSdk 29 直接引用会被 lint 判
 * `InlinedApi`；版本分支现集中在 `ui/foundation/Haptics.kt` 的 `constantFor`）。
 */
@Deprecated(
    message = "改用 tone 版：view.performHaptic(HapticTone.SUCCESS)",
    replaceWith = ReplaceWith(
        "performHaptic(HapticTone.SUCCESS)",
        "com.bsfeng.monitor.ui.foundation.performHaptic",
        "com.bsfeng.monitor.ui.foundation.HapticTone",
    ),
)
fun View.performConfirmHaptic() {
    performHaptic(HapticTone.SUCCESS)
}
