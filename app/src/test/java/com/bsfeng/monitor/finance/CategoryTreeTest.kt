package com.bsfeng.monitor.finance

import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.CategoryCatalog
import com.bsfeng.monitor.finance.model.CategorySeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [CategoryTree] 与 [CategoryCatalog] 单元测试（纯 JVM）。
 *
 * 验收点：18 个支出一级 / 8 个收入一级、名称全局唯一、二级归一到一级、未知分类兜底「其他」。
 */
class CategoryTreeTest {

    private var nextId = 1L

    private fun build(type: Int, seeds: List<CategorySeed>): List<Category> {
        val list = mutableListOf<Category>()
        seeds.forEachIndexed { topIndex, seed ->
            val topId = nextId++
            list += Category(id = topId, name = seed.name, type = type, parentId = -1, sortOrder = topIndex)
            seed.children.forEachIndexed { childIndex, child ->
                list += Category(
                    id = nextId++,
                    name = child,
                    type = type,
                    parentId = topId,
                    sortOrder = childIndex,
                )
            }
        }
        return list
    }

    private fun tree() = CategoryTree(build(0, CategoryCatalog.expense) + build(1, CategoryCatalog.income))

    @Test
    fun `支出一级 18 个收入一级 8 个`() {
        val t = tree()
        assertEquals(18, t.topLevel(0).size)
        assertEquals(8, t.topLevel(1).size)
    }

    @Test
    fun `一级与二级名称全局唯一`() {
        val names = CategoryCatalog.allNames()
        assertEquals("分类名不能重复（分类表 UNIQUE(name)）", names.size, names.toSet().size)
        assertTrue(names.contains("电商"))
        assertTrue(names.contains("外卖"))
    }

    @Test
    fun `二级归到对应一级 一级返回自身 未知归其他`() {
        val t = tree()
        assertEquals("购物", t.topLevelNameOf("电商"))
        assertEquals("餐饮", t.topLevelNameOf("外卖"))
        assertEquals("购物", t.topLevelNameOf("购物"))
        assertEquals("未知分类保留原名，统计不强行归并", "随便写一个不存在的分类", t.topLevelNameOf("随便写一个不存在的分类"))
        assertEquals("其他", t.topLevelNameOf(""))
    }

    @Test
    fun `一级下的二级按顺序返回`() {
        val t = tree()
        val children = t.childrenOf("购物").map { it.name }
        assertEquals(listOf("电商", "超市便利", "家居日用", "礼品", "其他购物"), children)
        assertTrue(t.childrenOf("不存在的").isEmpty())
    }

    @Test
    fun `按一级聚合 二级与一级同名分类合并`() {
        val t = tree()
        val aggregated = t.aggregateByTopLevel(
            mapOf("电商" to 10.0, "购物" to 5.0, "外卖" to 3.0),
        )
        assertEquals(15.0, aggregated["购物"] ?: 0.0, 0.0001)
        assertEquals(3.0, aggregated["餐饮"] ?: 0.0, 0.0001)
    }

    @Test
    fun `分类存在性校验`() {
        val t = tree()
        assertTrue(t.contains("电商"))
        assertTrue(t.contains("购物"))
        assertFalse(t.contains("未知分类"))
        assertFalse(t.childNames(0).contains("购物"))
    }
}
