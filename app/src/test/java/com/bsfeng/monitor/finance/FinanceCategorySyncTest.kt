package com.bsfeng.monitor.finance

import android.content.Context
import com.bsfeng.monitor.finance.model.Category
import com.bsfeng.monitor.finance.model.CategoryCatalog
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 分类目录补齐（[FinanceCategorySeeder.syncCatalog] / [FinanceManager.syncCatalogOnce]）。
 *
 * 真机场景：已安装用户的 finance.db 里已是旧目录，[FinanceCategorySeeder.seedDefaultCategories]
 * 只认「表为空」不会补新分类 —— 这里用真实 SQLite 验证补齐路径与幂等性。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FinanceCategorySyncTest {

    private lateinit var context: Context
    private lateinit var manager: FinanceManager

    /** 第二批之前的一级 + 二级（旧目录里的存量项，顺序与当时一致） */
    private val legacyCatalog = listOf(
        "餐饮" to listOf("外卖", "早餐", "午餐", "晚餐", "正餐", "咖啡奶茶", "零食", "水果", "聚餐", "烟酒"),
        "购物" to listOf("电商", "超市便利", "家居日用", "礼品", "其他购物"),
        "交通" to listOf("打车", "公交地铁", "火车高铁", "飞机", "共享单车", "长途客运"),
        "汽车" to listOf("加油", "停车", "保养维修", "洗车", "车险", "违章罚款", "过路费", "车贷"),
        "居住" to listOf("房租", "房贷", "物业", "水电燃气", "宽带", "家政", "维修", "装修"),
        "通讯" to listOf("话费", "流量"),
        "医疗健康" to listOf("门诊", "药品", "体检", "牙科", "住院", "保健品"),
        "娱乐休闲" to listOf("电影", "游戏", "会员订阅", "演出", "KTV", "酒吧"),
        "旅游" to listOf("跟团游", "自由行", "住宿", "景点门票", "签证"),
        "教育学习" to listOf("课程培训", "书籍", "考试报名", "文具", "学习工具"),
        "人情往来" to listOf("发红包", "礼物", "请客", "孝亲", "捐赠"),
        "金融保险" to listOf("保险", "手续费", "利息", "投资", "还款"),
        "服饰美妆" to listOf("衣服", "鞋包", "化妆品", "美容美发", "美甲"),
        "母婴育儿" to listOf("奶粉", "尿布", "玩具", "早教", "儿童衣物"),
        "宠物" to listOf("宠物食品", "宠物用品", "宠物医疗", "宠物美容", "寄养"),
        "数码电子" to listOf("手机", "电脑", "配件", "软件", "云服务"),
        "运动健身" to listOf("健身房", "器材", "运动装备", "场地费"),
        "其他" to listOf("未分类", "杂项", "生活服务"),
    )

    private val legacyIncome = listOf(
        "工资" to listOf("基本工资", "绩效", "年终奖", "补贴"),
        "奖金" to listOf("绩效奖金", "提成", "项目奖金"),
        "兼职" to listOf("兼职收入", "稿费", "外快"),
        "理财收益" to listOf("基金", "股票", "存款利息", "分红"),
        "红包" to listOf("礼金"),
        "退款" to listOf("购物退款", "服务退款"),
        "报销" to listOf("差旅报销", "医疗报销", "其他报销"),
        "其他收入" to listOf("意外收入"),
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        FinanceDatabaseHelper.resetForTesting()
        context.deleteDatabase(FinanceDatabaseHelper.DATABASE_NAME)
        context.getSharedPreferences("finance_prefs", Context.MODE_PRIVATE).edit().clear().commit()
        manager = FinanceManager(context)
    }

    @After
    fun tearDown() {
        FinanceDatabaseHelper.resetForTesting()
        context.deleteDatabase(FinanceDatabaseHelper.DATABASE_NAME)
        context.getSharedPreferences("finance_prefs", Context.MODE_PRIVATE).edit().clear().commit()
    }

    /** 模拟存量安装：只写入旧目录（一级父 id 先插、二级挂上去） */
    private fun seedLegacyCatalog() {
        for ((type, seeds) in listOf(0 to legacyCatalog, 1 to legacyIncome)) {
            for ((index, pair) in seeds.withIndex()) {
                val topId = manager.dao.insertCategory(
                    Category(name = pair.first, type = type, parentId = -1, sortOrder = index)
                )
                for ((childIndex, child) in pair.second.withIndex()) {
                    manager.dao.insertCategory(
                        Category(name = child, type = type, parentId = topId, sortOrder = childIndex)
                    )
                }
            }
        }
    }

    private fun childCount(categories: List<Category>) = categories.count { it.parentId >= 0 }

    @Test
    fun `旧目录能补齐到当前目录且不重复保留旧分类`() = runBlocking {
        seedLegacyCatalog()
        val legacyNames = manager.getAllCategories().map { it.name }.toSet()
        assertEquals("存量分类数应为旧目录", 145, legacyNames.size)

        val added = manager.syncCatalog()

        val all = manager.getAllCategories()
        assertEquals("补齐后分类总数应等于当前目录", CategoryCatalog.allNames().size, all.size)
        assertEquals("新增条数 = 当前目录 - 旧目录", 64, added)
        assertTrue("旧分类必须保留（历史交易可能引用）", all.map { it.name }.containsAll(legacyNames))
        assertEquals("名称不能重复", all.size, all.map { it.name }.toSet().size)
        assertEquals("二级总数", 183, childCount(all))
        // 新增项落在正确的一级下、顺序接在旧二级之后
        val carId = all.first { it.name == "汽车" }.id
        val carChildren = all.filter { it.parentId == carId }.sortedBy { it.sortOrder }.map { it.name }
        assertEquals(
            listOf(
                "加油", "停车", "保养维修", "洗车", "车险", "违章罚款", "过路费", "车贷",
                "电车充电", "充电桩安装", "年检", "车载用品", "救援拖车",
            ),
            carChildren,
        )
    }

    @Test
    fun `补齐可重复调用 第二次不再新增`() = runBlocking {
        seedLegacyCatalog()
        assertEquals(64, manager.syncCatalog())
        assertEquals("再跑一次必须返回 0", 0, manager.syncCatalog())
        assertEquals(CategoryCatalog.allNames().size, manager.getAllCategories().size)
    }

    @Test
    fun `空表时补齐等价于全量播种`() = runBlocking {
        assertEquals("一级 + 二级全量插入", CategoryCatalog.allNames().size, manager.syncCatalog())
        assertEquals(0, manager.syncCatalog())
        assertEquals(CategoryCatalog.allNames().size, manager.getAllCategories().size)
    }

    @Test
    fun `ensureInitialized 会补齐存量目录 且同版本只补一次`() = runBlocking {
        seedLegacyCatalog()
        // 真机上「旧扁平 → 一级/二级」迁移早已跑过，标记为 true；这里同样置位，
        // 否则迁移会清表重插全量，就测不出补齐路径了
        context.getSharedPreferences("finance_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("category_tree_v1", true).commit()

        manager.ensureInitialized()
        val after = manager.getAllCategories()
        assertEquals("存量安装升级后目录应完整", CategoryCatalog.allNames().size, after.size)
        assertTrue("新分类必须可见", after.any { it.name == "电车充电" })

        // 同版本再初始化一次：标记已置位，不再补写
        manager.ensureInitialized()
        assertEquals(CategoryCatalog.allNames().size, manager.getAllCategories().size)
    }
}