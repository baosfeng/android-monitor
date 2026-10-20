package com.bsfeng.monitor.finance.ui

import com.bsfeng.monitor.finance.CategoryTree
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.CategoryCatalog
import com.bsfeng.monitor.finance.model.CategorySeed
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 两级分类选择器纯逻辑测试（[CategoryPickerState]，纯 JVM）。
 *
 * 验收点来自 docs/记账/分类体系设计.md「测试」：一级切换、二级选择、不细分、
 * 识别到二级时初始定位、未知选中回退第一个一级、空选中。
 */
class CategoryPickerTest {

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

    private fun tree(): CategoryTree =
        CategoryTree(build(0, CategoryCatalog.expense) + build(1, CategoryCatalog.income))

    // ===== 一级切换 =====

    @Test
    fun `默认高亮第一个一级`() {
        val state = CategoryPickerState(tree(), type = 0)
        assertEquals("餐饮", state.activeTopName)
        assertEquals(0, state.activeTopIndex)
        assertTrue(state.children.contains("外卖"))
        assertEquals("", state.selectedChildName)
    }

    @Test
    fun `点一级只切换展示不动选中值`() {
        val base = CategoryPickerState(tree(), type = 0, selectedName = "外卖")
        assertEquals("餐饮", base.activeTopName)
        assertEquals("外卖", base.selectedChildName)

        val switched = base.selectTop("购物")
        // 切换后网格换成「购物」的二级，原选中项（外卖）不再高亮
        assertEquals("购物", switched.activeTopName)
        assertEquals(listOf("电商", "超市便利", "家居日用", "礼品", "其他购物"), switched.children)
        assertEquals("", switched.selectedChildName)
        // 选中值本身不被切换动作改掉
        assertEquals("外卖", switched.selectedName)
        assertNotEquals(base.activeTopIndex, switched.activeTopIndex)
    }

    @Test
    fun `一级之间来回切换互不干扰`() {
        val tree = tree()
        val food = CategoryPickerState(tree, type = 0, selectedName = "餐饮")
        val shopping = food.selectTop("购物")
        val back = shopping.selectTop("餐饮")
        assertEquals("餐饮", back.activeTopName)
        assertTrue(back.isTopLevelOnlySelected)
        assertEquals("购物", shopping.activeTopName)
    }

    // ===== 二级选择 / 不细分 =====

    @Test
    fun `选二级回传二级名`() {
        val state = CategoryPickerState(tree(), type = 0, selectedName = "购物")
        assertEquals("电商", state.chooseChild("电商"))
        assertEquals("外卖", state.selectTop("餐饮").chooseChild("外卖"))
    }

    @Test
    fun `选不属于当前一级的二级名返回空串`() {
        val state = CategoryPickerState(tree(), type = 0, selectedName = "餐饮")
        // 「电商」是购物下的二级；跨一级的名字不能被回传（否则会落出父子错位的分类）
        assertEquals("", state.chooseChild("电商"))
        assertEquals("", state.chooseChild("不存在的分类"))
    }

    @Test
    fun `不细分回传当前一级名`() {
        val state = CategoryPickerState(tree(), type = 0)
        assertEquals("餐饮", state.chooseTopOnly())
        assertEquals("购物", state.selectTop("购物").chooseTopOnly())
    }

    // ===== 识别项初始定位 =====

    @Test
    fun `识别到二级时定位到它的一级并高亮`() {
        // 自动记账识别到「购物·电商」
        val state = CategoryPickerState(tree(), type = 0, selectedName = "电商")
        assertEquals("购物", state.activeTopName)
        assertEquals("购物", state.selectedTopName)
        assertEquals("电商", state.selectedChildName)
        assertEquals(1, state.activeTopIndex)
        // 定位后「不细分」不高亮：用户记的是二级
        assertEquals(false, state.isTopLevelOnlySelected)
        // 切换一级再切回来，选中项仍在
        assertEquals("电商", state.selectTop("餐饮").selectTop("购物").selectedChildName)
    }

    @Test
    fun `识别到一级时该一级的网格高亮不细分`() {
        // 识别映射对未知商户兜底一级「其他」
        val state = CategoryPickerState(tree(), type = 0, selectedName = "其他")
        assertEquals("其他", state.activeTopName)
        assertEquals("其他", state.selectedTopName)
        assertEquals("", state.selectedChildName)
        assertTrue(state.isTopLevelOnlySelected)
        assertEquals("其他", state.chooseTopOnly())
    }

    @Test
    fun `识别到收入二级时定位收入一级`() {
        val state = CategoryPickerState(tree(), type = 1, selectedName = "基金")
        assertEquals("理财收益", state.activeTopName)
        assertEquals("基金", state.selectedChildName)
        // 收入树里没有「工资」以外的支出分类，也不会串到支出树
        assertEquals(8, state.topLevel.size)
    }

    // ===== 未知 / 空选中 =====

    @Test
    fun `未知选中回退第一个一级`() {
        val state = CategoryPickerState(tree(), type = 0, selectedName = "出行")
        // 「出行」是分类体系升级前的旧名：不在树里，不强行归到某个一级
        assertEquals("餐饮", state.activeTopName)
        assertEquals("", state.selectedTopName)
        assertEquals("", state.selectedChildName)
        assertEquals(false, state.isTopLevelOnlySelected)
    }

    @Test
    fun `空选中回退第一个一级且无高亮`() {
        val state = CategoryPickerState(tree(), type = 0, selectedName = "")
        assertEquals("餐饮", state.activeTopName)
        assertEquals("", state.selectedTopName)
        assertEquals("", state.selectedChildName)
        assertEquals(false, state.isTopLevelOnlySelected)
        assertEquals("餐饮", state.chooseTopOnly())
    }

    @Test
    fun `选中项属于另一个类型时按未知处理`() {
        // 支出选择器拿到收入分类名（历史数据 / 类型切换），不能高亮支出里的任何一级
        val state = CategoryPickerState(tree(), type = 0, selectedName = "工资")
        assertEquals("餐饮", state.activeTopName)
        assertEquals("", state.selectedTopName)
    }

    @Test
    fun `空分类树不崩溃`() {
        val state = CategoryPickerState(CategoryTree(emptyList()), type = 0, selectedName = "电商")
        assertEquals("", state.activeTopName)
        assertTrue(state.topLevel.isEmpty())
        assertTrue(state.children.isEmpty())
        assertEquals("", state.chooseTopOnly())
        assertEquals("", state.chooseChild("电商"))
    }
}
