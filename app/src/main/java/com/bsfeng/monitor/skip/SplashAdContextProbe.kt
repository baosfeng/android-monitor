package com.bsfeng.monitor.skip

// 职责：从无障碍节点采集判据所需的上下文（文案/描述/viewId/祖先 viewId 链/同屏广告标识），供 SplashCandidateVerdict 判定。

import android.view.accessibility.AccessibilityNodeInfo

/**
 * 跳过候选的上下文采集。
 *
 * 只有这里碰 [AccessibilityNodeInfo]：判定本身是纯函数（[SplashCandidateVerdict]），
 * 便于单测覆盖真实写法而不依赖节点树。
 */
internal object SplashAdContextProbe {

    /** 祖先链最大回溯层级：再深的祖先基本不是广告容器，且频繁 getParent 拖慢主线程 */
    private const val MAX_ANCESTOR_DEPTH = 8

    /** 同屏广告标识扫描的节点上限（防超长页面拖慢扫描） */
    private const val MAX_SCAN_NODES = 120

    /** 同屏广告标识文案（简体/繁体/英文） */
    private val AD_MARKERS = listOf("广告", "廣告", "推广", "推廣", "赞助", "贊助", "广告位")

    /**
     * 采集命中节点的判据上下文。
     *
     * @param node 规则命中的目标节点（调用方保证未回收）
     * @param root 当前窗口根节点（用于同屏广告标识扫描）
     * @param elapsedSinceEnterMs 自切换到当前 App 起的毫秒数
     */
    fun probe(
        node: AccessibilityNodeInfo,
        root: AccessibilityNodeInfo?,
        elapsedSinceEnterMs: Long
    ): SplashCandidate = SplashCandidate(
        text = node.text?.toString(),
        contentDescription = node.contentDescription?.toString(),
        viewId = node.viewIdResourceName,
        ancestorViewIds = ancestorViewIds(node),
        screenHasAdMarker = hasAdMarker(root),
        elapsedSinceEnterMs = elapsedSinceEnterMs
    )

    /** 自命中节点向上到根的 viewId 链（跳过空 viewId，最多 [MAX_ANCESTOR_DEPTH] 层） */
    private fun ancestorViewIds(node: AccessibilityNodeInfo): List<String> {
        val ids = mutableListOf<String>()
        var current = node.parent
        var depth = 0
        while (current != null && depth < MAX_ANCESTOR_DEPTH) {
            current.viewIdResourceName?.takeIf { it.isNotBlank() }?.let { ids.add(it) }
            val next = current.parent
            // 只在无障碍服务线程里拿到的节点由系统池管理，这里不主动 recycle 父节点：
            // recycle 会连带回收子节点引用，导致后续读取抛 IllegalStateException
            current = next
            depth++
        }
        return ids
    }

    /** 同屏是否存在广告标识文本（广度优先，节点数有上限） */
    private fun hasAdMarker(root: AccessibilityNodeInfo?): Boolean {
        if (root == null) return false
        var visited = 0
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty() && visited < MAX_SCAN_NODES) {
            val node = queue.removeFirst()
            visited++
            val text = node.text?.toString()
            val desc = node.contentDescription?.toString()
            if (containsMarker(text) || containsMarker(desc)) return true
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return false
    }

    private fun containsMarker(raw: String?): Boolean {
        if (raw.isNullOrBlank() || raw.length > 24) return false
        val lower = raw.lowercase()
        if (AD_MARKERS.any { lower.contains(it) }) return true
        // 独立单词 AD / Ad（避免把 "load"、"head" 之类的子串当广告标识）
        return Regex("(^|[^a-z])ad([^a-z]|$)").containsMatchIn(lower)
    }
}
