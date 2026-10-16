package com.bsfeng.monitor.autobook

import android.graphics.Rect
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 无障碍节点文本收集器（Android 侧）。
 *
 * 只负责「把一屏节点树读成 [NodeText] 列表」，分行与解析分别由
 * [PageLines] 和 [AutoBookParser] 完成 —— 后两者是纯 Kotlin，可被 JVM 单测直接覆盖。
 *
 * 线程约束：必须在无障碍服务主线程调用（AccessibilityNodeInfo 不可跨线程）。
 */
object NodeTextCollector {

    /** 单次最多遍历的节点数：支付详情页通常 100~300 个节点，600 足够且不会拖慢主线程 */
    const val MAX_NODES = 600

    /**
     * 收集一屏的文本节点（去重 + 限制节点数）。
     *
     * @param root 无障碍根节点；调用方负责回收 root 本身
     * @param ignoreBounds 是否忽略节点的屏幕坐标，默认 false。
     *   ⚠️ **只在诊断时传 true**：部分 App（真机实测微信）会把第三方无障碍服务看到的节点
     *   标记为不可见并把 bounds 归零，此时正常收集会得到空列表。忽略坐标能区分
     *   「树还在、只是被标记不可见」和「树被系统裁掉了」—— 这两种情况的应对完全不同，
     *   详见 docs/记账/微信支付页识别调研.md 的「待真机验证的实验清单」。
     *   忽略坐标后所有节点坐标为 (0,0)，**不能**用于分行解析，只能用来排查。
     */
    fun collect(root: AccessibilityNodeInfo?, ignoreBounds: Boolean = false): List<NodeText> {
        if (root == null) return emptyList()

        val result = ArrayList<NodeText>(64)
        val seen = HashSet<String>(64)
        val pending = ArrayDeque<AccessibilityNodeInfo>()
        val borrowed = ArrayList<AccessibilityNodeInfo>(64)
        val rect = Rect()

        pending.addLast(root)
        var visited = 0

        while (pending.isNotEmpty() && visited < MAX_NODES) {
            val node = pending.removeLast()
            visited++

            val label = node.text?.toString()?.trim().takeUnless { it.isNullOrEmpty() }
                ?: node.contentDescription?.toString()?.trim().takeUnless { it.isNullOrEmpty() }

            if (label != null && seen.add(label)) {
                runCatching {
                    node.getBoundsInScreen(rect)
                    if (ignoreBounds || (rect.width() > 0 && rect.height() > 0)) {
                        result.add(NodeText(label, rect.left, rect.top))
                    }
                }
            }

            for (index in 0 until node.childCount) {
                val child = node.getChild(index) ?: continue
                borrowed.add(child)
                pending.addLast(child)
            }
        }

        // getChild 返回的是新实例，必须显式回收，否则每屏泄漏数百个节点
        for (node in borrowed) {
            runCatching { node.recycle() }
        }
        return result
    }

    /** 一步到位：根节点 → 视觉行文本 */
    fun collectLines(root: AccessibilityNodeInfo?): List<String> =
        PageLines.toLines(collect(root))
}
