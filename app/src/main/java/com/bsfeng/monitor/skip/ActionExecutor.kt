package com.bsfeng.monitor.skip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.service.MonitorAccessibilityService

/**
 * 动作执行器。
 *
 * 对无障碍节点执行点击等操作，支持多种动作类型。
 * 对于 WebView / 小程序等无法使用 ACTION_CLICK 的场景，
 * 会降级为坐标点击（dispatchGesture）。
 */
object ActionExecutor {

    private const val TAG = "SkipAction"

    /** 全局引用，供坐标点击使用 */
    var accessibilityService: AccessibilityService? = null

    /**
     * 在节点上执行动作。
     *
     * @param node 目标节点
     * @param action 动作类型（click / longClick / back）
     * @return 是否执行成功
     */
    fun perform(node: AccessibilityNodeInfo, action: String): Boolean {
        return when (action.lowercase()) {
            "click" -> performClick(node, allowParentClick = true)
            "click_direct" -> performClick(node, allowParentClick = false)
            "click_coordinate" -> performCoordinateClick(node)
            "longclick" -> performLongClick(node)
            "back" -> performGlobalBack(node)
            else -> {
                Log.w(TAG, "未知动作类型: $action")
                false
            }
        }
    }

    /**
     * 执行点击操作。
     *
     * @param allowParentClick 是否允许逐级尝试点击父节点（WebView/小程序内关闭此选项避免误触）
     */
    private fun performClick(node: AccessibilityNodeInfo, allowParentClick: Boolean = true): Boolean {
        // 方法1: 使用无障碍 ACTION_CLICK
        if (node.isClickable) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "performClick: ACTION_CLICK 成功")
                return true
            }
        }

        // 方法2: 尝试点击父节点（如果当前节点不可点击但父节点可点击）
        // ⚠️ WebView/小程序内禁用，父节点可能是容器 View，点击会导致导航退出
        if (allowParentClick) {
            try {
                var parent = node.parent
                while (parent != null) {
                    if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        Log.d(TAG, "performClick: 父节点点击成功")
                        parent.recycle()
                        return true
                    }
                    val grandParent = parent.parent
                    parent.recycle()
                    parent = grandParent
                }
            } catch (_: Exception) {}
        }

        // 方法3: 直接点击（没有资源ID常量的后备方案）
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "performClick: 再次尝试 ACTION_CLICK")
            return true
        }

        // 方法4: 坐标点击降级（解决 WebView/复杂布局无法 ACTION_CLICK 的问题）
        val gesvc = accessibilityService
        if (gesvc != null) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.isEmpty) {
                Log.w(TAG, "performClick: 节点边界为空，跳过坐标点击")
            } else {
                val centerX = rect.centerX().toFloat()
                val centerY = rect.centerY().toFloat()
                Log.d(TAG, "performClick: 坐标点击 ($centerX, $centerY) via dispatchGesture")

                val path = android.graphics.Path().apply {
                    moveTo(centerX, centerY)
                    lineTo(centerX, centerY)
                }
                val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 80L)
                val gesture = android.accessibilityservice.GestureDescription.Builder()
                    .addStroke(stroke)
                    .build()

                Log.d(TAG, "performClick: 坐标点击 ($centerX, $centerY) via dispatchGesture")
                gesvc.dispatchGesture(gesture, null, null)
                return true
            }
        }

        Log.w(TAG, "performClick: 所有方式都失败")
        return false
    }

    /**
     * 纯坐标点击（跳过所有 ACTION_CLICK 尝试，直接 dispatchGesture）。
     * 用于 WebView/小程序等无障碍 ACTION_CLICK 不可靠的场景。
     */
    private fun performCoordinateClick(node: AccessibilityNodeInfo): Boolean {
        val service = accessibilityService ?: return false
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) return false
        val centerX = rect.centerX().toFloat()
        val centerY = rect.centerY().toFloat()
        Log.d(TAG, "performCoordinateClick: ($centerX, $centerY) via dispatchGesture")

        val path = android.graphics.Path().apply {
            moveTo(centerX, centerY)
            lineTo(centerX, centerY)
        }
        val stroke = android.accessibilityservice.GestureDescription.StrokeDescription(path, 0, 100L)
        val gesture = android.accessibilityservice.GestureDescription.Builder()
            .addStroke(stroke)
            .build()
        service.dispatchGesture(gesture, null, null)
        return true
    }

    /**
     * 执行长按操作。
     */
    private fun performLongClick(node: AccessibilityNodeInfo): Boolean {
        return if (node.isLongClickable) {
            node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)
        } else {
            false
        }
    }

    /**
     * 模拟返回键（通过无障碍服务执行全局返回）。
     * 广告弹窗通常可以通过返回键关闭。
     */
    private fun performGlobalBack(node: AccessibilityNodeInfo): Boolean {
        val service = accessibilityService
        if (service != null) {
            return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
        return false
    }

    /**
     * 最终兜底：尝试全局返回键。
     * 当所有点击方式都失败时，模拟按返回键尝试关闭广告弹窗。
     */
    fun performGlobalBack(): Boolean {
        val service = accessibilityService
        if (service != null) {
            return service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
        return false
    }

    /**
     * 异步坐标点击（dispatchGesture），通过回调返回手势执行结果。
     *
     * 与 [perform] 不同：该方法使用 [GestureResultCallback] 确认手势真正完成，
     * 而非盲目返回 true。适用于监控场景——手势取消时不会误判为跳过成功。
     *
     * @param node 目标节点
     * @param onResult 手势结果回调：true=手势完成, false=手势取消/失败
     */
    fun performGestureClick(
        node: AccessibilityNodeInfo,
        onResult: (Boolean) -> Unit
    ) {
        val service = accessibilityService
        if (service == null) {
            Log.w(TAG, "performGestureClick: accessibilityService 为空")
            onResult(false)
            return
        }
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.isEmpty) {
            Log.w(TAG, "performGestureClick: 节点边界为空")
            onResult(false)
            return
        }
        val centerX = rect.centerX().toFloat()
        val centerY = rect.centerY().toFloat()
        Log.d(TAG, "performGestureClick: ($centerX, $centerY) via dispatchGesture")

        val path = Path().apply {
            moveTo(centerX, centerY)
            lineTo(centerX, centerY)
        }
        val stroke = GestureDescription.StrokeDescription(path, 0, 100L)
        val gesture = GestureDescription.Builder()
            .addStroke(stroke)
            .build()

        service.dispatchGesture(gesture, object : AccessibilityService.GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription) {
                Log.d(TAG, "performGestureClick: ✅ 手势完成 ($centerX, $centerY)")
                onResult(true)
            }

            override fun onCancelled(gestureDescription: GestureDescription) {
                Log.w(TAG, "performGestureClick: ❌ 手势取消 ($centerX, $centerY)")
                onResult(false)
            }
        }, null)
    }
}
