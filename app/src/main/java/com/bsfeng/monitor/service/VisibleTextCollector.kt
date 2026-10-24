package com.bsfeng.monitor.service

// 从 service/MonitorAccessibilityService.kt 拆出：页面指纹用的轻量可见文本抽取。
// 限深限节点、只留在内存，不复用 SnapshotExporter（那边是 60 深全属性，量级差两个数量级）。

import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.record.PageTracker

/** 页面指纹的轻量遍历上限：每秒轮询跑一次，限深 8 / 节点 200（快照导出是另一个全属性全深度通道） */
private const val PAGE_MAX_DEPTH = 8
private const val PAGE_MAX_NODES = 200

/**
 * 轻量可见文本抽取（指纹输入，**只留在内存**）。
 *
 * 限深 [PAGE_MAX_DEPTH]、节点 [PAGE_MAX_NODES]、最多 [PageTracker.MAX_TEXTS] 条，
 * 只取 `visibleToUser` 的 text；不复用 SnapshotExporter（那边是 60 深全属性，量级差两个数量级）。
 */
internal fun collectVisibleTexts(root: android.view.accessibility.AccessibilityNodeInfo?): List<String> {
    val target = root ?: return emptyList()
    val result = ArrayList<String>(PageTracker.MAX_TEXTS)
    val pending = ArrayDeque<Pair<android.view.accessibility.AccessibilityNodeInfo, Int>>()
    val borrowed = ArrayList<android.view.accessibility.AccessibilityNodeInfo>(64)
    pending.addLast(target to 0)
    var visited = 0
    try {
        while (pending.isNotEmpty() && visited < PAGE_MAX_NODES && result.size < PageTracker.MAX_TEXTS) {
            val (node, depth) = pending.removeFirst()
            visited++
            if (node.isVisibleToUser) {
                val label = node.text?.toString()?.trim()
                if (!label.isNullOrEmpty()) result.add(label)
            }
            if (depth < PAGE_MAX_DEPTH) {
                for (index in 0 until node.childCount) {
                    val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
                    borrowed.add(child)
                    pending.addLast(child to depth + 1)
                }
            }
        }
    } finally {
        // getChild 返回的是新实例：不回收会让每秒一次的轮询堆积节点句柄
        for (node in borrowed) runCatching { node.recycle() }
    }
    return result
}
