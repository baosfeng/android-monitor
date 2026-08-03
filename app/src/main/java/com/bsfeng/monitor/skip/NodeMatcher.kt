package com.bsfeng.monitor.skip

import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.domain.model.AttrCondition
import com.bsfeng.monitor.domain.model.AttrOp
import com.bsfeng.monitor.domain.model.Selector
import com.bsfeng.monitor.domain.model.Combinator
import java.util.regex.Pattern

/**
 * 无障碍节点匹配器。
 *
 * 根据 [Selector] 在 AccessibilityNodeInfo 树中查找匹配的目标节点。
 * 优先使用 Android 原生 API（findAccessibilityNodeInfosByViewId / ByText）加速匹配。
 */
object NodeMatcher {

    /**
     * 在无障碍树中查找匹配选择器的节点。
     *
     * @param root 根节点
     * @param selector 选择器
     * @return 匹配的节点列表（按匹配度排序）
     */
    fun find(root: AccessibilityNodeInfo?, selector: Selector): List<AccessibilityNodeInfo> {
        if (root == null) return emptyList()

        // 先解析右侧组合器
        if (!selector.isLeaf) {
            return findWithCombinator(root, selector)
        }

        // 叶子选择器：直接查找
        return findLeaf(root, selector)
    }

    /**
     * 使用组合器查找：先找左侧匹配节点，再对每个结果应用组合器+右侧选择器。
     */
    private fun findWithCombinator(root: AccessibilityNodeInfo, selector: Selector): List<AccessibilityNodeInfo> {
        val leftResults = findLeaf(root, selector)
        val rightSel = selector.right ?: return leftResults

        val results = when (selector.combinator) {
            Combinator.CHILD -> {
                // A > B：对每个 A 节点找其直接子节点中匹配 B 的
                leftResults.flatMap { parent ->
                    val childResults = mutableListOf<AccessibilityNodeInfo>()
                    for (i in 0 until parent.childCount) {
                        try {
                            val child = parent.getChild(i) ?: continue
                            if (matchNode(child, rightSel)) {
                                childResults.add(child)
                            } else {
                                child.recycle()
                            }
                        } catch (_: Exception) {}
                    }
                    childResults
                }
            }
            Combinator.DESCENDANT -> {
                // A B：对每个 A 节点找其后代中匹配 B 的
                leftResults.flatMap { parent ->
                    findLeaf(parent, rightSel)
                }
            }
            Combinator.SIBLING -> {
                // A + B：对每个 A 节点找其下一个兄弟节点中匹配 B 的
                leftResults.flatMap { sibling ->
                    val parent = sibling.parent ?: return@flatMap emptyList<AccessibilityNodeInfo>()
                    try {
                        val siblingResults = mutableListOf<AccessibilityNodeInfo>()
                        var foundSibling = false
                        for (i in 0 until parent.childCount) {
                            try {
                                val child = parent.getChild(i) ?: continue
                                if (child == sibling) {
                                    foundSibling = true
                                    child.recycle()
                                    continue
                                }
                                if (foundSibling && matchNode(child, rightSel)) {
                                    siblingResults.add(child)
                                } else {
                                    child.recycle()
                                }
                            } catch (_: Exception) {}
                        }
                        siblingResults
                    } finally {
                        parent.recycle()
                    }
                }
            }
            null -> leftResults
        }

        // 组合器已消费 leftResults（null 分支直接返回，不在此回收）
        if (selector.combinator != null) {
            recycleAll(leftResults)
        }
        return results
    }

    /**
     * 查找单个叶子选择器匹配的节点。
     * 优先使用 Android 原生 API 加速。
     */
    private fun findLeaf(root: AccessibilityNodeInfo, selector: Selector): List<AccessibilityNodeInfo> {
        // 1) 优先使用 ID 加速
        val idCondition = selector.attributes.find { it.name == "id" && it.op == AttrOp.EQ }
        if (idCondition != null) {
            return filterAndRecycle(root.findAccessibilityNodeInfosByViewId(idCondition.value), selector)
        }

        // 2) 使用文本加速
        val textCondition = selector.attributes.find { it.name == "text" && it.op == AttrOp.EQ }
        if (textCondition != null && textCondition.value.length >= 2) {
            return filterAndRecycle(root.findAccessibilityNodeInfosByText(textCondition.value), selector)
        }

        // 3) 回退到遍历匹配
        val results = mutableListOf<AccessibilityNodeInfo>()
        collectMatchingNodes(root, selector, results)
        return results
    }

    /**
     * 过滤原生 API 返回的节点：匹配的保留（调用方负责回收），未匹配的立即回收。
     */
    private fun filterAndRecycle(
        nodes: List<AccessibilityNodeInfo>,
        selector: Selector
    ): List<AccessibilityNodeInfo> {
        val matched = mutableListOf<AccessibilityNodeInfo>()
        for (node in nodes) {
            try {
                if (matchNode(node, selector)) {
                    matched.add(node)
                } else {
                    node.recycle()
                }
            } catch (_: Exception) {
                node.recycle()
            }
        }
        return matched
    }

    /**
     * 遍历无障碍树，收集所有匹配选择器的节点。
     *
     * @return 本节点是否匹配（已加入结果集；匹配节点不回收，由调用方统一回收）
     */
    private fun collectMatchingNodes(
        node: AccessibilityNodeInfo,
        selector: Selector,
        results: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0
    ): Boolean {
        if (depth > 50) return false // 防止栈溢出

        val selfMatched = try {
            matchNode(node, selector)
        } catch (_: Exception) {
            false
        }
        if (selfMatched) {
            results.add(node)
            // 找到一个匹配后不返回——同级可能有多个
        }

        for (i in 0 until node.childCount) {
            try {
                val child = node.getChild(i) ?: continue
                val childMatched = collectMatchingNodes(child, selector, results, depth + 1)
                // 未匹配的子节点回收；匹配的已入结果集，由调用方统一回收
                if (!childMatched) {
                    child.recycle()
                }
            } catch (_: Exception) {}
        }
        return selfMatched
    }

    /**
     * 安全回收节点列表（忽略已回收/无效节点）。
     * 调用方对 [find] 返回的结果使用完毕后统一调用。
     */
    fun recycleAll(nodes: List<AccessibilityNodeInfo>) {
        for (node in nodes) {
            try {
                node.recycle()
            } catch (_: Exception) {}
        }
    }

    /**
     * 判断单个节点是否匹配选择器。
     */
    fun matchNode(node: AccessibilityNodeInfo, selector: Selector): Boolean {
        // 检查标签名
        if (selector.tag != null) {
            val className = node.className?.toString() ?: ""
            val shortName = className.substringAfterLast('.')
            // 支持简写（如 "TextView"）和全限定名（如 "android.widget.TextView"）
            if (shortName != selector.tag && className != selector.tag) {
                return false
            }
        }

        // 检查所有属性条件
        for (attr in selector.attributes) {
            if (!matchAttribute(node, attr)) return false
        }

        return true
    }

    /**
     * 匹配单个属性条件。
     */
    private fun matchAttribute(node: AccessibilityNodeInfo, attr: AttrCondition): Boolean {
        val actualValue = getAttributeValue(node, attr.name) ?: return false

        return when (attr.op) {
            AttrOp.EQ -> actualValue == attr.value
            AttrOp.CONTAINS -> actualValue.contains(attr.value, ignoreCase = true)
            AttrOp.STARTS_WITH -> actualValue.startsWith(attr.value, ignoreCase = true)
            AttrOp.ENDS_WITH -> actualValue.endsWith(attr.value, ignoreCase = true)
            AttrOp.REGEX -> {
                try {
                    Pattern.compile(attr.value, Pattern.CASE_INSENSITIVE).matcher(actualValue).find()
                } catch (_: Exception) { false }
            }
            AttrOp.NOT_EQ -> actualValue != attr.value
        }
    }

    /**
     * 从节点获取指定属性名的值。
     */
    private fun getAttributeValue(node: AccessibilityNodeInfo, name: String): String? {
        return when (name.lowercase()) {
            "text" -> node.text?.toString()
            "desc" -> node.contentDescription?.toString()
            "id" -> node.viewIdResourceName
            "vid" -> node.viewIdResourceName?.substringAfterLast('/')
            "clickable" -> node.isClickable.toString()
            "longclickable" -> node.isLongClickable.toString()
            "visible" -> node.isVisibleToUser.toString()
            "visibletouser" -> node.isVisibleToUser.toString()
            "enabled" -> node.isEnabled.toString()
            "checked" -> node.isChecked.toString()
            "focusable" -> node.isFocusable.toString()
            "selected" -> node.isSelected.toString()
            "classname" -> node.className?.toString()
            "package" -> node.packageName?.toString()
            "childcount" -> node.childCount.toString()
            "password" -> node.isPassword.toString()
            else -> null
        }
    }

    /** 页面结构诊断 — 桩实现 */
    fun diagnosePageStructure(rootNode: AccessibilityNodeInfo) {
        // 暂不实现
    }
}
