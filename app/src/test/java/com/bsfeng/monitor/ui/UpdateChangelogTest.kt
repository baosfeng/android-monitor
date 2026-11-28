package com.bsfeng.monitor.ui

import com.bsfeng.monitor.update.UpdateRelease
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
    fun `单版本副行带版本_发布日与项数`() {
        val manifest = updateFixture(publishedAt = "2026-11-28T02:00:00Z")
        val sections = updateReleaseSections(manifest, currentVersionCode = 3)
        val line = updateReleaseLine("1.3.0", manifest, sections)
        assertTrue(line, line.contains("当前 1.3.0 → 1.4.0"))
        assertTrue(line, line.contains("11-28 发布"))
        assertTrue(line, line.contains("1 项更新"))
    }

    @Test
    fun `发布日解析不出来时整段省略`() {
        val manifest = updateFixture(publishedAt = "")
        val line = updateReleaseLine("1.3.0", manifest, updateReleaseSections(manifest, currentVersionCode = 4))
        assertEquals("当前 1.3.0 → 1.4.0", line)
    }

    @Test
    fun `跨版本只保留比当前新的版本且从新到旧`() {
        val manifest = updateFixture(
            publishedAt = "2026-11-28T02:00:00Z",
            releases = listOf(
                UpdateRelease(3, "1.3.0", "- feat: 三", "2026-11-10T02:00:00Z"),
                UpdateRelease(4, "1.4.0", "- fix: 四", "2026-11-20T02:00:00Z"),
                UpdateRelease(2, "1.2.0", "- feat: 二", "2026-11-01T02:00:00Z"),
            ),
        )
        val sections = updateReleaseSections(manifest, currentVersionCode = 2)
        // 顶层与 releases 里同 versionCode 时以顶层为准（发布流程两处写同一版时不重复展示）
        assertEquals(listOf("1.4.0", "1.3.0"), sections.map { it.versionName })
        assertEquals(listOf("示例", "三"), sections.flatMap { s -> s.changes.map { it.text } })
    }

    @Test
    fun `清单满 5 版且最旧仍比当前新时标记未覆盖`() {
        val manifest = updateFixture(
            publishedAt = "2026-11-28T02:00:00Z",
            versionCode = 5,
            versionName = "1.5.0",
            releases = (4 downTo 1).map { code ->
                UpdateRelease(code, "1.$code.0", "- feat: 第 " + code + " 版", "2026-11-0" + code + "T02:00:00Z")
            },
        )
        assertTrue("清单没覆盖到当前版本时要提示", updateHistoryTruncated(manifest, currentVersionCode = 0))
        assertTrue("当前版本落在清单内即覆盖完整", !updateHistoryTruncated(manifest, currentVersionCode = 3))
    }

    @Test
    fun `旧清单没有 releases 时不算未覆盖`() {
        val manifest = updateFixture(publishedAt = "2026-11-28T02:00:00Z")
        assertTrue(!updateHistoryTruncated(manifest, currentVersionCode = 0))
    }

    @Test
    fun `旧清单没有 releases 时只有一段`() {
        val manifest = updateFixture(publishedAt = "2026-11-28T02:00:00Z")
        val sections = updateReleaseSections(manifest, currentVersionCode = 0)
        assertEquals(1, sections.size)
        assertEquals("1.4.0", sections.single().versionName)
    }

    @Test
    fun `跨版本副行给版本数与总项数`() {
        val manifest = updateFixture(
            publishedAt = "2026-11-28T02:00:00Z",
            releases = listOf(UpdateRelease(3, "1.3.0", "- feat: 三\n- fix: 修", "2026-11-10T02:00:00Z")),
        )
        val sections = updateReleaseSections(manifest, currentVersionCode = 2)
        val line = updateReleaseLine("1.2.0", manifest, sections)
        assertTrue(line, line.contains("含 2 个版本"))
        assertTrue(line, line.contains("3 项更新"))
        assertTrue(line, !line.contains("发布"))
    }

    private fun updateFixture(
        publishedAt: String,
        releases: List<com.bsfeng.monitor.update.UpdateRelease> = emptyList(),
        versionCode: Int = 4,
        versionName: String = "1.4.0",
    ) = com.bsfeng.monitor.update.UpdateManifest(
        versionCode = versionCode,
        versionName = versionName,
        apkUrl = "https://example.invalid/app.apk",
        apkSha256 = "0".repeat(64),
        changelog = "- feat: 示例",
        publishedAt = publishedAt,
        releases = releases,
    )

    @Test
    fun `单组时总数与组内条数一致`() {
        val groups = groupUpdateChanges(parseUpdateChangelog("- feat: 只有一条"))
        assertEquals(1, groups.size)
        assertEquals(1, groups.single().size)
        assertTrue(groups.single().kind == UpdateChangeKind.FEATURE)
    }
}