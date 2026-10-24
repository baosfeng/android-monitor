package com.bsfeng.monitor.ui.components

import com.bsfeng.monitor.R

// 从 ui/components/CategoryIcon.kt 拆出：图标 key → drawable 资源 id 的全量对照表。
// 负责「用哪个图标」到「画哪张图」，未知 key 返回 0 由调用方回落。

/** 图标 key → drawable 资源；key 非法返回 0（调用方回落）。 */
internal fun categoryIconDrawableOf(key: String): Int = when (key) {
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