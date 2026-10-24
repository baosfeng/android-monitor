package com.bsfeng.monitor.service

// 从 service/MonitorAccessibilityService.kt 拆出：跳过成功后的「弹幕」浮层。
// 用 TYPE_ACCESSIBILITY_OVERLAY 显示在任意 App 之上（无需悬浮窗权限），带入场/退场动画。

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log

/**
 * 当前正在显示的弹幕视图，防止重叠。
 *
 * 只持有 View（不持 Context），且在所有移除路径上置空；跟随服务的生命周期，服务销毁即释放。
 */
@SuppressLint("StaticFieldLeak")
private var currentDanmaku: android.view.View? = null

private const val DANMAKU_DURATION_MS = 1200L

/**
 * 显示弹幕式跳过提示（居中半透明浮层）。
 * 使用 [android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY]
 * 保证在任意 App 上方显示，且不需要额外权限。
 * 带有淡入淡出和上下滑入滑出的入场/退场动画。
 */
internal fun MonitorAccessibilityService.showSkipDanmaku(appName: String, confirmed: Boolean? = null) {
    Log.d("MonitorService", "showSkipDanmaku: $appName confirmed=$confirmed")
    try {
        // 移除之前的弹幕
        removeCurrentDanmaku()

        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        // 未确认时如实提示，避免把"点了没反应"也说成跳过成功
        val message = if (confirmed == false) {
            "⏭️ 已点击 $appName 跳过按钮（未确认）"
        } else {
            "⏭️ 已跳过 $appName 开屏广告"
        }

        val tv = android.widget.TextView(this).apply {
            text = message
            textSize = 17f
            gravity = android.view.Gravity.CENTER
            setTextColor(android.graphics.Color.WHITE)
            setBackgroundColor(android.graphics.Color.argb(200, 0, 0, 0))
            setPadding(40, 20, 40, 20)
            elevation = 12f
            // 圆角通过 shape 背景
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(android.graphics.Color.argb(200, 0, 0, 0))
                cornerRadius = 20f
            }
            // 初始状态：透明 + 向上偏移（为入场动画做准备）
            alpha = 0f
            translationY = -50f
        }

        val params = android.view.WindowManager.LayoutParams(
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.WRAP_CONTENT,
            android.view.WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            android.view.WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    android.view.WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    android.view.WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            android.graphics.PixelFormat.TRANSLUCENT
        ).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.CENTER_HORIZONTAL
            y = 120
        }

        currentDanmaku = tv
        wm.addView(tv, params)

        // 入场动画：淡入 + 下滑到目标位置（300ms）
        tv.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(300)
            .setInterpolator(android.view.animation.OvershootInterpolator(0.8f))
            .start()

        // 展示 DANMAKU_DURATION_MS 后退场
        tv.postDelayed({
            try {
                // 退场动画：淡出 + 上滑（250ms）
                tv.animate()
                    .alpha(0f)
                    .translationY(-30f)
                    .setDuration(250)
                    .withEndAction {
                        // 只移除自己，不触碰 currentDanmaku——
                        // 若期间已展示新弹幕，currentDanmaku 指向新视图，不能误删
                        removeDanmakuView(tv)
                    }
                    .start()
            } catch (e: Exception) {
                removeDanmakuView(tv)
            }
        }, DANMAKU_DURATION_MS)
    } catch (e: Exception) {
        Log.w("MonitorService", "显示跳过弹幕失败", e)
    }
}

/** 移除当前弹幕视图 */
internal fun MonitorAccessibilityService.removeCurrentDanmaku() {
    val view = currentDanmaku ?: return
    currentDanmaku = null
    removeDanmakuView(view)
}

/** 从 WindowManager 移除指定弹幕视图（幂等：已移除/服务已销毁时静默忽略） */
internal fun MonitorAccessibilityService.removeDanmakuView(view: android.view.View) {
    try {
        val wm = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        wm.removeView(view)
    } catch (_: Exception) {}
}
