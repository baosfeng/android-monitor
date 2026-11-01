package com.bsfeng.monitor.ui

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 更新记录拆行（纯函数）：对话框把整段 changelog 拆成逐行 Text 再放进滚动容器。
 *
 * 真实数据是发布流程生成的提交列表（最多 40 行），因此空行过滤、CRLF 行尾、超长行都要覆盖。
 */
class UpdateChangelogTest {

    @Test
    fun `多行记录拆成逐行文本`() {
        val changelog = "fix: 修正更新检测\nfeat: 新增每日行程\nchore: 升级依赖"
        assertEquals(
            listOf("fix: 修正更新检测", "feat: 新增每日行程", "chore: 升级依赖"),
            updateChangelogLines(changelog),
        )
    }

    @Test
    fun `空行与行尾空白被过滤`() {
        val changelog = "第一行   \n\n   \n第二行\n"
        assertEquals(listOf("第一行", "第二行"), updateChangelogLines(changelog))
    }

    @Test
    fun `CRLF 行尾不残留回车`() {
        assertEquals(listOf("a", "b"), updateChangelogLines("a\r\nb\r\n"))
    }

    @Test
    fun `超长行原样保留交给 Text 换行`() {
        val long = "fix: " + "很长的提交信息".repeat(20)
        assertEquals(listOf(long), updateChangelogLines(long))
    }

    @Test
    fun `空串与全空白回落到说明文案`() {
        assertEquals(listOf(UPDATE_CHANGELOG_FALLBACK), updateChangelogLines(""))
        assertEquals(listOf(UPDATE_CHANGELOG_FALLBACK), updateChangelogLines("   \n\n"))
        assertEquals("包含功能改进与问题修复。", UPDATE_CHANGELOG_FALLBACK)
    }

    @Test
    fun `首行标题不做特殊处理`() {
        val changelog = "v2.3.0 更新内容\n- 新增时间轴倒序"
        assertEquals(listOf("v2.3.0 更新内容", "- 新增时间轴倒序"), updateChangelogLines(changelog))
    }
}
