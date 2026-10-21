package com.bsfeng.monitor.ui.components

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * 确认类触觉反馈。
 *
 * `HapticFeedbackConstants.CONFIRM` 是 API 30 才有的常量，minSdk 29 直接引用会被 lint 判
 * [InlinedApi]（旧机型上常量为 0，表现为无反馈而非崩溃）。这里按系统版本分支，29 上回落
 * [HapticFeedbackConstants.VIRTUAL_KEY]，各屏统一调用本函数，不再直接引用该常量。
 */
fun View.performConfirmHaptic() {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        performHapticFeedback(HapticFeedbackConstants.CONFIRM)
    } else {
        performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }
}
