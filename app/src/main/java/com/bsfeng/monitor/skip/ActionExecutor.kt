package com.bsfeng.monitor.skip

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.graphics.Path
import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.domain.model.ClickPosition
import com.bsfeng.monitor.domain.model.MatchAction

/**
 * 动作执行器：只负责"在无障碍节点/屏幕坐标上执行动作"，不做规则匹配与选择器解析。
 *
 * 动作语义对齐 GKD：
 * - [MatchAction.CLICK]：节点可点击 → `ACTION_CLICK`；不可点击或未被应用接收 → 节点中心手势点击
 * - [MatchAction.CLICK_NODE]：只发 `ACTION_CLICK`（不可点击 / 未被应用接收则返回 false）
 * - [MatchAction.CLICK_CENTER]：节点中心（或 [ClickPosition] 指定的归一化坐标）手势点击
 * - [MatchAction.LONG_CLICK]：节点可长按 → `ACTION_LONG_CLICK`；否则长按中心 400ms 手势
 * - [MatchAction.BACK]：`performGlobalAction(GLOBAL_ACTION_BACK)`
 * - [MatchAction.NONE]：不做动作，返回 false
 *
 * 安全约定：节点不可用（null / 已回收导致读取异常）、bounds 为空、无障碍服务为空、
 * 坐标越界等场景一律安全返回 false（异步回调场景通过 `onResult(false)` 回报），
 * 不向调用方抛异常；关键失败一律打 [Log.w]（TAG = "SkipAction"）。
 */
object ActionExecutor {

    private const val TAG = "SkipAction"

    /** 普通点击手势时长（毫秒） */
    private const val CLICK_DURATION_MS = 80L

    /** 长按手势时长（毫秒） */
    private const val LONG_CLICK_DURATION_MS = 400L

    /** 无障碍服务引用（由 MonitorAccessibilityService 在 onServiceConnected/onDestroy 维护） */
    var accessibilityService: AccessibilityService? = null

    /**
     * 执行动作。
     *
     * @param position 归一化坐标(0..1，相对屏幕宽高)，仅 CLICK_CENTER 生效；null 时用节点中心
     * @return 是否真的触发了动作（NONE 恒为 false）
     */
    fun perform(
        node: AccessibilityNodeInfo,
        action: MatchAction,
        position: ClickPosition? = null
    ): Boolean {
        if (action == MatchAction.NONE) {
            return false // NONE 只作为匹配标记，不产生任何动作
        }
        return try {
            when (action) {
                MatchAction.CLICK -> clickNodeOrCenter(node)
                MatchAction.CLICK_NODE -> clickNodeOnly(node)
                MatchAction.CLICK_CENTER -> clickCenter(node, position)
                MatchAction.LONG_CLICK -> longClick(node)
                MatchAction.BACK -> performGlobalBack()
                MatchAction.NONE -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "perform($action) 执行异常: ${e.message}", e)
            false
        }
    }

    /**
     * 节点中心手势点击（带 GestureResultCallback，异步回报真实结果）。
     *
     * 节点不可用、bounds 为空、服务为空或手势派发失败时回调 false；
     * 手势派发成功后由系统回调 true（完成）/ false（取消）。
     *
     * @param onResult 手势结果回调，必定被调用一次
     */
    fun performGestureClick(node: AccessibilityNodeInfo, onResult: (Boolean) -> Unit) {
        try {
            val point = centerPoint(node)
            if (point == null) {
                onResult(false)
                return
            }
            val dispatched = dispatchGesture(point.first, point.second, CLICK_DURATION_MS) { ok ->
                onResult(ok)
            }
            if (!dispatched) {
                onResult(false)
            }
        } catch (e: Exception) {
            Log.w(TAG, "performGestureClick 执行异常: ${e.message}", e)
            onResult(false)
        }
    }

    /**
     * 屏幕坐标手势点击（用于 position 指定的自定义点击位置）。
     *
     * @return 手势是否派发成功；坐标为负或超出屏幕时返回 false
     */
    fun clickAt(x: Int, y: Int): Boolean {
        if (x < 0 || y < 0) {
            Log.w(TAG, "clickAt: 坐标非法 ($x, $y)")
            return false
        }
        return dispatchGesture(x, y, CLICK_DURATION_MS, null)
    }

    /** 全局返回键（GLOBAL_ACTION_BACK） */
    fun performGlobalBack(): Boolean {
        val service = accessibilityService
        if (service == null) {
            Log.w(TAG, "performGlobalBack: 无障碍服务为空")
            return false
        }
        return try {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        } catch (e: Exception) {
            Log.w(TAG, "performGlobalBack 执行异常: ${e.message}", e)
            false
        }
    }

    // ========== 动作实现 ==========

    /** CLICK：节点可点击优先发 ACTION_CLICK，失败或不可点击时降级为节点中心手势点击 */
    private fun clickNodeOrCenter(node: AccessibilityNodeInfo): Boolean {
        if (!isUsable(node)) return false
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "CLICK: ACTION_CLICK 成功")
            return true
        }
        val point = centerPoint(node) ?: return false
        Log.d(TAG, "CLICK: 降级为节点中心手势点击 (${point.first}, ${point.second})")
        return clickAt(point.first, point.second)
    }

    /** CLICK_NODE：只发 ACTION_CLICK，节点不可点击或未被应用接收即失败 */
    private fun clickNodeOnly(node: AccessibilityNodeInfo): Boolean {
        if (!isUsable(node)) return false
        if (!node.isClickable) {
            Log.w(TAG, "CLICK_NODE: 节点不可点击，放弃")
            return false
        }
        val clicked = node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        if (!clicked) {
            Log.w(TAG, "CLICK_NODE: ACTION_CLICK 未被应用接收")
        }
        return clicked
    }

    /** CLICK_CENTER：节点中心或 position 归一化坐标手势点击 */
    private fun clickCenter(node: AccessibilityNodeInfo, position: ClickPosition?): Boolean {
        val point = if (position != null) normalizedPoint(position) else centerPoint(node)
        if (point == null) return false
        return clickAt(point.first, point.second)
    }

    /** LONG_CLICK：可长按节点优先 ACTION_LONG_CLICK，否则长按节点中心 400ms */
    private fun longClick(node: AccessibilityNodeInfo): Boolean {
        if (!isUsable(node)) return false
        if (node.isLongClickable && node.performAction(AccessibilityNodeInfo.ACTION_LONG_CLICK)) {
            Log.d(TAG, "LONG_CLICK: ACTION_LONG_CLICK 成功")
            return true
        }
        val point = centerPoint(node) ?: return false
        Log.d(TAG, "LONG_CLICK: 降级为长按中心手势 (${point.first}, ${point.second})")
        return dispatchGesture(point.first, point.second, LONG_CLICK_DURATION_MS, null)
    }

    // ========== 坐标与手势 ==========

    /**
     * 派发一次点击/长按手势。
     *
     * @param callback 非空时通过 GestureResultCallback 异步回报结果
     * @return 手势是否派发成功（服务为空、坐标越界或系统拒绝时为 false）
     */
    private fun dispatchGesture(
        x: Int,
        y: Int,
        durationMs: Long,
        callback: ((Boolean) -> Unit)?
    ): Boolean {
        val service = accessibilityService
        if (service == null) {
            Log.w(TAG, "手势派发失败: 无障碍服务为空 ($x, $y)")
            return false
        }
        return try {
            if (!isInsideScreen(service, x, y)) {
                Log.w(TAG, "手势派发失败: 坐标超出屏幕 ($x, $y)")
                return false
            }
            val path = Path().apply {
                moveTo(x.toFloat(), y.toFloat())
                lineTo(x.toFloat(), y.toFloat())
            }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0L, durationMs))
                .build()
            val gestureCallback = callback?.let { report ->
                object : AccessibilityService.GestureResultCallback() {
                    override fun onCompleted(gestureDescription: GestureDescription) {
                        Log.d(TAG, "手势完成 ($x, $y)")
                        report(true)
                    }

                    override fun onCancelled(gestureDescription: GestureDescription) {
                        Log.w(TAG, "手势取消 ($x, $y)")
                        report(false)
                    }
                }
            }
            service.dispatchGesture(gesture, gestureCallback, null)
        } catch (e: Exception) {
            Log.w(TAG, "手势派发异常 ($x, $y): ${e.message}", e)
            false
        }
    }

    /** 归一化坐标(0..1) → 屏幕像素坐标；越界或屏幕尺寸不可用时返回 null */
    private fun normalizedPoint(position: ClickPosition): Pair<Int, Int>? {
        val nx = position.x
        val ny = position.y
        if (nx.isNaN() || ny.isNaN() || nx < 0f || nx > 1f || ny < 0f || ny > 1f) {
            Log.w(TAG, "position 归一化坐标越界: ($nx, $ny)")
            return null
        }
        val service = accessibilityService
        if (service == null) {
            Log.w(TAG, "position 换算失败: 无障碍服务为空")
            return null
        }
        return try {
            val metrics = service.resources.displayMetrics
            val maxX = (metrics.widthPixels - 1).coerceAtLeast(0)
            val maxY = (metrics.heightPixels - 1).coerceAtLeast(0)
            val px = (nx * metrics.widthPixels).toInt().coerceIn(0, maxX)
            val py = (ny * metrics.heightPixels).toInt().coerceIn(0, maxY)
            px to py
        } catch (e: Exception) {
            Log.w(TAG, "position 换算异常: ${e.message}", e)
            null
        }
    }

    /** 节点中心屏幕坐标；节点不可用或 bounds 为空时返回 null */
    private fun centerPoint(node: AccessibilityNodeInfo?): Pair<Int, Int>? {
        if (node == null) {
            Log.w(TAG, "节点为空，无法坐标点击")
            return null
        }
        return try {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (rect.isEmpty) {
                Log.w(TAG, "节点 bounds 为空，无法坐标点击")
                return null
            }
            rect.centerX() to rect.centerY()
        } catch (e: Exception) {
            Log.w(TAG, "读取节点 bounds 异常（节点可能已回收）: ${e.message}", e)
            null
        }
    }

    /**
     * 节点是否可用（null 视为不可用）。
     *
     * 注：compileSdk 36 的 AccessibilityNodeInfo 已无 isRecycled()，
     * 已回收/失效节点的读取异常由各调用处的 try/catch 兜底。
     */
    private fun isUsable(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) {
            Log.w(TAG, "节点为空，动作中止")
            return false
        }
        return true
    }

    /** 坐标是否落在屏幕内（左上角为 0,0，右下角为 width-1/height-1） */
    private fun isInsideScreen(service: AccessibilityService, x: Int, y: Int): Boolean {
        val metrics = service.resources.displayMetrics
        return x in 0 until metrics.widthPixels && y in 0 until metrics.heightPixels
    }
}
