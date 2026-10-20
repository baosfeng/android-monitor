package com.bsfeng.monitor.finance

import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.CategoryCatalog

/**
 * 分类树：一级/二级的父子映射与一级聚合（纯逻辑，可 JVM 单测）。
 *
 * 交易 `category_name` 可能是二级名（有细分）或一级名（只记一级）；统计与预算只按一级汇总，
 * 都通过这里换算。分类表中不存在的名字（历史脏数据）归到 [CategoryCatalog.FALLBACK]。
 */
class CategoryTree(private val categories: List<Category>) {

    private val byName: Map<String, Category> = categories.associateBy { it.name }
    private val byId: Map<Long, Category> = categories.associateBy { it.id }

    /** 某类型的一级分类（按 sortOrder） */
    fun topLevel(type: Int): List<Category> =
        categories.filter { it.parentId < 0 && it.type == type }.sortedBy { it.sortOrder }

    /** 一级分类下的二级（按 sortOrder） */
    fun childrenOf(topName: String): List<Category> {
        val top = byName[topName] ?: return emptyList()
        if (top.parentId >= 0) return emptyList()
        return categories.filter { it.parentId == top.id }.sortedBy { it.sortOrder }
    }

    /**
     * 这个名字属于哪个一级：二级返回父级名，一级返回自身。
     *
     * 分类表里找不到的名字**保留原名**而不是归「其他」：统计/预算不该把历史脏数据或导入分类
     * 强行并到一个分类里（会丢信息）；未知分类的兜底「其他」由迁移与识别映射负责。
     */
    fun topLevelNameOf(name: String): String {
        if (name.isBlank()) return CategoryCatalog.FALLBACK
        val category = byName[name] ?: return name
        if (category.parentId < 0) return category.name
        return byId[category.parentId]?.name ?: name
    }

    /** 把「分类名 → 金额」按一级归并 */
    fun aggregateByTopLevel(rows: Map<String, Double>): Map<String, Double> =
        rows.entries
            .groupBy { topLevelNameOf(it.key) }
            .mapValues { (_, entries) -> entries.sumOf { it.value } }

    /** 分类是否在树里（迁移/映射落库前的校验） */
    fun contains(name: String): Boolean = byName.containsKey(name)

    /** 某类型下所有二级名（自动记账映射校验用） */
    fun childNames(type: Int): List<String> =
        topLevel(type).flatMap { top -> childrenOf(top.name).map { it.name } }
}
