package com.bsfeng.monitor.skip

import android.graphics.Rect
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 无障碍树快照导出器。
 *
 * 把当前前台页面的 AccessibilityNodeInfo 树序列化为 JSON 文本并落盘，
 * 用于离线核对开屏跳过规则的选择器写法（对照真实页面的 id / vid / text / desc 等属性）。
 *
 * 设计要点：
 * - 仅使用 org.json（系统内置），不引入第三方依赖；
 * - 遍历深度以 [MAX_DEPTH] 封顶，避免异常深树导致栈溢出；
 * - 单个节点导出失败只跳过该节点并记日志，不影响整体导出；
 * - 本类内部自行回收 getChild 产生的节点，避免节点泄漏。
 */
object SnapshotExporter {

    private const val TAG = "SkipSnapshot"

    /** 遍历深度上限：达到该层级的节点只导出自身属性、不再展开子节点 */
    internal const val MAX_DEPTH = 60

    /** 快照输出目录名（相对 filesDir） */
    private const val SNAPSHOT_DIR_NAME = "snapshots"

    /** JSON 缩进空格数 */
    private const val INDENT_SPACES = 2

    private const val KEY_PACKAGE = "packageName"
    private const val KEY_CLASS = "className"
    private const val KEY_EXPORTED_AT = "exportedAt"
    private const val KEY_ROOT = "root"
    private const val KEY_CHILDREN = "children"

    /**
     * 无障碍树 → JSON 文本（缩进 2）。
     *
     * 顶层字段：packageName / className / exportedAt / root。
     * 节点字段：id / vid / name / text / desc / clickable / longClickable / focusable /
     * checkable / checked / editable / visibleToUser / left / top / right / bottom /
     * width / height / childCount / index / depth，子节点放在 children 数组。
     *
     * 省略规则：字符串属性（id / vid / name / text / desc）为 null 或空串时省略该键；
     * 布尔属性与几何属性恒定输出（false / 0 也算有效值）。
     * 其中 name 为控件类名（全限定名），vid 为资源 id 的短名（id 为全名）。
     *
     * @param root 根节点
     * @param packageName 前台应用包名
     * @param className 前台 Activity 类名
     * @return JSON 文本
     */
    fun toJson(root: AccessibilityNodeInfo, packageName: String, className: String): String {
        val document = JSONObject()
        document.put(KEY_PACKAGE, packageName)
        document.put(KEY_CLASS, className)
        document.put(KEY_EXPORTED_AT, System.currentTimeMillis())
        document.put(KEY_ROOT, buildNode(root, index = 0, depth = 0))
        return document.toString(INDENT_SPACES)
    }

    /**
     * 导出当前前台页面到 filesDir/snapshots/&lt;时间戳&gt;.json。
     *
     * @param context 上下文（取 filesDir）
     * @param root 根节点，为 null 时直接放弃导出
     * @param packageName 前台应用包名
     * @param className 前台 Activity 类名
     * @return 快照文件绝对路径；失败（根节点为空 / 目录不可写 / 写文件异常）返回 null 并打日志
     */
    fun exportCurrentPage(
        context: android.content.Context,
        root: AccessibilityNodeInfo?,
        packageName: String,
        className: String
    ): String? {
        if (root == null) {
            Log.w(TAG, "根节点为空，跳过快照导出")
            return null
        }
        return try {
            val dir = File(context.filesDir, SNAPSHOT_DIR_NAME)
            if (!dir.exists() && !dir.mkdirs()) {
                Log.e(TAG, "创建快照目录失败: ${dir.absolutePath}")
                return null
            }
            val file = File(dir, "${System.currentTimeMillis()}.json")
            file.writeText(toJson(root, packageName, className))
            Log.i(TAG, "快照已导出: ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "导出当前页面快照失败", e)
            null
        }
    }

    /**
     * 结构摘要单行文本，用于日志 / 调试面板。
     *
     * 格式：节点总数=N, 最大深度=N, 可点击节点数=N, 文本节点数=N, 耗时=Nms
     *
     * @param root 根节点
     * @return 单行摘要
     */
    fun summarize(root: AccessibilityNodeInfo): String {
        val startedAt = System.currentTimeMillis()
        val stats = TreeStats()
        collectStats(root, stats, depth = 0)
        val elapsed = System.currentTimeMillis() - startedAt
        return "节点总数=${stats.total}, 最大深度=${stats.maxDepth}, " +
            "可点击节点数=${stats.clickable}, 文本节点数=${stats.textNodes}, 耗时=${elapsed}ms"
    }

    // ========== 内部实现 ==========

    /**
     * 递归构造节点 JSON。
     *
     * @param node 当前节点
     * @param index 在父节点中的序号（根节点为 0）
     * @param depth 当前深度（根节点为 0）
     */
    private fun buildNode(node: AccessibilityNodeInfo, index: Int, depth: Int): JSONObject {
        val target = JSONObject()
        putNodeAttributes(target, node, index, depth)

        // 深度封顶：只导出自身属性，不再向下展开
        if (depth >= MAX_DEPTH) return target

        val childCount = safeValue(0) { node.childCount }
        val children = JSONArray()
        for (i in 0 until childCount) {
            val child = safeValue<AccessibilityNodeInfo?>(null) { node.getChild(i) } ?: continue
            try {
                children.put(buildNode(child, index = i, depth = depth + 1))
            } catch (e: Exception) {
                // 单节点失败只跳过该节点，不影响整体导出
                Log.w(TAG, "导出子节点失败: index=$i depth=${depth + 1}", e)
            } finally {
                recycleQuietly(child)
            }
        }
        if (children.length() > 0) target.put(KEY_CHILDREN, children)
        return target
    }

    /** 写入节点自身属性（失败的单条属性回退为默认值，不中断导出） */
    private fun putNodeAttributes(
        target: JSONObject,
        node: AccessibilityNodeInfo,
        index: Int,
        depth: Int
    ) {
        val resourceId = safeValue<String?>(null) { node.viewIdResourceName }
        putString(target, "id", resourceId)
        putString(target, "vid", resourceId?.substringAfterLast('/'))
        putString(target, "name", safeValue<String?>(null) { node.className?.toString() })
        putString(target, "text", safeValue<String?>(null) { node.text?.toString() })
        putString(target, "desc", safeValue<String?>(null) { node.contentDescription?.toString() })

        target.put("clickable", safeValue(false) { node.isClickable })
        target.put("longClickable", safeValue(false) { node.isLongClickable })
        target.put("focusable", safeValue(false) { node.isFocusable })
        target.put("checkable", safeValue(false) { node.isCheckable })
        target.put("checked", safeValue(false) { node.isChecked })
        target.put("editable", safeValue(false) { node.isEditable })
        target.put("visibleToUser", safeValue(false) { node.isVisibleToUser })

        putBounds(target, node)

        target.put("childCount", safeValue(0) { node.childCount })
        target.put("index", index)
        target.put("depth", depth)
    }

    /** 写入屏幕坐标与宽高（取不到时回退为 0） */
    private fun putBounds(target: JSONObject, node: AccessibilityNodeInfo) {
        val bounds = Rect()
        safeValue(Unit) { node.getBoundsInScreen(bounds) }
        target.put("left", bounds.left)
        target.put("top", bounds.top)
        target.put("right", bounds.right)
        target.put("bottom", bounds.bottom)
        target.put("width", bounds.width())
        target.put("height", bounds.height())
    }

    /** 字符串属性为空（null / 空串）时省略该键 */
    private fun putString(target: JSONObject, key: String, value: String?) {
        if (!value.isNullOrEmpty()) target.put(key, value)
    }

    /**
     * 递归统计结构信息（同样遵守 [MAX_DEPTH] 限制）。
     */
    private fun collectStats(node: AccessibilityNodeInfo, stats: TreeStats, depth: Int) {
        stats.total++
        if (depth > stats.maxDepth) stats.maxDepth = depth
        if (safeValue(false) { node.isClickable }) stats.clickable++
        val text = safeValue<String?>(null) { node.text?.toString() }
        if (!text.isNullOrEmpty()) stats.textNodes++

        if (depth >= MAX_DEPTH) return

        val childCount = safeValue(0) { node.childCount }
        for (i in 0 until childCount) {
            val child = safeValue<AccessibilityNodeInfo?>(null) { node.getChild(i) } ?: continue
            try {
                collectStats(child, stats, depth + 1)
            } catch (e: Exception) {
                Log.w(TAG, "统计子节点失败: index=$i depth=${depth + 1}", e)
            } finally {
                recycleQuietly(child)
            }
        }
    }

    /** 安全回收节点（忽略已回收 / 无效节点抛出的异常），防止节点泄漏 */
    private fun recycleQuietly(node: AccessibilityNodeInfo) {
        try {
            node.recycle()
        } catch (_: Exception) {
            // 已回收或无效节点，忽略
        }
    }

    /** 读取属性失败时回退到 [fallback]，保证单个坏节点不中断整体导出 */
    private inline fun <T> safeValue(fallback: T, block: () -> T): T =
        try {
            block()
        } catch (_: Exception) {
            fallback
        }

    /** 结构统计累加器 */
    private class TreeStats {
        var total = 0
        var maxDepth = 0
        var clickable = 0
        var textNodes = 0
    }
}
