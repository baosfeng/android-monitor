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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.R
import com.bsfeng.monitor.finance.model.CategoryCatalog
import com.bsfeng.monitor.ui.theme.LocalDesignTokens

/**
 * 分类图标名 ↔ 真机 finance_categories.name 的映射与 drawable 解析。
 *
 * 图标来自原型 s05 的 22 个线描 SVG（逐条转成 res/drawable/ic_cat_*.xml，
 * 24x24 viewBox、strokeWidth 1.7、fill=none），与原型逐条对应。
 * 收入类分类原型尚未绘制图标，[keyOf] 返回 null，由 [CategoryIcon] 回落彩色圆点。
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
     * 二级分类 → 图标 key。
     *
     * 默认继承父级图标（[CategoryCatalog] 遍历生成），再对高频细分做覆盖
     * （打车/加油/电影/药品/话费…），保证每个二级都有线描图标，不再回落彩色圆点。
     */
    private val CHILD_KEY: Map<String, String> = buildMap {
        CategoryCatalog.expense.forEach { seed ->
            seed.children.forEach { put(it, TOP_KEY.getValue(seed.name)) }
        }
        CategoryCatalog.income.forEach { seed ->
            seed.children.forEach { put(it, TOP_KEY.getValue(seed.name)) }
        }
        putAll(
            mapOf(
                "外卖" to "food",
                "早餐" to "breakfast",
                "午餐" to "food",
                "晚餐" to "food",
                "正餐" to "food",
                "咖啡奶茶" to "coffee",
                "零食" to "snack",
                "水果" to "fruit",
                "聚餐" to "food",
                "烟酒" to "smoke",
                "电商" to "shopping",
                "超市便利" to "shopping",
                "家居日用" to "home",
                "礼品" to "gift",
                "其他购物" to "shopping",
                "打车" to "taxi",
                "公交地铁" to "metro",
                "火车高铁" to "metro",
                "飞机" to "plane",
                "共享单车" to "bike",
                "长途客运" to "bus",
                "加油" to "car",
                "停车" to "parking",
                "保养维修" to "wrench",
                "洗车" to "wash",
                "车险" to "finance",
                "违章罚款" to "fine",
                "过路费" to "road",
                "车贷" to "finance",
                "房租" to "rent",
                "房贷" to "rent",
                "物业" to "home",
                "水电燃气" to "utility",
                "宽带" to "broadband",
                "家政" to "home",
                "维修" to "wrench",
                "装修" to "paint",
                "话费" to "comm",
                "流量" to "comm",
                "门诊" to "medical",
                "药品" to "pill",
                "体检" to "medical",
                "牙科" to "tooth",
                "住院" to "hospital",
                "保健品" to "medical",
                "电影" to "film",
                "游戏" to "game",
                "会员订阅" to "entertain",
                "演出" to "mic",
                "KTV" to "mic",
                "酒吧" to "bar",
                "跟团游" to "travel",
                "自由行" to "travel",
                "住宿" to "hotel",
                "景点门票" to "ticket",
                "签证" to "passport",
                "课程培训" to "education",
                "书籍" to "education",
                "考试报名" to "education",
                "文具" to "pen",
                "学习工具" to "education",
                "发红包" to "redpacket",
                "礼物" to "gift",
                "请客" to "food",
                "孝亲" to "gift",
                "捐赠" to "gift",
                "保险" to "finance",
                "手续费" to "finance",
                "利息" to "invest",
                "投资" to "invest",
                "还款" to "finance",
                "衣服" to "clothing",
                "鞋包" to "shoes",
                "化妆品" to "beauty",
                "美容美发" to "beauty",
                "美甲" to "nail",
                "奶粉" to "baby",
                "尿布" to "baby",
                "玩具" to "toy",
                "早教" to "education",
                "儿童衣物" to "clothing",
                "宠物食品" to "pet",
                "宠物用品" to "pet",
                "宠物医疗" to "medical",
                "宠物美容" to "beauty",
                "寄养" to "pet",
                "手机" to "phone",
                "电脑" to "laptop",
                "配件" to "digital",
                "软件" to "digital",
                "云服务" to "digital",
                "健身房" to "sport",
                "器材" to "sport",
                "运动装备" to "sport",
                "场地费" to "sport",
                "未分类" to "daily",
                "杂项" to "daily",
                "生活服务" to "daily",
                "基本工资" to "salary",
                "绩效" to "salary",
                "年终奖" to "bonus",
                "补贴" to "salary",
                "绩效奖金" to "bonus",
                "提成" to "bonus",
                "项目奖金" to "bonus",
                "兼职收入" to "parttime",
                "稿费" to "parttime",
                "外快" to "parttime",
                "基金" to "invest",
                "股票" to "invest",
                "存款利息" to "invest",
                "分红" to "invest",
                "礼金" to "redpacket",
                "购物退款" to "refund",
                "服务退款" to "refund",
                "差旅报销" to "reimburse",
                "医疗报销" to "reimburse",
                "其他报销" to "reimburse",
                "意外收入" to "income",
            )
        )
    }

    /** 分类名 → 图标 key；未绘制图标的分类返回 null。 */
    fun keyOf(categoryName: String): String? = CHILD_KEY[categoryName] ?: TOP_KEY[categoryName]

    /** 图标 key → drawable 资源；key 非法返回 0（调用方回落）。 */
    fun drawableOf(key: String): Int = when (key) {
        "food" -> R.drawable.ic_cat_food
        "car" -> R.drawable.ic_cat_car
        "transport" -> R.drawable.ic_cat_transport
        "digital" -> R.drawable.ic_cat_digital
        "shopping" -> R.drawable.ic_cat_shopping
        "home" -> R.drawable.ic_cat_home
        "daily" -> R.drawable.ic_cat_daily
        "education" -> R.drawable.ic_cat_education
        "snack" -> R.drawable.ic_cat_snack
        "broadband" -> R.drawable.ic_cat_broadband
        "fruit" -> R.drawable.ic_cat_fruit
        "metro" -> R.drawable.ic_cat_metro
        "utility" -> R.drawable.ic_cat_utility
        "medical" -> R.drawable.ic_cat_medical
        "comm" -> R.drawable.ic_cat_comm
        "clothing" -> R.drawable.ic_cat_clothing
        "beauty" -> R.drawable.ic_cat_beauty
        "rent" -> R.drawable.ic_cat_rent
        "entertain" -> R.drawable.ic_cat_entertain
        "taxi" -> R.drawable.ic_cat_taxi
        "hotel" -> R.drawable.ic_cat_hotel
        "travel" -> R.drawable.ic_cat_travel
        "gift" -> R.drawable.ic_cat_gift
        "finance" -> R.drawable.ic_cat_finance
        "baby" -> R.drawable.ic_cat_baby
        "pet" -> R.drawable.ic_cat_pet
        "sport" -> R.drawable.ic_cat_sport
        "salary" -> R.drawable.ic_cat_salary
        "bonus" -> R.drawable.ic_cat_bonus
        "parttime" -> R.drawable.ic_cat_parttime
        "invest" -> R.drawable.ic_cat_invest
        "redpacket" -> R.drawable.ic_cat_redpacket
        "refund" -> R.drawable.ic_cat_refund
        "reimburse" -> R.drawable.ic_cat_reimburse
        "income" -> R.drawable.ic_cat_income
        "breakfast" -> R.drawable.ic_cat_breakfast
        "coffee" -> R.drawable.ic_cat_coffee
        "smoke" -> R.drawable.ic_cat_smoke
        "plane" -> R.drawable.ic_cat_plane
        "bike" -> R.drawable.ic_cat_bike
        "bus" -> R.drawable.ic_cat_bus
        "parking" -> R.drawable.ic_cat_parking
        "wrench" -> R.drawable.ic_cat_wrench
        "wash" -> R.drawable.ic_cat_wash
        "fine" -> R.drawable.ic_cat_fine
        "road" -> R.drawable.ic_cat_road
        "paint" -> R.drawable.ic_cat_paint
        "pill" -> R.drawable.ic_cat_pill
        "tooth" -> R.drawable.ic_cat_tooth
        "hospital" -> R.drawable.ic_cat_hospital
        "film" -> R.drawable.ic_cat_film
        "game" -> R.drawable.ic_cat_game
        "mic" -> R.drawable.ic_cat_mic
        "bar" -> R.drawable.ic_cat_bar
        "ticket" -> R.drawable.ic_cat_ticket
        "passport" -> R.drawable.ic_cat_passport
        "pen" -> R.drawable.ic_cat_pen
        "shoes" -> R.drawable.ic_cat_shoes
        "nail" -> R.drawable.ic_cat_nail
        "toy" -> R.drawable.ic_cat_toy
        "phone" -> R.drawable.ic_cat_phone
        "laptop" -> R.drawable.ic_cat_laptop
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
    // 原型定稿 .cat-icon：无底色，图标直接走 currentColor（调用方传文字色 / 选中 accent 色）
    val ink = color

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(t.radiusMd)),
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
            // 原型尚未绘制该分类的图标（如收入类）时的兜底：分类色实心圆点。
            Box(
                Modifier
                    .size(glyph * 0.6f)
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}
