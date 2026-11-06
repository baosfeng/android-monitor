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
            listOf(
                "外卖", "早餐", "午餐", "晚餐", "正餐", "咖啡奶茶", "零食", "水果", "聚餐", "烟酒",
                // 第二批：高频细分（买菜 / 茶饮点心 / 夜宵 / 瓶装饮品 / 烘焙）
                "食材", "下午茶", "夜宵", "饮料", "烘焙甜品",
            ),
        ),
        CategorySeed(
            "购物",
            listOf(
                "电商", "超市便利", "家居日用", "礼品", "其他购物",
                // 第二批：日用品按品类细分
                "纸品清洁", "洗护个护", "厨卫用品", "家电", "家纺", "五金工具",
            ),
        ),
        CategorySeed(
            "交通",
            listOf(
                "打车", "公交地铁", "火车高铁", "飞机", "共享单车", "长途客运",
                "代驾", "租车", "船票",
            ),
        ),
        CategorySeed(
            "汽车",
            listOf(
                "加油", "停车", "保养维修", "洗车", "车险", "违章罚款", "过路费", "车贷",
                // 第二批：电车与养车周边
                "电车充电", "充电桩安装", "年检", "车载用品", "救援拖车",
            ),
        ),
        CategorySeed(
            "居住",
            listOf(
                "房租", "房贷", "物业", "水电燃气", "宽带", "家政", "维修", "装修",
                "供暖费", "车位租金", "房屋押金",
            ),
        ),
        CategorySeed("通讯", listOf("话费", "流量", "手机套餐")),
        CategorySeed(
            "医疗健康",
            listOf("门诊", "药品", "体检", "牙科", "住院", "保健品", "中医理疗", "配镜", "疫苗"),
        ),
        CategorySeed(
            "娱乐休闲",
            listOf("电影", "游戏", "会员订阅", "演出", "KTV", "酒吧", "游乐场", "剧本杀", "桌游", "打赏"),
        ),
        CategorySeed(
            "旅游",
            listOf("跟团游", "自由行", "住宿", "景点门票", "签证", "露营", "景区交通", "旅行购物"),
        ),
        CategorySeed(
            "教育学习",
            listOf("课程培训", "书籍", "考试报名", "文具", "学习工具", "网课", "兴趣班", "家教"),
        ),
        CategorySeed("人情往来", listOf("发红包", "礼物", "请客", "孝亲", "捐赠", "随礼", "探病")),
        CategorySeed("金融保险", listOf("保险", "手续费", "利息", "投资", "还款", "税费", "汇兑", "账户管理费")),
        CategorySeed("服饰美妆", listOf("衣服", "鞋包", "化妆品", "美容美发", "美甲", "配饰", "内衣", "干洗")),
        CategorySeed("母婴育儿", listOf("奶粉", "尿布", "玩具", "早教", "儿童衣物", "辅食", "婴儿用品", "儿童医疗", "儿童课程")),
        CategorySeed("宠物", listOf("宠物食品", "宠物用品", "宠物医疗", "宠物美容", "寄养", "宠物训练", "宠物保险")),
        CategorySeed("数码电子", listOf("手机", "电脑", "配件", "软件", "云服务", "相机", "游戏机", "数码维修")),
        CategorySeed("运动健身", listOf("健身房", "器材", "运动装备", "场地费", "游泳", "球类课程", "赛事报名")),
        CategorySeed("其他", listOf("未分类", "杂项", "生活服务", "快递", "打印")),
    )

    /** 收入一级（8 个） */
    val income: List<CategorySeed> = listOf(
        CategorySeed("工资", listOf("基本工资", "绩效", "年终奖", "补贴", "加班费")),
        CategorySeed("奖金", listOf("绩效奖金", "提成", "项目奖金")),
        CategorySeed("兼职", listOf("兼职收入", "稿费", "外快")),
        CategorySeed("理财收益", listOf("基金", "股票", "存款利息", "分红", "公积金", "债券")),
        CategorySeed("红包", listOf("礼金")),
        CategorySeed("退款", listOf("购物退款", "服务退款")),
        CategorySeed("报销", listOf("差旅报销", "医疗报销", "其他报销")),
        CategorySeed("其他收入", listOf("意外收入", "房租收入", "中奖", "变卖")),
    )

    /**
     * 分类目录版本：**增删分类时递增**，用于触发存量安装的补齐同步。
     *
     * - 1：首批目录（26 一级 + 119 二级）
     * - 2：第二批扩容（新增 64 个二级，共 26 一级 + 183 二级）
     *
     * [com.bsfeng.monitor.finance.FinanceManager.syncCatalogOnce] 以它作为一次性标记的一部分，
     * 升级后第一次启动会把新增分类补进已存在的 finance.db。
     */
    const val VERSION = 2

    /** 兜底一级分类名（迁移与识别映射的落点） */
    const val FALLBACK = "其他"

    /** 所有支出/收入分类名（一级 + 二级），用于校验唯一性与存在性 */
    fun allNames(): List<String> =
        (expense + income).flatMap { seed -> listOf(seed.name) + seed.children }
}
