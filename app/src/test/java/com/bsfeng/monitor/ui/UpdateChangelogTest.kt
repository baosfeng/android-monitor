package com.bsfeng.monitor.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 更新记录的解析与分组（纯函数）。
 *
 * 真实数据是发布流程生成的提交列表（最多 20 条、`- type(scope): 标题`），
 * 因此前缀剥离、未知类型兜底、空行 / CRLF、分组顺序都要覆盖。
 */
class UpdateChangelogTest {

    @Test
    fun `类型前缀与 scope 被剥掉`() {
        val changes = parseUpdateChangelog(
            "- feat: 新增每日行程\n- fix(trip): 轨迹线按精度分档\n- docs(debug-cli): seed 用法补 noise 场景",
        )
        assertEquals(
            listOf(
                UpdateChange(UpdateChangeKind.FEATURE, "新增每日行程"),
                UpdateChange(UpdateChangeKind.FIX, "轨迹线按精度分档"),
                UpdateChange(UpdateChangeKind.DOCS, "seed 用法补 noise 场景"),
            ),
            changes,
        )
    }

    @Test
    fun `星号项目符号与中文冒号同样识别`() {
        val changes = parseUpdateChangelog("* perf：滚动更顺")
        assertEquals(listOf(UpdateChange(UpdateChangeKind.PERF, "滚动更顺")), changes)
    }

    @Test
    fun `未知类型归到其他且保留全文`() {
        val changes = parseUpdateChangelog("- chore(open-design): 截图不入库\nv2.3.0 更新内容")
        assertEquals(
            listOf(
                UpdateChange(UpdateChangeKind.OTHER, "截图不入库"),
                UpdateChange(UpdateChangeKind.OTHER, "v2.3.0 更新内容"),
            ),
            changes,
        )
    }

    @Test
    fun `空行与 CRLF 不影响解析`() {
        val changes = parseUpdateChangelog("fix: a\r\n\r\n   \nfix: b\r\n")
        assertEquals(listOf("a", "b"), changes.map { it.text })
    }

    @Test
    fun `超长行原样保留交给 Text 换行`() {
        val long = "很长的提交信息".repeat(20)
        assertEquals(listOf(long), parseUpdateChangelog("- fix: " + long).map { it.text })
    }

    @Test
    fun `空串与全空白回落到说明文案`() {
        assertEquals(
            listOf(UpdateChange(UpdateChangeKind.OTHER, UPDATE_CHANGELOG_FALLBACK)),
            parseUpdateChangelog(""),
        )
        assertEquals(
            listOf(UpdateChange(UpdateChangeKind.OTHER, UPDATE_CHANGELOG_FALLBACK)),
            parseUpdateChangelog("   \n\n"),
        )
        assertEquals("包含功能改进与问题修复。", UPDATE_CHANGELOG_FALLBACK)
    }

    @Test
    fun `分组按固定顺序且空组不出现`() {
        val groups = groupUpdateChanges(
            parseUpdateChangelog(
                "- docs: 文档一\n- fix: 修一\n- fix: 修二\n- feat: 加一\n- test: 测一",
            ),
        )
        assertEquals(
            listOf(
                UpdateChangeKind.FEATURE,
                UpdateChangeKind.FIX,
                UpdateChangeKind.DOCS,
                UpdateChangeKind.TEST,
            ),
            groups.map { it.kind },
        )
        assertEquals(2, groups.first { it.kind == UpdateChangeKind.FIX }.size)
        assertEquals(5, groups.sumOf { it.size })
    }

    @Test
    fun `发布副行带版本_发布日与项数`() {
        val manifest = updateFixture(publishedAt = "2026-11-28T02:00:00Z")
        val line = updateReleaseLine("1.3.0", manifest, 12)
        assertTrue(line, line.contains("当前 1.3.0 → 1.4.0"))
        assertTrue(line, line.contains("11-28 发布"))
        assertTrue(line, line.contains("12 项更新"))
    }

    @Test
    fun `发布日解析不出来时整段省略`() {
        val line = updateReleaseLine("1.3.0", updateFixture(publishedAt = ""), 0)
        assertEquals("当前 1.3.0 → 1.4.0", line)
    }

    private fun updateFixture(publishedAt: String) = com.bsfeng.monitor.update.UpdateManifest(
        versionCode = 5,
        versionName = "1.4.0",
        apkUrl = "https://example.invalid/app.apk",
        apkSha256 = "0".repeat(64),
        changelog = "- feat: 示例",
        publishedAt = publishedAt,
    )

    @Test
    fun `单组时总数与组内条数一致`() {
        val groups = groupUpdateChanges(parseUpdateChangelog("- feat: 只有一条"))
        assertEquals(1, groups.size)
        assertEquals(1, groups.single().size)
        assertTrue(groups.single().kind == UpdateChangeKind.FEATURE)
    }
}
