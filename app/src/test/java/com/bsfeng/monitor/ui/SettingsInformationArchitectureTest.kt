package com.bsfeng.monitor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 设置页信息架构（一级页 6 组 + 二级页 ≤ 7 项）。
 *
 * 这几条不是「好看」的问题：一级页多出一行会让最高频的两个动作下沉一层；
 * 功能细则漏进一级页会让 6 组变成 13 行；每页项数超标时用户要滚 4 屏以上才能找到目标项。
 * 用纯函数 + 枚举钉住，改架构时这些用例先红。
 */
class SettingsInformationArchitectureTest {

    @Test
    fun `一级页 debug 关闭时是 5 组调试开启时是 6 组`() {
        val withoutDebug = settingsIndexEntries(debugUi = false)
        val withDebug = settingsIndexEntries(debugUi = true)
        assertEquals(5, withoutDebug.size)
        assertEquals(6, withDebug.size)
        assertEquals(SettingsSubPage.DEBUG, withDebug.last())
    }

    @Test
    fun `一级页入口顺序固定`() {
        assertEquals(
            listOf(
                "监控与采集", "功能开关", "数据与备份", "外观与动效", "关于",
            ),
            settingsIndexEntries(debugUi = false).map { it.title },
        )
    }

    @Test
    fun `功能细则页不进一级页入口`() {
        val indexPages = settingsIndexEntries(debugUi = true).toSet()
        settingsFeatureDetailPages().forEach { detail ->
            assertFalse(detail.name + " 不该出现在一级页", detail in indexPages)
        }
    }

    @Test
    fun `功能开关页七项功能各有一页细则`() {
        // 七项功能 = 七行开关 = 七个细则页，一一对应，不允许两项共用一个细则页
        val details = settingsFeatureDetailPages()
        assertEquals(7, details.size)
        assertEquals(1, details.count { it == SettingsSubPage.TOTP_DETAIL })
        assertEquals(details.size, details.toSet().size)
        assertEquals(1, details.count { it == SettingsSubPage.SKIP_DETAIL })
    }

    @Test
    fun `旧枚举名保留以便升级后恢复状态`() {
        // 名字会进 rememberSaveable；改名等于升级后回不到原页。旧的四个名字必须都还在。
        val names = SettingsSubPage.entries.map { it.name }.toSet()
        listOf("FEATURE", "GENERAL", "DATA", "ABOUT").forEach {
            assertTrue("旧枚举名 $it 必须保留", it in names)
        }
    }

    @Test
    fun `每个二级页都可达不出现孤儿页`() {
        // 孤儿页 = 枚举里有、界面上没有入口：用户永远点不到，等于白写一屏
        val indexPages = settingsIndexEntries(debugUi = true).toSet()
        val detailPages = (settingsFeatureDetailPages() + listOf(
            SettingsSubPage.WIDGET_DETAIL,
            SettingsSubPage.TRANSPORT_RECEIVING_DETAIL,
            SettingsSubPage.TRANSPORT_ALERTS_DETAIL,
        )).toSet()
        SettingsSubPage.entries.forEach { page ->
            assertTrue(
                page.name + " 既不在一级页入口、也不在任何下钻入口",
                page in indexPages || page in detailPages,
            )
        }
    }

    @Test
    fun `返回键从细则页先回父页而不是弹回一级页`() {
        // 下钻两层时，返回应该逐层退回；直接关覆盖层会让用户从「广告跳过细则」一路掉回一级页
        assertEquals(SettingsSubPage.FEATURE, settingsParentOf(SettingsSubPage.SKIP_DETAIL))
        assertEquals(SettingsSubPage.FEATURE, settingsParentOf(SettingsSubPage.WIDGET_DETAIL))
        assertEquals(
            SettingsSubPage.TRANSPORT_DETAIL,
            settingsParentOf(SettingsSubPage.TRANSPORT_RECEIVING_DETAIL),
        )
        assertEquals(
            SettingsSubPage.TRANSPORT_DETAIL,
            settingsParentOf(SettingsSubPage.TRANSPORT_ALERTS_DETAIL),
        )
    }

    @Test
    fun `一级页入口没有父页返回即关闭覆盖层`() {
        settingsIndexEntries(debugUi = true).forEach { page ->
            assertEquals(page.name + " 不该有父页", null, settingsParentOf(page))
        }
    }

    @Test
    fun `父页面链一定在有限步内终止`() {
        // 防环：父页指回自己 / 互指会让返回键永远退不出去
        SettingsSubPage.entries.forEach { page ->
            var cursor: SettingsSubPage? = page
            var steps = 0
            while (cursor != null) {
                steps++
                assertTrue(page.name + " 的父页面链成环", steps <= SettingsSubPage.entries.size)
                cursor = settingsParentOf(cursor)
            }
        }
    }

    @Test
    fun `二级页每页都有标题与一句话说明`() {
        SettingsSubPage.entries.forEach { page ->
            assertTrue(page.title + " 缺标题", page.title.isNotBlank())
            assertTrue(page.title + " 缺副标题", page.meta.isNotBlank())
        }
    }

    @Test
    fun `下线功能不再出现在任何摘要里`() {
        // 页面轨迹已在 DatabaseHelper v6 迁移下线，设置页摘要不能再提它
        SettingsSubPage.entries.forEach { page ->
            assertFalse(page.meta, page.meta.contains("页面轨迹"))
        }
    }
}
