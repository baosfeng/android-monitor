package com.bsfeng.monitor.finance

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [CategoryMigration] 单元测试（纯 JVM）。
 *
 * 验收点：旧 22 个支出分类映射到设计里的新一级；收入分类走收入映射；
 * 未知名字归「其他」/「其他收入」，绝不留下游离分类。
 */
class CategoryMigrationTest {

    @Test
    fun `旧支出一级映射到新一级`() {
        val cases = mapOf(
            "餐饮" to "餐饮",
            "出行" to "交通",
            "打车" to "交通",
            "地铁火车" to "交通",
            "购物" to "购物",
            "日用" to "购物",
            "零食" to "餐饮",
            "水果" to "餐饮",
            "水电" to "居住",
            "房租还贷" to "居住",
            "居家生活" to "居住",
            "就医" to "医疗健康",
            "通讯" to "通讯",
            "宽带话费" to "通讯",
            "衣物" to "服饰美妆",
            "美妆" to "服饰美妆",
            "休闲娱乐" to "休闲娱乐",
            "住宿" to "旅游",
            "旅游度假" to "旅游",
            "汽车" to "汽车",
            "手机数码" to "数码电子",
            "教育" to "教育学习",
            "其他" to "其他",
        )
        for ((old, expected) in cases) {
            assertEquals("旧分类 $old 应归到 $expected", expected, CategoryMigration.mapLegacy(old, 0))
        }
    }

    @Test
    fun `旧收入一级映射到新收入一级`() {
        assertEquals("工资", CategoryMigration.mapLegacy("薪资", 1))
        assertEquals("工资", CategoryMigration.mapLegacy("补助", 1))
        assertEquals("理财收益", CategoryMigration.mapLegacy("基金", 1))
        assertEquals("理财收益", CategoryMigration.mapLegacy("股票", 1))
        assertEquals("其他收入", CategoryMigration.mapLegacy("礼物", 1))
        assertEquals("退款", CategoryMigration.mapLegacy("退款", 1))
        assertEquals("其他收入", CategoryMigration.mapLegacy("其他", 1))
    }

    @Test
    fun `未知分类按类型兜底`() {
        assertEquals("其他", CategoryMigration.mapLegacy("某个平台自定义分类", 0))
        assertEquals("其他收入", CategoryMigration.mapLegacy("某个平台自定义收入", 1))
        assertEquals("其他", CategoryMigration.mapLegacy("", 0))
        assertEquals("其他收入", CategoryMigration.mapLegacy("", 1))
    }

    @Test
    fun `已经是新分类的二级也能找到一级`() {
        assertEquals("购物", CategoryMigration.mapLegacy("电商", 0))
        assertEquals("餐饮", CategoryMigration.mapLegacy("外卖", 0))
        assertEquals("工资", CategoryMigration.mapLegacy("基本工资", 1))
    }
}
