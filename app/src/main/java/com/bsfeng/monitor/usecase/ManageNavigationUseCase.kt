package com.bsfeng.monitor.usecase

import com.bsfeng.monitor.domain.NavigationTracker
import com.bsfeng.monitor.domain.model.NavChangeResult
import com.bsfeng.monitor.domain.model.NavigationNode

/**
 * 导航管理用例 — 封装导航栈操作。
 * 纯业务逻辑，不依赖 Android 框架，可在 JVM 单元测试中直接测试。
 */
class ManageNavigationUseCase(
    private val navigationTracker: NavigationTracker
) {

    fun onAppSwitch(packageName: String, appName: String, className: String? = null): NavChangeResult {
        return navigationTracker.onAppSwitch(packageName, appName, className)
    }

    fun onActivityChange(
        packageName: String,
        appName: String,
        className: String,
        pageTitle: String?
    ): NavChangeResult {
        return navigationTracker.onActivityChange(packageName, appName, className, pageTitle)
    }

    fun saveState(packageName: String): List<NavigationNode> {
        return navigationTracker.saveState(packageName)
    }

    fun restoreState(packageName: String, state: List<NavigationNode>) {
        navigationTracker.restoreState(packageName, state)
    }

    fun getPathAsString(packageName: String): String {
        return navigationTracker.getPathAsString(packageName)
    }

    fun getCurrentPath(packageName: String): List<NavigationNode> {
        return navigationTracker.getCurrentPath(packageName)
    }

    /** 构建导航链路的层级映射（nav_level_N / nav_level_N_type） */
    fun buildLevelMap(path: List<NavigationNode>): Map<String, String> {
        val map = mutableMapOf<String, String>()
        for (i in path.indices) {
            val level = i + 1
            val node = path[i]
            map["nav_level_$level"] = node.name
            map["nav_level_${level}_type"] = node.type
        }
        return map
    }

    /** 构建导航摘要文本 */
    fun buildSummary(path: List<NavigationNode>, pageTitle: String?): String {
        val pathString = path.joinToString(" > ") { it.name }
        return buildString {
            append("导航：$pathString")
            if (pageTitle != null && path.lastOrNull()?.name != pageTitle) {
                append("（$pageTitle）")
            }
        }
    }
}
