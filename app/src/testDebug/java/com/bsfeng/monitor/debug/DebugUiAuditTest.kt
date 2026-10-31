package com.bsfeng.monitor.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 无障碍审计的判定（纯 JVM）：两类缺口、dp 换算、统计项、摘要条数。
 *
 * 这些判定在真机上跑一次要装包 + 点进屏幕，几分钟才拿到一行；这里毫秒级把边界钉住
 * （尤其 43.9dp 与 44.0dp 之间那条线，判定线与设计 token tap-min 同源）。
 */
class DebugUiAuditTest {

    /** 默认密度 2.0：120px = 60dp（放行），88px = 44dp（刚好放行），87px = 43.5dp（缺口）。 */
    private companion object {
        const val DENSITY = 2.0f
    }

    private fun node(
        index: Int = 0,
        parent: Int = -1,
        className: String = "android.widget.ImageButton",
        text: String = "",
        desc: String = "",
        viewId: String = "",
        bounds: List<Int> = listOf(0, 0, 120, 120),
        clickable: Boolean = true,
        focusable: Boolean = false,
        scrollable: Boolean = false,
        enabled: Boolean = true,
    ) = UiNode(
        index = index,
        parent = parent,
        depth = 1,
        window = 0,
        className = className,
        viewId = viewId,
        text = text,
        desc = desc,
        left = bounds[0],
        top = bounds[1],
        right = bounds[2],
        bottom = bounds[3],
        clickable = clickable,
        scrollable = scrollable,
        editable = false,
        enabled = enabled,
        selected = false,
        childCount = 0,
        focusable = focusable,
    )

    private fun audit(vararg nodes: UiNode): UiAuditReport =
        UiAudit.run(nodes.toList(), DENSITY, pkg = "com.bsfeng.monitor")

    // ────────────────────────── 无可读名 ──────────────────────────

    @Test
    fun `无可读名 - 可点节点既无 text 也无 desc 才是缺口`() {
        val gap = audit(node(text = "", desc = "")).gaps.single()
        assertEquals(UiAudit.REASON_NO_LABEL, gap.reason)
        assertEquals(0, gap.index)

        // 任一有内容就算「能读出来」：图标按钮常只给 contentDescription
        assertTrue(audit(node(text = "设置")).gaps.isEmpty())
        assertTrue(audit(node(desc = "打开设置")).gaps.isEmpty())
    }

    @Test
    fun `无可读名 - 纯空白文案按「没有」算`() {
        assertEquals(1, audit(node(text = "   ")).gapCount)
    }

    @Test
    fun `无可读名 - 文案挂在子节点上就不算缺口（真机实测的 Compose 合并语义）`() {
        // 实测形态：clickable 的 android.view.View 自身 text/desc 全空，文案在子 TextView 上
        val report = audit(
            node(index = 0, className = "android.view.View", bounds = listOf(48, 602, 582, 941)),
            node(index = 1, parent = 0, className = "android.widget.TextView", text = "事件",
                clickable = false, bounds = listOf(84, 638, 158, 686)),
        )
        assertTrue(report.gaps.isEmpty())
        assertEquals(1, report.labelFromChildren)
    }

    @Test
    fun `无可读名 - 孙子节点上的文案同样算（深层 Compose 布局）`() {
        val report = audit(
            node(index = 0, className = "android.view.View", text = ""),
            node(index = 1, parent = 0, className = "android.view.View", text = "", clickable = false),
            node(index = 2, parent = 1, className = "android.widget.TextView", text = "设置",
                clickable = false, bounds = listOf(0, 0, 300, 60)),
        )
        assertTrue(report.gaps.isEmpty())
    }

    @Test
    fun `无可读名 - 子树里嵌套的可交互节点不替父节点作证`() {
        // 父容器自点名，子按钮有自己的名字：两者是独立读屏目标，父节点仍是「无名按钮」
        val report = audit(
            node(index = 0, className = "android.view.View", text = ""),
            node(index = 1, parent = 0, desc = "更多", clickable = true),
        )
        assertEquals(1, report.gapCount)
        assertEquals(0, report.gaps.single().index)
    }

    @Test
    fun `无可读名 - 子树里全是无名装饰节点仍要报`() {
        val report = audit(
            node(index = 0, className = "android.view.View", text = ""),
            node(index = 1, parent = 0, className = "android.view.View", text = "", clickable = false,
                bounds = listOf(0, 0, 100, 100)),
        )
        assertEquals(1, report.gapCount)
        assertEquals(UiAudit.REASON_NO_LABEL, report.gaps.single().reason)
    }

    @Test
    fun `无可读名 - 只能聚焦不能点的节点同样要报（读屏焦点会停在上面）`() {
        val gap = audit(node(clickable = false, focusable = true)).gaps.single()
        assertEquals(UiAudit.REASON_NO_LABEL, gap.reason)
    }

    @Test
    fun `无可读名 - 既不可点也不可聚焦的装饰节点不报`() {
        assertTrue(audit(node(clickable = false, focusable = false)).gaps.isEmpty())
    }

    // ────────────────────────── 触达过小 ──────────────────────────

    @Test
    fun `触达过小 - 短边 dp 低于 44 就报，并给出 dp 实数`() {
        val gap = audit(node(text = "更多", bounds = listOf(0, 0, 40, 200))).gaps.single()
        assertEquals(UiAudit.REASON_SMALL_TARGET, gap.reason)
        // 短边取 min(width, height) = 40px ÷ 2.0 = 20dp
        assertEquals(20.0, gap.shortSideDp, 0.001)
    }

    @Test
    fun `触达过小 - 44dp 是放行线（87px 报、88px 不报）`() {
        // 44dp × density 2.0 = 88px；判定线与设计 token tap-min（44）同源
        assertEquals(1, audit(node(text = "更多", bounds = listOf(0, 0, 87, 300))).gapCount)
        assertTrue(audit(node(text = "更多", bounds = listOf(0, 0, 88, 300))).gaps.isEmpty())
    }

    @Test
    fun `触达过小 - 不可点的节点不按尺寸报（尺寸只约束点得动的目标）`() {
        assertTrue(
            audit(node(clickable = false, focusable = false, bounds = listOf(0, 0, 10, 10))).gaps.isEmpty(),
        )
    }

    @Test
    fun `触达过小 - 0 尺寸的可点节点不报（未布局的语义壳会淹没真实缺口）`() {
        assertTrue(audit(node(text = "有名字的壳", bounds = listOf(0, 0, 0, 0))).gaps.isEmpty())
    }

    @Test
    fun `触达过小 - 被可滚祖先裁掉半截的不判尺寸（真机实测：列表底部卡片）`() {
        // 实测形态：ScrollView 竖向 [0,602,1200,2430]，被截的卡片头 [48,2339,1152,2430]
        val report = audit(
            node(index = 0, className = "android.view.View", bounds = listOf(0, 602, 1200, 2430),
                clickable = false, scrollable = true, text = "滚动容器"),
            node(index = 1, parent = 0, text = "今日行程", bounds = listOf(48, 2339, 1152, 2430)),
        )
        assertTrue("被裁的可见高度不代表真实触达高度", report.gaps.isEmpty())
        assertEquals(1, report.clippedSkipped)
    }

    @Test
    fun `触达过小 - 完全落在可滚视口内的照常判（裁剪豁免不会放过真缺口）`() {
        val report = audit(
            node(index = 0, className = "android.view.View", bounds = listOf(0, 602, 1200, 2430),
                clickable = false, scrollable = true, text = "滚动容器"),
            node(index = 1, parent = 0, text = "更多", bounds = listOf(48, 700, 1088, 781)),
        )
        assertEquals(UiAudit.REASON_SMALL_TARGET, report.gaps.single().reason)
        assertEquals(0, report.clippedSkipped)
    }

    @Test
    fun `触达过小 - 顶部贴齐视口不算裁切（列表停在顶部时第一条照常判）`() {
        val report = audit(
            node(index = 0, className = "android.view.View", bounds = listOf(0, 602, 1200, 2430),
                clickable = false, scrollable = true, text = "滚动容器"),
            node(index = 1, parent = 0, text = "更多", bounds = listOf(48, 602, 1088, 660)),
        )
        assertEquals(UiAudit.REASON_SMALL_TARGET, report.gaps.single().reason)
    }

    @Test
    fun `触达过小 - 横向车厢里靠右裁掉的节点同样不判`() {
        val report = audit(
            node(index = 0, className = "android.widget.HorizontalScrollView", bounds = listOf(48, 346, 1152, 478),
                clickable = false, scrollable = true, text = "横条"),
            node(index = 1, parent = 0, text = "标签", bounds = listOf(1046, 382, 1152, 442)),
        )
        assertTrue(report.gaps.isEmpty())
        assertEquals(1, report.clippedSkipped)
    }

    @Test
    fun `触达过小 - 已经完全滚出视口的节点不判（bounds 是陈旧值）`() {
        val report = audit(
            node(index = 0, className = "android.view.View", bounds = listOf(0, 602, 1200, 2430),
                clickable = false, scrollable = true, text = "滚动容器"),
            node(index = 1, parent = 0, text = "早先的行", bounds = listOf(48, 100, 1088, 181)),
        )
        assertTrue(report.gaps.isEmpty())
        assertEquals(1, report.clippedSkipped)
    }

    @Test
    fun `触达过小 - 密度未取到时按 1dp=1px 兜底，而不是放行`() {
        val gap = UiAudit.run(listOf(node(text = "更多", bounds = listOf(0, 0, 40, 40))), density = 0f)
            .gaps.single()
        assertEquals(40.0, gap.shortSideDp, 0.001)
    }

    // ──────────────────────── 组合与统计 ────────────────────────

    @Test
    fun `两条都犯 - 同一节点出两行缺口，no_label 在前`() {
        val gaps = audit(node(bounds = listOf(0, 0, 40, 40))).gaps
        assertEquals(2, gaps.size)
        assertEquals(UiAudit.REASON_NO_LABEL, gaps[0].reason)
        assertEquals(UiAudit.REASON_SMALL_TARGET, gaps[1].reason)
    }

    @Test
    fun `统计 - 可点数与「有文案但没资源名」的可点数分开计`() {
        val report = audit(
            node(index = 0, text = "设置"),
            node(index = 1, text = "记账", viewId = "tab_book"),
            node(index = 2, desc = "返回"),
            node(index = 3, text = "纯文本", clickable = false),
        )
        assertEquals(3, report.clickableCount)
        // 只有 index=0（有文案、没资源名）计入；index=2 只有 desc，不算「有文案」
        assertEquals(1, report.textWithoutId)
    }

    @Test
    fun `摘要 - 只带前 5 条，一行一条能直接粘进报告`() {
        val many = (0 until 8).map { node(index = it, className = "android.widget.ImageButton") }
        val report = audit(*many.toTypedArray())
        assertEquals(8, report.gapCount)
        assertEquals(UiAudit.HEAD_LIMIT, report.headSummaries().size)
        val head = report.headSummaries().first()
        assertTrue(head, head.startsWith("no_label #0"))
        assertTrue(head, head.contains("ImageButton"))
        assertTrue(head, head.contains("short=60.0dp"))
    }

    @Test
    fun `摘要 - 文案里的换行与引号压成空格、单引号，不把 logcat 一行拆开`() {
        val gap = audit(node(text = "多\n行\"文案\"", bounds = listOf(0, 0, 40, 40))).gaps
            .first { it.reason == UiAudit.REASON_SMALL_TARGET }
        val summary = gap.summary()
        assertTrue(summary, !summary.contains('\n'))
        assertTrue(summary, summary.contains("多 行'文案'"))
    }

    @Test
    fun `放行 - 无缺口时 ok 为真，有缺口时 ok 为假`() {
        val clean = audit(node(text = "设置", bounds = listOf(0, 0, 240, 240)))
        assertTrue(clean.ok)
        assertEquals(0, clean.gapCount)
        assertTrue(!audit(node()).ok)
    }

    @Test
    fun `空屏 - 一个节点都没有时不算缺口（no_root 由执行层报）`() {
        val report = UiAudit.run(emptyList(), DENSITY)
        assertTrue(report.ok)
        assertEquals(0, report.nodeCount)
        assertEquals(0, report.clickableCount)
    }
}
