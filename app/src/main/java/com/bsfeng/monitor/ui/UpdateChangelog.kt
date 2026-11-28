package com.bsfeng.monitor.ui

// 更新记录的结构化：发布清单里的 changelog 是一串「- feat(scope): 标题」，
// 20 条原文直接铺在对话框里既不能扫也抓不到重点（用户反馈「更新太多时显示效果很差」）。
// 这里按 Conventional Commits 的类型归成「新增 / 修复 / 优化 / 重构 / 文档 / 测试 / 其他」七组。

/** 一类改动；枚举顺序 = 对话框里的展示顺序（先看新增与修复，文档/测试垫底）。 */
internal enum class UpdateChangeKind(val label: String) {
    FEATURE("新增"),
    FIX("修复"),
    PERF("优化"),
    REFACTOR("重构"),
    DOCS("文档"),
    TEST("测试"),
    OTHER("其他"),
}

/** 一条改动：类型 + 去掉前缀后的正文。 */
internal data class UpdateChange(val kind: UpdateChangeKind, val text: String)

/** 一组同类改动（空组不会出现在结果里）。 */
internal data class UpdateChangeGroup(val kind: UpdateChangeKind, val changes: List<UpdateChange>) {
    val size: Int get() = changes.size
}

/** 发布流程没写更新记录时的回落文案。 */
internal const val UPDATE_CHANGELOG_FALLBACK = "包含功能改进与问题修复。"

/** 行首的项目符号：`- xxx` / `* xxx`。 */
private val BULLET = Regex("^[-*]\\s*")

/** Conventional Commits 前缀：`feat: ` / `fix(trip): `；冒号兼容中英文。 */
private val TYPE_PREFIX = Regex("^([a-z]+)(\\([^)]*\\))?\\s*[:：]\\s*")

private fun kindOf(type: String): UpdateChangeKind = when (type) {
    "feat", "feature" -> UpdateChangeKind.FEATURE
    "fix", "bugfix", "hotfix" -> UpdateChangeKind.FIX
    "perf" -> UpdateChangeKind.PERF
    "refactor" -> UpdateChangeKind.REFACTOR
    "docs", "doc" -> UpdateChangeKind.DOCS
    "test", "tests" -> UpdateChangeKind.TEST
    else -> UpdateChangeKind.OTHER
}

/**
 * 更新记录 → 一条条改动。
 *
 * 解析规则（对不上就当「其他」原样保留，宁可多显示也不吞信息）：
 * - 去掉行首 `- ` / `* ` 与首尾空白，空行跳过；
 * - 认 Conventional Commits 的 `type(scope): 正文`，scope 丢弃（它是仓库内部路径，读者不需要）；
 * - 整段为空时回落到 [UPDATE_CHANGELOG_FALLBACK]。
 */
internal fun parseUpdateChangelog(changelog: String): List<UpdateChange> {
    val parsed = changelog.split('\n')
        .map { BULLET.replace(it.trim(), "") }
        .filter { it.isNotBlank() }
        .map { line ->
            val prefix = TYPE_PREFIX.find(line)
            if (prefix == null) {
                UpdateChange(UpdateChangeKind.OTHER, line)
            } else {
                val text = line.removeRange(prefix.range.first, prefix.range.last + 1).trim()
                UpdateChange(kindOf(prefix.groupValues[1].lowercase()), text)
            }
        }
        .filter { it.text.isNotBlank() }
    return parsed.ifEmpty { listOf(UpdateChange(UpdateChangeKind.OTHER, UPDATE_CHANGELOG_FALLBACK)) }
}

/** 按 [UpdateChangeKind] 的固定顺序分组；空组剔除，总数 = 各组之和。 */
internal fun groupUpdateChanges(changes: List<UpdateChange>): List<UpdateChangeGroup> =
    UpdateChangeKind.entries.mapNotNull { kind ->
        val items = changes.filter { it.kind == kind }
        if (items.isEmpty()) null else UpdateChangeGroup(kind, items)
    }
