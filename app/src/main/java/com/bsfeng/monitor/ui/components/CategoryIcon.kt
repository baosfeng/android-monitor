package com.bsfeng.monitor.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.R
import com.bsfeng.monitor.finance.model.CategoryCatalog
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 分类图标名 ↔ 真机 finance_categories.name 的映射与 drawable 解析。
 *
 * 图标为 24x24 viewBox 线描（`strokeWidth 1.7`、`fill=none`、圆角端点），
 * 每个分类（一级 + 二级）都有**独立 key 与独立 drawable**，二级不再继承父级图标；
 * 唯一性由 `CategoryIconUniquenessTest` 守护（145 个分类 ↔ 145 个不同资源）。
 */
internal object CategoryIcons {

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
    fun keyOf(categoryName: String): String? = CHILD_KEY[categoryName] ?: TOP_KEY[categoryName]

    /** 图标 key → drawable 资源；key 非法返回 0（调用方回落）。 */
    fun drawableOf(key: String): Int = when (key) {
        // 一级
        "food" -> R.drawable.ic_cat_food
        "shopping" -> R.drawable.ic_cat_shopping
        "transport" -> R.drawable.ic_cat_transport
        "car" -> R.drawable.ic_cat_car
        "home" -> R.drawable.ic_cat_home
        "comm" -> R.drawable.ic_cat_comm
        "medical" -> R.drawable.ic_cat_medical
        "entertain" -> R.drawable.ic_cat_entertain
        "travel" -> R.drawable.ic_cat_travel
        "education" -> R.drawable.ic_cat_education
        "gift" -> R.drawable.ic_cat_gift
        "finance" -> R.drawable.ic_cat_finance
        "clothing" -> R.drawable.ic_cat_clothing
        "baby" -> R.drawable.ic_cat_baby
        "pet" -> R.drawable.ic_cat_pet
        "digital" -> R.drawable.ic_cat_digital
        "sport" -> R.drawable.ic_cat_sport
        "daily" -> R.drawable.ic_cat_daily
        "salary" -> R.drawable.ic_cat_salary
        "bonus" -> R.drawable.ic_cat_bonus
        "parttime" -> R.drawable.ic_cat_parttime
        "invest" -> R.drawable.ic_cat_invest
        "redpacket" -> R.drawable.ic_cat_redpacket
        "refund" -> R.drawable.ic_cat_refund
        "reimburse" -> R.drawable.ic_cat_reimburse
        "income" -> R.drawable.ic_cat_income
        // 餐饮
        "takeout" -> R.drawable.ic_cat_takeout
        "breakfast" -> R.drawable.ic_cat_breakfast
        "lunch" -> R.drawable.ic_cat_lunch
        "dinner" -> R.drawable.ic_cat_dinner
        "meal" -> R.drawable.ic_cat_meal
        "coffee" -> R.drawable.ic_cat_coffee
        "snack" -> R.drawable.ic_cat_snack
        "fruit" -> R.drawable.ic_cat_fruit
        "gathering" -> R.drawable.ic_cat_gathering
        "smoke" -> R.drawable.ic_cat_smoke
        // 购物
        "ecommerce" -> R.drawable.ic_cat_ecommerce
        "grocery" -> R.drawable.ic_cat_grocery
        "household" -> R.drawable.ic_cat_household
        "giftbox" -> R.drawable.ic_cat_giftbox
        "bag" -> R.drawable.ic_cat_bag
        // 交通
        "taxi" -> R.drawable.ic_cat_taxi
        "metro" -> R.drawable.ic_cat_metro
        "train" -> R.drawable.ic_cat_train
        "plane" -> R.drawable.ic_cat_plane
        "bike" -> R.drawable.ic_cat_bike
        "bus" -> R.drawable.ic_cat_bus
        // 汽车
        "fuel" -> R.drawable.ic_cat_fuel
        "parking" -> R.drawable.ic_cat_parking
        "wrench" -> R.drawable.ic_cat_wrench
        "wash" -> R.drawable.ic_cat_wash
        "carshield" -> R.drawable.ic_cat_carshield
        "fine" -> R.drawable.ic_cat_fine
        "road" -> R.drawable.ic_cat_road
        "carloan" -> R.drawable.ic_cat_carloan
        // 居住
        "rent" -> R.drawable.ic_cat_rent
        "mortgage" -> R.drawable.ic_cat_mortgage
        "property" -> R.drawable.ic_cat_property
        "utility" -> R.drawable.ic_cat_utility
        "broadband" -> R.drawable.ic_cat_broadband
        "cleaning" -> R.drawable.ic_cat_cleaning
        "repair" -> R.drawable.ic_cat_repair
        "paint" -> R.drawable.ic_cat_paint
        // 通讯
        "phonebill" -> R.drawable.ic_cat_phonebill
        "data" -> R.drawable.ic_cat_data
        // 医疗健康
        "stethoscope" -> R.drawable.ic_cat_stethoscope
        "pill" -> R.drawable.ic_cat_pill
        "checkup" -> R.drawable.ic_cat_checkup
        "tooth" -> R.drawable.ic_cat_tooth
        "hospital" -> R.drawable.ic_cat_hospital
        "supplement" -> R.drawable.ic_cat_supplement
        // 娱乐休闲
        "film" -> R.drawable.ic_cat_film
        "game" -> R.drawable.ic_cat_game
        "subscription" -> R.drawable.ic_cat_subscription
        "stage" -> R.drawable.ic_cat_stage
        "mic" -> R.drawable.ic_cat_mic
        "bar" -> R.drawable.ic_cat_bar
        // 旅游
        "tour" -> R.drawable.ic_cat_tour
        "diymap" -> R.drawable.ic_cat_diymap
        "hotel" -> R.drawable.ic_cat_hotel
        "ticket" -> R.drawable.ic_cat_ticket
        "passport" -> R.drawable.ic_cat_passport
        // 教育学习
        "course" -> R.drawable.ic_cat_course
        "book" -> R.drawable.ic_cat_book
        "exam" -> R.drawable.ic_cat_exam
        "pen" -> R.drawable.ic_cat_pen
        "ruler" -> R.drawable.ic_cat_ruler
        // 人情往来
        "redgift" -> R.drawable.ic_cat_redgift
        "present" -> R.drawable.ic_cat_present
        "treat" -> R.drawable.ic_cat_treat
        "eldercare" -> R.drawable.ic_cat_eldercare
        "donate" -> R.drawable.ic_cat_donate
        // 金融保险
        "insurance" -> R.drawable.ic_cat_insurance
        "fee" -> R.drawable.ic_cat_fee
        "interest" -> R.drawable.ic_cat_interest
        "investing" -> R.drawable.ic_cat_investing
        "repay" -> R.drawable.ic_cat_repay
        // 服饰美妆
        "shirt" -> R.drawable.ic_cat_shirt
        "shoes" -> R.drawable.ic_cat_shoes
        "cosmetic" -> R.drawable.ic_cat_cosmetic
        "salon" -> R.drawable.ic_cat_salon
        "nail" -> R.drawable.ic_cat_nail
        // 母婴育儿
        "milkbottle" -> R.drawable.ic_cat_milkbottle
        "diaper" -> R.drawable.ic_cat_diaper
        "toy" -> R.drawable.ic_cat_toy
        "blocks" -> R.drawable.ic_cat_blocks
        "kidclothes" -> R.drawable.ic_cat_kidclothes
        // 宠物
        "petfood" -> R.drawable.ic_cat_petfood
        "petitem" -> R.drawable.ic_cat_petitem
        "petcare" -> R.drawable.ic_cat_petcare
        "petgroom" -> R.drawable.ic_cat_petgroom
        "petstay" -> R.drawable.ic_cat_petstay
        // 数码电子
        "phone" -> R.drawable.ic_cat_phone
        "laptop" -> R.drawable.ic_cat_laptop
        "cable" -> R.drawable.ic_cat_cable
        "software" -> R.drawable.ic_cat_software
        "cloud" -> R.drawable.ic_cat_cloud
        // 运动健身
        "gym" -> R.drawable.ic_cat_gym
        "equipment" -> R.drawable.ic_cat_equipment
        "runningshoe" -> R.drawable.ic_cat_runningshoe
        "court" -> R.drawable.ic_cat_court
        // 其他
        "unknown" -> R.drawable.ic_cat_unknown
        "misc" -> R.drawable.ic_cat_misc
        "servicebox" -> R.drawable.ic_cat_servicebox
        // 工资
        "payslip" -> R.drawable.ic_cat_payslip
        "gearcoin" -> R.drawable.ic_cat_gearcoin
        "yearend" -> R.drawable.ic_cat_yearend
        "subsidy" -> R.drawable.ic_cat_subsidy
        // 奖金
        "starcoin" -> R.drawable.ic_cat_starcoin
        "commission" -> R.drawable.ic_cat_commission
        "trophy" -> R.drawable.ic_cat_trophy
        // 兼职
        "clockcoin" -> R.drawable.ic_cat_clockcoin
        "quill" -> R.drawable.ic_cat_quill
        "boltcoin" -> R.drawable.ic_cat_boltcoin
        // 理财收益
        "fund" -> R.drawable.ic_cat_fund
        "stock" -> R.drawable.ic_cat_stock
        "deposit" -> R.drawable.ic_cat_deposit
        "dividend" -> R.drawable.ic_cat_dividend
        // 红包
        "envelope" -> R.drawable.ic_cat_envelope
        // 退款
        "shoprefund" -> R.drawable.ic_cat_shoprefund
        "servicerefund" -> R.drawable.ic_cat_servicerefund
        // 报销
        "travelclaim" -> R.drawable.ic_cat_travelclaim
        "medicalclaim" -> R.drawable.ic_cat_medicalclaim
        "claim" -> R.drawable.ic_cat_claim
        // 其他收入
        "windfall" -> R.drawable.ic_cat_windfall
        // 旧扁平名（历史数据）复用的图标
        "beauty" -> R.drawable.ic_cat_beauty
        else -> 0
    }
}

/** 原型 .cat-icon 的尺寸（34px 底 / 20px 图标）。 */
private val CATEGORY_ICON_SIZE: Dp = 34.dp
private const val CATEGORY_GLYPH_RATIO = 20f / 34f

/**
 * 分类图标块（替代原型 .cat-icon）：分类色 12% 淡底 + 分类色混 78% 前景色的线描图标。
 *
 * @param name 分类名（与 finance_categories.name 一致，如「餐饮」）
 * @param color 该分类的色槽颜色，取 t.categoryDot(sortOrder)
 */
@Composable
fun CategoryIcon(
    name: String,
    color: Color,
    modifier: Modifier = Modifier,
    size: Dp = CATEGORY_ICON_SIZE,
) {
    val t = LocalDesignTokens.current
    val glyph = size * CATEGORY_GLYPH_RATIO
    // 旧版口径：分类色 12% 淡底 + 分类色线描（色混 22% 前景），与 s05/s14a 的彩色分类格一致
    val ink = lerp(color, t.fg, 0.22f)

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(t.radiusMd))
            .background(color.copy(alpha = 0.12f)),
        contentAlignment = Alignment.Center,
    ) {
        val res = CategoryIcons.keyOf(name)?.let { CategoryIcons.drawableOf(it) } ?: 0
        if (res != 0) {
            Icon(
                painter = painterResource(res),
                contentDescription = name,
                tint = ink,
                modifier = Modifier.size(glyph),
            )
        } else {
            // 未登记分类（历史脏数据）的兜底：分类色实心圆点。
            Box(
                Modifier
                    .size(glyph * 0.6f)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
