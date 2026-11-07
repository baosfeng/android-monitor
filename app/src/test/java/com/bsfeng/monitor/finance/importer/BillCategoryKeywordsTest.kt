package com.bsfeng.monitor.finance.importer

import com.bsfeng.monitor.finance.model.CategoryCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BillCategoryKeywords] 表本身的守护：目标名必须在分类表内、关键词不能被两个分类同时认领。
 *
 * 这两条是「表写错」的高频形态：写了已下线的分类名 → 账目落到表外；
 * 同一关键词被两个分类认领 → 顺序一改结果就变（用户会觉得「分类乱跳」）。
 */
class BillCategoryKeywordsTest {

    private val catalogNames = CategoryCatalog.allNames().toSet()

    @Test
    fun `所有目标分类都落在分类表内`() {
        val outside = BillCategoryKeywords.allTargets().filter { it !in catalogNames }
        assertTrue("以下分类名不在 CategoryCatalog 里：" + outside, outside.isEmpty())
    }

    @Test
    fun `兜底分类也在分类表内`() {
        assertTrue(BillCategoryKeywords.FALLBACK_EXPENSE in catalogNames)
        assertTrue(BillCategoryKeywords.FALLBACK_INCOME in catalogNames)
    }

    @Test
    fun `表里每个关键词都能命中它自己的分类`() {
        // 关键词被更靠前的分类抢走是最隐蔽的表错误：写着「京东家电」，
        // 但「京东」在前面的「电商」里先命中，结果整批导入都落电商。
        // 这里逐条回放：关键词自身必须能推出它所属的分类（方向未知时两边都试）。
        val misses = BillCategoryKeywords.allKeywords().filter { (keyword, target) ->
            BillCategoryKeywords.lookup(keyword, false) != target &&
                BillCategoryKeywords.lookup(keyword, true) != target
        }
        assertTrue("以下关键词命不中自己的分类（多半被更靠前的分类抢走）：" + misses, misses.isEmpty())
    }
    @Test
    fun `空串与空白不命中任何分类`() {
        assertNull(BillCategoryKeywords.lookup("", false))
        assertNull(BillCategoryKeywords.lookup("   ", true))
    }

    @Test
    fun `大小写不敏感且包含匹配`() {
        assertEquals("咖啡奶茶", BillCategoryKeywords.lookup("STARBUCKS 星巴克咖啡", false))
        assertEquals("游戏", BillCategoryKeywords.lookup("Steam 平台充值", false))
    }

    @Test
    fun `更具体的品类排在前面 不被泛词抢走`() {
        assertEquals("食材", BillCategoryKeywords.lookup("美团买菜订单", false))
        assertEquals("外卖", BillCategoryKeywords.lookup("美团外卖订单", false))
        assertEquals("家电", BillCategoryKeywords.lookup("京东家电自营", false))
        assertEquals("电商", BillCategoryKeywords.lookup("京东商城", false))
        assertEquals("充电桩安装", BillCategoryKeywords.lookup("上门安装充电桩服务", false))
        assertEquals("电车充电", BillCategoryKeywords.lookup("特来电充电桩", false))
        assertEquals("快递", BillCategoryKeywords.lookup("顺丰速运", false))
    }

    @Test
    fun `收入侧只查收入表`() {
        assertEquals("工资", BillCategoryKeywords.lookup("某某公司代发工资", true))
        assertEquals("公积金", BillCategoryKeywords.lookup("住房公积金提取", true))
        assertEquals("变卖", BillCategoryKeywords.lookup("闲鱼卖出闲置", true))
        // 同一文本在支出侧应落到支出分类，不能串表
        assertEquals("电商", BillCategoryKeywords.lookup("闲鱼", false))
    }

    @Test
    fun `覆盖任务清单里点名的品牌`() {
        val samples = mapOf(
            "饿了么外卖" to "外卖",
            "海底捞火锅" to "正餐",
            "蜜雪冰城" to "咖啡奶茶",
            "叮咚买菜" to "食材",
            "全家便利店" to "超市便利",
            "拼多多" to "电商",
            "抽纸一提" to "纸品清洁",
            "洗发水" to "洗护个护",
            "苏宁易购" to "家电",
            "中石化加油" to "加油",
            "小桔充电" to "电车充电",
            "停车缴费" to "停车",
            "滴滴出行" to "打车",
            "上海地铁" to "公交地铁",
            "中国铁路12306" to "火车高铁",
            "机票订单" to "飞机",
            "中国移动话费" to "话费",
            "国家电网电费" to "水电燃气",
            "物业管理费" to "物业",
            "菜鸟驿站" to "快递",
            "万达影城" to "电影",
            "爱奇艺会员" to "会员订阅",
            "王者荣耀点券" to "游戏",
            "市第一医院" to "门诊",
            "大药房" to "药品",
            "宝岛眼镜" to "配镜",
            "健身房年卡" to "健身房",
            "优衣库" to "衣服",
            "耐克旗舰店" to "鞋包",
            "屈臣氏" to "化妆品",
            "理发店" to "美容美发",
            "奶粉" to "奶粉",
            "猫粮" to "宠物食品",
            "新东方课程" to "课程培训",
            "当当图书" to "书籍",
            "平安保险" to "保险",
            "花呗还款" to "还款",
            "手续费" to "手续费",
        )
        val misses = samples.filter { (text, expected) ->
            BillCategoryKeywords.lookup(text, false) != expected
        }
        assertTrue("未按预期命中：" + misses, misses.isEmpty())
    }

    @Test
    fun `真实账单里的日常商户名不落空`() {
        // 这批是从支付宝 / 微信账单里最常见的一类「交易对方 + 商品说明」写法：
        // 店名 / 平台名 + 一点后缀，基本不含「餐厅」「超市」这类泛称。
        // 判据只要求「命中某个具体分类」，不锁死到哪一个 —— 店名归属会随运营变化，
        // 钉太死会让一次合理的归类调整变成红灯。真正不能接受的是落到兜底「其他」。
        val samples = listOf(
            "菜鸟驿站", "美团外卖订单", "饿了么订单", "滴滴出行", "高德打车", "哈啰单车",
            "上海地铁", "国家电网电费", "中国移动话费", "中石化加油", "特来电", "小桔充电",
            "星巴克", "瑞幸咖啡", "蜜雪冰城", "肯德基", "麦当劳", "海底捞",
            "盒马鲜生", "叮咚买菜", "永辉超市", "沃尔玛", "全家便利店",
            "淘宝", "天猫超市", "京东商城", "拼多多", "抖音电商",
            "顺丰速运", "中通快递", "丰巢智能柜",
            "万达影城", "携程旅行", "如家酒店",
            "爱奇艺会员", "网易云音乐黑胶VIP", "王者荣耀", "Steam游戏",
            "老百姓大药房", "宝岛眼镜", "口腔医院",
            "优衣库", "耐克", "屈臣氏", "理发店", "丝芙兰",
            "健身房年卡", "迪卡侬", "新东方", "当当网图书", "猫粮", "宠物医院",
            "物业费", "房租", "宽带费", "水费", "中国平安保险", "花呗还款",
            "绝味鸭脖", "正新鸡排", "兰州拉面", "沙县小吃", "黄焖鸡米饭",
            "水果店", "百果园", "钱大妈", "小米之家", "苏宁易购",
            "停车缴费", "高速通行费", "违章罚款", "洗车", "汽车保养",
            "家政服务", "装修公司", "话费充值", "流量包",
            "美团优选", "多多买菜", "山姆", "Costco",
        )
        val missed = samples.filter {
            val hit = BillCategoryKeywords.lookup(it, false)
            hit == null || hit == BillCategoryKeywords.FALLBACK_EXPENSE
        }
        assertTrue("以下日常商户名落到了兜底「其他」：" + missed, missed.isEmpty())
    }
}