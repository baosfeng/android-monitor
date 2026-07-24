package com.bsfeng.monitor.skip

import android.view.accessibility.AccessibilityNodeInfo
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

        return when (selector.combinator) {
            Combinator.CHILD -> {
                // A > B：对每个 A 节点找其直接子节点中匹配 B 的
                leftResults.flatMap { parent ->
                    val results = mutableListOf<AccessibilityNodeInfo>()
                    for (i in 0 until parent.childCount) {
                        try {
                            parent.getChild(i)?.let { child ->
                                if (matchNode(child, rightSel)) {
                                    results.add(child)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    results
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
                    val results = mutableListOf<AccessibilityNodeInfo>()
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
                                results.add(child)
                            } else {
                                child.recycle()
                            }
                        } catch (_: Exception) {}
                    }
                    results
                }
            }
            null -> leftResults
        }
    }

    /**
     * 查找单个叶子选择器匹配的节点。
     * 优先使用 Android 原生 API 加速。
     */
    private fun findLeaf(root: AccessibilityNodeInfo, selector: Selector): List<AccessibilityNodeInfo> {
        // 1) 优先使用 ID 加速
        val idCondition = selector.attributes.find { it.name == "id" && it.op == AttrOp.EQ }
        if (idCondition != null) {
            val nodes = root.findAccessibilityNodeInfosByViewId(idCondition.value)
            return nodes.filter { matchNode(it, selector) }
        }

        // 2) 使用文本加速
        val textCondition = selector.attributes.find { it.name == "text" && it.op == AttrOp.EQ }
        if (textCondition != null && textCondition.value.length >= 2) {
            val nodes = root.findAccessibilityNodeInfosByText(textCondition.value)
            return nodes.filter { matchNode(it, selector) }
        }

        // 3) 回退到遍历匹配
        val results = mutableListOf<AccessibilityNodeInfo>()
        collectMatchingNodes(root, selector, results)
        return results
    }

    /**
     * 遍历无障碍树，收集所有匹配选择器的节点。
     */
    private fun collectMatchingNodes(
        node: AccessibilityNodeInfo,
        selector: Selector,
        results: MutableList<AccessibilityNodeInfo>,
        depth: Int = 0
    ) {
        if (depth > 50) return // 防止栈溢出

        try {
            if (matchNode(node, selector)) {
                results.add(node)
                // 找到一个匹配后不返回——同级可能有多个
            }

            for (i in 0 until node.childCount) {
                try {
                    node.getChild(i)?.let { child ->
                        collectMatchingNodes(child, selector, results, depth + 1)
                        child.recycle()
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
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
}
