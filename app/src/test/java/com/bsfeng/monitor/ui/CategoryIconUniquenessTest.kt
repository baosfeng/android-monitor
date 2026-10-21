package com.bsfeng.monitor.ui

import com.bsfeng.monitor.finance.model.CategoryCatalog
import com.bsfeng.monitor.ui.components.CategoryIcons
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 分类图标唯一性：145 个分类（26 个一级 + 119 个二级）每个都有独立 key 与独立 drawable。
 *
 * 约束：任意两个分类的图标 key 不同、drawable 资源 id 不同，避免「二级复用父级图标」
 * 导致列表里出现肉眼无法区分的重复图标。资源存在性由 R.drawable 常量断言覆盖
 * （编译期生成，纯 JVM 单测即可，不依赖 Robolectric）。
 */
class CategoryIconUniquenessTest {

    private val names: List<String> = CategoryCatalog.allNames()

    @Test
    fun `分类总数与图标覆盖基数一致`() {
        assertEquals(145, names.size)
        assertEquals(26, CategoryCatalog.expense.size + CategoryCatalog.income.size)
    }

    @Test
    fun `每个分类都映射到图标 key`() {
        val missing = names.filter { CategoryIcons.keyOf(it) == null }
        assertTrue("以下分类未映射图标 key：$missing", missing.isEmpty())
    }

    @Test
    fun `图标 key 全局互不重复`() {
        val byKey = names.groupBy { CategoryIcons.keyOf(it) }
        val duplicates = byKey.filterKeys { it != null }.filterValues { it.size > 1 }
        assertTrue("以下分类共用了同一个图标 key：$duplicates", duplicates.isEmpty())
        assertEquals(names.size, names.mapNotNull { CategoryIcons.keyOf(it) }.distinct().size)
    }

    @Test
    fun `每个图标 key 都有独立 drawable 资源`() {
        val resolved = names.mapNotNull { name ->
            CategoryIcons.keyOf(name)?.let { key -> name to (key to CategoryIcons.drawableOf(key)) }
        }
        val missing = resolved.filter { it.second.second == 0 }.map { it.first }
        assertTrue("以下分类的 key 没有对应 drawable：$missing", missing.isEmpty())
        val byRes = resolved.groupBy { it.second.second }.filterValues { it.size > 1 }
        assertTrue("以下分类共用了同一个 drawable：$byRes", byRes.isEmpty())
    }
}
