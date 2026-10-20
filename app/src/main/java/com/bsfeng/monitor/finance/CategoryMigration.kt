package com.bsfeng.monitor.finance

import com.bsfeng.monitor.finance.model.CategoryCatalog

/**
 * 旧扁平分类 → 新一级分类的映射（纯逻辑，可 JVM 单测）。
 *
 * 用户已确认历史账目「只归一级」：旧交易/预算的 `category_name` 统一改到新一级，
 * 不猜测二级。旧分类表（餐饮/出行/购物…）与新一级不完全同名（出行→交通、就医→医疗健康…），
 * 所以必须显式映射；映射不到的名字归「其他」（收入归「其他收入」），绝不留下游离分类名。
 */
object CategoryMigration {

    /** 旧支出一级 → 新支出一级 */
    private val EXPENSE_MAP = mapOf(
        "餐饮" to "餐饮",
        "出行" to "交通",
        "购物" to "购物",
        "日用" to "购物",
        "零食" to "餐饮",
        "水果" to "餐饮",
        "水电" to "居住",
        "就医" to "医疗健康",
        "通讯" to "通讯",
        "衣物" to "服饰美妆",
        "美妆" to "服饰美妆",
        "房租还贷" to "居住",
        "休闲娱乐" to "休闲娱乐",
        "打车" to "交通",
        "住宿" to "旅游",
        "旅游度假" to "旅游",
        "汽车" to "汽车",
        "手机数码" to "数码电子",
        "居家生活" to "居住",
        "教育" to "教育学习",
        "宽带话费" to "通讯",
        "地铁火车" to "交通",
        "其他" to "其他",
    )

    /** 旧收入一级 → 新收入一级 */
    private val INCOME_MAP = mapOf(
        "薪资" to "工资",
        "奖金" to "奖金",
        "补助" to "工资",
        "报销" to "报销",
        "红包" to "红包",
        "理财" to "理财收益",
        "股票" to "理财收益",
        "基金" to "理财收益",
        "兼职" to "兼职",
        "礼物" to "其他收入",
        "退款" to "退款",
        "其他" to "其他收入",
    )

    /**
     * 把旧分类名映射到新一级。
     *
     * @param type 0=支出 / 1=收入（交易 type=2/3 应先折算到支出）
     */
    fun mapLegacy(name: String, type: Int): String {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return fallbackFor(type)
        val catalogTop = topLevelInCatalog(trimmed, type)
        if (catalogTop != null) return catalogTop
        val legacy = if (type == 1) INCOME_MAP[trimmed] else EXPENSE_MAP[trimmed]
        return legacy ?: fallbackFor(type)
    }

    /** 这个名字在新分类目录里属于哪个一级（本身是二级也能找到父级） */
    private fun topLevelInCatalog(name: String, type: Int): String? {
        val seeds = if (type == 1) CategoryCatalog.income else CategoryCatalog.expense
        seeds.firstOrNull { it.name == name }?.let { return it.name }
        seeds.firstOrNull { it.children.contains(name) }?.let { return it.name }
        return null
    }

    private fun fallbackFor(type: Int): String =
        if (type == 1) "其他收入" else CategoryCatalog.FALLBACK
}
