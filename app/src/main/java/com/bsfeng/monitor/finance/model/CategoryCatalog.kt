package com.bsfeng.monitor.finance.model

/**
 * 一级分类种子：名称 + 可选二级子分类。
 *
 * 分类体系见 `docs/记账/分类体系设计.md`：一级覆盖日常场景，二级只是细分，记账可以只记一级。
 * 所有名称**全局唯一**（分类表有 UNIQUE(name)）；二级不得与任何一级同名。
 */
data class CategorySeed(
    val name: String,
    val children: List<String> = emptyList(),
)

/**
 * 内置分类目录（唯一来源）。
 *
 * 改这里即可同时影响：种子数据、迁移、分类树与自动记账映射的校验。
 * 二级命名避免与一级同名（例如人情往来用「发红包」，收入一级用「红包」）。
 */
object CategoryCatalog {

    /** 支出一级（18 个，顺序即展示顺序） */
    val expense: List<CategorySeed> = listOf(
        CategorySeed(
            "餐饮",
            listOf("外卖", "早餐", "午餐", "晚餐", "正餐", "咖啡奶茶", "零食", "水果", "聚餐", "烟酒"),
        ),
        CategorySeed("购物", listOf("电商", "超市便利", "家居日用", "礼品", "其他购物")),
        CategorySeed("交通", listOf("打车", "公交地铁", "火车高铁", "飞机", "共享单车", "长途客运")),
        CategorySeed(
            "汽车",
            listOf("加油", "停车", "保养维修", "洗车", "车险", "违章罚款", "过路费", "车贷"),
        ),
        CategorySeed(
            "居住",
            listOf("房租", "房贷", "物业", "水电燃气", "宽带", "家政", "维修", "装修"),
        ),
        CategorySeed("通讯", listOf("话费", "流量")),
        CategorySeed("医疗健康", listOf("门诊", "药品", "体检", "牙科", "住院", "保健品")),
        CategorySeed("娱乐休闲", listOf("电影", "游戏", "会员订阅", "演出", "KTV", "酒吧")),
        CategorySeed("旅游", listOf("跟团游", "自由行", "住宿", "景点门票", "签证")),
        CategorySeed("教育学习", listOf("课程培训", "书籍", "考试报名", "文具", "学习工具")),
        CategorySeed("人情往来", listOf("发红包", "礼物", "请客", "孝亲", "捐赠")),
        CategorySeed("金融保险", listOf("保险", "手续费", "利息", "投资", "还款")),
        CategorySeed("服饰美妆", listOf("衣服", "鞋包", "化妆品", "美容美发", "美甲")),
        CategorySeed("母婴育儿", listOf("奶粉", "尿布", "玩具", "早教", "儿童衣物")),
        CategorySeed("宠物", listOf("宠物食品", "宠物用品", "宠物医疗", "宠物美容", "寄养")),
        CategorySeed("数码电子", listOf("手机", "电脑", "配件", "软件", "云服务")),
        CategorySeed("运动健身", listOf("健身房", "器材", "运动装备", "场地费")),
        CategorySeed("其他", listOf("未分类", "杂项", "生活服务")),
    )

    /** 收入一级（8 个） */
    val income: List<CategorySeed> = listOf(
        CategorySeed("工资", listOf("基本工资", "绩效", "年终奖", "补贴")),
        CategorySeed("奖金", listOf("绩效奖金", "提成", "项目奖金")),
        CategorySeed("兼职", listOf("兼职收入", "稿费", "外快")),
        CategorySeed("理财收益", listOf("基金", "股票", "存款利息", "分红")),
        CategorySeed("红包", listOf("礼金")),
        CategorySeed("退款", listOf("购物退款", "服务退款")),
        CategorySeed("报销", listOf("差旅报销", "医疗报销", "其他报销")),
        CategorySeed("其他收入", listOf("意外收入")),
    )

    /** 兜底一级分类名（迁移与识别映射的落点） */
    const val FALLBACK = "其他"

    /** 所有支出/收入分类名（一级 + 二级），用于校验唯一性与存在性 */
    fun allNames(): List<String> =
        (expense + income).flatMap { seed -> listOf(seed.name) + seed.children }
}
