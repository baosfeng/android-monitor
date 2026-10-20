package com.bsfeng.monitor.finance.model

/**
 * 交易分类。
 *
 * @param type 0=支出分类, 1=收入分类
 * @param parentId 父分类ID, -1 表示顶级分类
 */
data class Category(
    val id: Long = 0,
    val name: String = "",
    val type: Int = 0,
    val iconName: String = "",
    val color: String = "",
    val parentId: Long = -1,
    val sortOrder: Int = 0
) {
    val isExpenseType: Boolean get() = type == 0
    val isIncomeType: Boolean get() = type == 1

    /** 一级分类（parent_id = -1）；二级分类指向一级的 id */
    val isTopLevel: Boolean get() = parentId < 0

    companion object {
        /** 预设支出分类 */
        val defaultExpenseCategories = listOf(
            "餐饮", "出行", "购物", "日用", "零食", "水果",
            "水电", "就医", "通讯", "衣物", "美妆", "房租还贷",
            "休闲娱乐", "打车", "住宿", "旅游度假", "汽车",
            "手机数码", "居家生活", "教育", "宽带话费", "地铁火车"
        )

        /** 预设收入分类 */
        val defaultIncomeCategories = listOf(
            "薪资", "奖金", "补助", "报销", "红包",
            "理财", "股票", "基金", "兼职", "礼物", "退款", "其他"
        )
    }
}
