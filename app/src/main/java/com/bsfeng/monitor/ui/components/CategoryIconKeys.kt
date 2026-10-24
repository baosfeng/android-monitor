package com.bsfeng.monitor.ui.components

import com.bsfeng.monitor.finance.model.CategoryCatalog

// 从 ui/components/CategoryIcon.kt 拆出：分类名 → 图标 key 的两张映射表（一级 / 二级，含旧扁平名兼容）。
// 负责「分类叫什么」到「用哪个图标」的查表，key 的唯一性由 CategoryIconUniquenessTest 守护。

/** 一级分类（含收入）→ 图标 key；旧扁平名保留映射，历史数据也能显示图标 */
private val TOP_KEY: Map<String, String> = mapOf(
    "餐饮" to "food",
    "购物" to "shopping",
    "交通" to "transport",
    "汽车" to "car",
    "居住" to "home",
    "通讯" to "comm",
    "医疗健康" to "medical",
    "娱乐休闲" to "entertain",
    "旅游" to "travel",
    "教育学习" to "education",
    "人情往来" to "gift",
    "金融保险" to "finance",
    "服饰美妆" to "clothing",
    "母婴育儿" to "baby",
    "宠物" to "pet",
    "数码电子" to "digital",
    "运动健身" to "sport",
    "其他" to "daily",
    "工资" to "salary",
    "奖金" to "bonus",
    "兼职" to "parttime",
    "理财收益" to "invest",
    "红包" to "redpacket",
    "退款" to "refund",
    "报销" to "reimburse",
    "其他收入" to "income",
    // 旧扁平分类名（历史数据 / 原型演示）
    "出行" to "transport",
    "日用" to "daily",
    "手机数码" to "digital",
    "宽带话费" to "broadband",
    "零食" to "snack",
    "水果" to "fruit",
    "地铁火车" to "metro",
    "水电" to "utility",
    "就医" to "medical",
    "衣物" to "clothing",
    "美妆" to "beauty",
    "房租还贷" to "rent",
    "休闲娱乐" to "entertain",
    "打车" to "taxi",
    "住宿" to "hotel",
    "旅游度假" to "travel",
    "教育" to "education",
    "居家生活" to "home",
)

/**
 * 二级分类 → 专属图标 key（显式全量，不再继承父级）。
 *
 * 键集合必须与 [CategoryCatalog] 的二级分类一一对应；新增二级时同步补条目与
 * `ic_cat_<key>.xml`，否则唯一性测试失败。
 */
private val CHILD_KEY: Map<String, String> = mapOf(
    // 餐饮
    "外卖" to "takeout",
    "早餐" to "breakfast",
    "午餐" to "lunch",
    "晚餐" to "dinner",
    "正餐" to "meal",
    "咖啡奶茶" to "coffee",
    "零食" to "snack",
    "水果" to "fruit",
    "聚餐" to "gathering",
    "烟酒" to "smoke",
    // 购物
    "电商" to "ecommerce",
    "超市便利" to "grocery",
    "家居日用" to "household",
    "礼品" to "giftbox",
    "其他购物" to "bag",
    // 交通
    "打车" to "taxi",
    "公交地铁" to "metro",
    "火车高铁" to "train",
    "飞机" to "plane",
    "共享单车" to "bike",
    "长途客运" to "bus",
    // 汽车
    "加油" to "fuel",
    "停车" to "parking",
    "保养维修" to "wrench",
    "洗车" to "wash",
    "车险" to "carshield",
    "违章罚款" to "fine",
    "过路费" to "road",
    "车贷" to "carloan",
    // 居住
    "房租" to "rent",
    "房贷" to "mortgage",
    "物业" to "property",
    "水电燃气" to "utility",
    "宽带" to "broadband",
    "家政" to "cleaning",
    "维修" to "repair",
    "装修" to "paint",
    // 通讯
    "话费" to "phonebill",
    "流量" to "data",
    // 医疗健康
    "门诊" to "stethoscope",
    "药品" to "pill",
    "体检" to "checkup",
    "牙科" to "tooth",
    "住院" to "hospital",
    "保健品" to "supplement",
    // 娱乐休闲
    "电影" to "film",
    "游戏" to "game",
    "会员订阅" to "subscription",
    "演出" to "stage",
    "KTV" to "mic",
    "酒吧" to "bar",
    // 旅游
    "跟团游" to "tour",
    "自由行" to "diymap",
    "住宿" to "hotel",
    "景点门票" to "ticket",
    "签证" to "passport",
    // 教育学习
    "课程培训" to "course",
    "书籍" to "book",
    "考试报名" to "exam",
    "文具" to "pen",
    "学习工具" to "ruler",
    // 人情往来
    "发红包" to "redgift",
    "礼物" to "present",
    "请客" to "treat",
    "孝亲" to "eldercare",
    "捐赠" to "donate",
    // 金融保险
    "保险" to "insurance",
    "手续费" to "fee",
    "利息" to "interest",
    "投资" to "investing",
    "还款" to "repay",
    // 服饰美妆
    "衣服" to "shirt",
    "鞋包" to "shoes",
    "化妆品" to "cosmetic",
    "美容美发" to "salon",
    "美甲" to "nail",
    // 母婴育儿
    "奶粉" to "milkbottle",
    "尿布" to "diaper",
    "玩具" to "toy",
    "早教" to "blocks",
    "儿童衣物" to "kidclothes",
    // 宠物
    "宠物食品" to "petfood",
    "宠物用品" to "petitem",
    "宠物医疗" to "petcare",
    "宠物美容" to "petgroom",
    "寄养" to "petstay",
    // 数码电子
    "手机" to "phone",
    "电脑" to "laptop",
    "配件" to "cable",
    "软件" to "software",
    "云服务" to "cloud",
    // 运动健身
    "健身房" to "gym",
    "器材" to "equipment",
    "运动装备" to "runningshoe",
    "场地费" to "court",
    // 其他
    "未分类" to "unknown",
    "杂项" to "misc",
    "生活服务" to "servicebox",
    // 工资
    "基本工资" to "payslip",
    "绩效" to "gearcoin",
    "年终奖" to "yearend",
    "补贴" to "subsidy",
    // 奖金
    "绩效奖金" to "starcoin",
    "提成" to "commission",
    "项目奖金" to "trophy",
    // 兼职
    "兼职收入" to "clockcoin",
    "稿费" to "quill",
    "外快" to "boltcoin",
    // 理财收益
    "基金" to "fund",
    "股票" to "stock",
    "存款利息" to "deposit",
    "分红" to "dividend",
    // 红包
    "礼金" to "envelope",
    // 退款
    "购物退款" to "shoprefund",
    "服务退款" to "servicerefund",
    // 报销
    "差旅报销" to "travelclaim",
    "医疗报销" to "medicalclaim",
    "其他报销" to "claim",
    // 其他收入
    "意外收入" to "windfall",
)

/** 分类名 → 图标 key；未绘制图标的分类返回 null。 */
internal fun categoryIconKeyOf(categoryName: String): String? =
    CHILD_KEY[categoryName] ?: TOP_KEY[categoryName]
