package com.bsfeng.monitor.autobook

import android.graphics.PixelFormat
import android.os.Build
import android.view.Gravity
import android.view.WindowInsets
import android.view.WindowManager
import androidx.annotation.RequiresApi

// 从 autobook/AutoBookOverlay.kt 拆出：无障碍叠加窗口的 LayoutParams 构建与输入焦点切换。
// 负责「窗口怎么摆、能不能拿焦点」，不含 Compose 内容与生命周期。

/**
 * [WindowManager.LayoutParams] 的构建与「是否可聚焦」切换。
 *
 * 两个关键契约：
 * 1. 全屏沉浸：`fitInsetsTypes = 0` + `LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS`，
 *    否则窗口被状态栏/手势条挤小，遮罩铺不满。
 * 2. 默认 `FLAG_NOT_FOCUSABLE`（不抢目标 App 输入）；编辑「说明」时临时去掉才能弹输入法，
 *    同时去掉 `FLAG_LAYOUT_NO_LIMITS` 让 `ADJUST_RESIZE` 给键盘让位。
 */
internal object OverlayWindowConfig {

    /** 全屏、默认不可聚焦、请求硬件加速的展示态窗口参数。 */
    fun build(): WindowManager.LayoutParams = WindowManager.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        // 可点击但默认不拿输入焦点；不铺满就会退回「只包住底部卡片」，遮罩无法覆盖状态栏。
        // 编辑「说明」时由 setImeFocusable(true) 临时去掉 NOT_FOCUSABLE，否则输入法不会弹。
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        // 无障碍叠加窗口默认可能走软件渲染（真机实测 isHardwareAccelerated=false）：
        // 全屏遮罩 + 分类网格滚动每帧都要 CPU 重绘，必然卡。显式请求硬件加速；
        // 部分 ROM 会忽略这个 flag，所以 attach 后仍会打印实际值。
        @Suppress("DEPRECATION")
        flags = flags or WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        // 键盘弹出时把窗口顶小，确认卡跟着上移；未编辑时窗口不可聚焦，键盘不会弹
        softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // 声明「不吃系统栏 inset」：否则窗口会被状态栏 / 手势条挤小，遮罩铺不满
            fitInsetsTypes = 0
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        } else {
            @Suppress("DEPRECATION")
            layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    /** 按 [focusable] 改窗口的焦点 / 边界 flag（原地修改 [params]）。 */
    fun applyFocusable(params: WindowManager.LayoutParams, focusable: Boolean) {
        params.flags = if (focusable) {
            // 编辑态：去掉 NOT_FOCUSABLE 才能拿输入焦点；同时去掉 NO_LIMITS，
            // 否则窗口不受显示边界约束，ADJUST_RESIZE 不一定让位给键盘
            params.flags and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv() and
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS.inv()
        } else {
            // 退出编辑：恢复「不可聚焦 + 铺满无边」的展示态
            params.flags or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            fitImeOnly(params, focusable)
        }
    }

    /**
     * 只 fit IME（不 fit 状态栏 / 手势条）：键盘弹出时窗口被顶小，遮罩仍铺满除键盘外的屏幕。
     *
     * 单独封装 + [RequiresApi]：`WindowInsets.Type` 是 API 30 才有的类型，直接在通用代码里
     * 引用会在 API 29 上冒类解析风险。
     */
    @RequiresApi(Build.VERSION_CODES.R)
    private fun fitImeOnly(params: WindowManager.LayoutParams, focusable: Boolean) {
        params.fitInsetsTypes = if (focusable) WindowInsets.Type.ime() else 0
    }
}
