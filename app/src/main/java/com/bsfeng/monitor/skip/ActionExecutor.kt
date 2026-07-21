package com.bsfeng.monitor.skip

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 动作执行器。
 *
 * 对无障碍节点执行点击等操作，支持多种动作类型。
 */
object ActionExecutor {

    private const val TAG = "SkipAction"

    /**
     * 在节点上执行动作。
     *
     * @param node 目标节点
     * @param action 动作类型（click / longClick / back）
     * @return 是否执行成功
     */
    fun perform(node: AccessibilityNodeInfo, action: String): Boolean {
        return when (action.lowercase()) {
            "click" -> performClick(node)
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
     * 优先使用 performAction(ACTION_CLICK)，失败时尝试点击节点中心坐标。
     */
    private fun performClick(node: AccessibilityNodeInfo): Boolean {
        // 方法1: 使用无障碍 ACTION_CLICK
        if (node.isClickable) {
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                Log.d(TAG, "performClick: ACTION_CLICK 成功")
                return true
            }
        }

        // 方法2: 尝试点击父节点（如果当前节点不可点击但父节点可点击）
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

        // 方法3: 直接点击（没有资源ID常量的后备方案）
        if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            Log.d(TAG, "performClick: 再次尝试 ACTION_CLICK")
            return true
        }

        Log.w(TAG, "performClick: 所有方式都失败")
        return false
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
     */
    private fun performGlobalBack(node: AccessibilityNodeInfo): Boolean {
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }
}
