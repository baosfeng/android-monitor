package com.bsfeng.monitor.debug

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 控件树纯模型：选择器匹配 / 可点祖先回溯 / tag 清洗 / 文本截断。
 *
 * 这些都是「读树之后」的判定，纯 JVM 就能断言 —— 真机上重复点错一次要花几分钟，这里毫秒级。
 */
class DebugUiModelTest {

    private fun node(
        index: Int = 0,
        parent: Int = -1,
        text: String = "",
        desc: String = "",
        viewId: String = "",
        bounds: List<Int> = listOf(0, 0, 100, 50),
        clickable: Boolean = false,
        scrollable: Boolean = false,
        enabled: Boolean = true,
        window: Int = 0,
    ) = UiNode(
        index = index,
        parent = parent,
        depth = 0,
        window = window,
        className = "android.widget.TextView",
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
    )

    // ──────────────────────────── 选择器 ────────────────────────────

    @Test
    fun `文案 - 包含匹配、大小写不敏感、trim 后为空视为没给`() {
        val target = node(text = "功能开关")
        assertTrue(UiSelector(text = "功能").matches(target))
        assertTrue(UiSelector(text = "开关").matches(target))
        assertTrue(!UiSelector(text = "位置采集").matches(target))
        assertTrue(UiSelector(text = "SETTINGS").matches(node(text = "Settings")))
    }

    @Test
    fun `多条件 - 必须同时满足，任一不中就落空`() {
        val target = node(text = "保存", desc = "确认", viewId = "btn_save", bounds = listOf(10, 20, 110, 80))
        assertTrue(
            UiSelector(text = "保存", desc = "确认", id = "btn_save", bounds = UiRect(10, 20, 110, 80)).matches(target),
        )
        assertTrue(!UiSelector(text = "保存", desc = "取消").matches(target))
        assertTrue(!UiSelector(text = "保存", id = "btn_cancel").matches(target))
        assertTrue(!UiSelector(text = "保存", bounds = UiRect(0, 0, 10, 10)).matches(target))
    }

    @Test
    fun `空选择器 - isEmpty 为真（解析期必须拒掉它）`() {
        assertTrue(UiSelector().isEmpty)
        assertTrue(!UiSelector(text = "x").isEmpty)
        assertTrue(!UiSelector(bounds = UiRect(0, 0, 1, 1)).isEmpty)
    }

    @Test
    fun `describe - 只回显给了的条件`() {
        assertEquals("text=保存", UiSelector(text = "保存").describe())
        assertEquals(
            "text=保存 desc=确认 id=btn bounds=1,2,3,4",
            UiSelector(text = "保存", desc = "确认", id = "btn", bounds = UiRect(1, 2, 3, 4)).describe(),
        )
    }

    @Test
    fun `bounds 解析 - 四整数且宽高为正才放行`() {
        assertEquals(UiRect(10, 20, 110, 80), UiRect.parse("10,20,110,80"))
        assertEquals(UiRect(10, 20, 110, 80), UiRect.parse(" 10 , 20 , 110 , 80 "))
        assertNull(UiRect.parse("10,20,110"))
        assertNull(UiRect.parse("10,20,110,80,90"))
        assertNull(UiRect.parse("a,20,110,80"))
        assertNull(UiRect.parse("110,20,10,80"))
        assertNull(UiRect.parse("10,80,110,20"))
    }

    // ─────────────────────────── 树查询 ───────────────────────────

    @Test
    fun `可点祖先 - 文案节点自身不可点时回溯到包裹的 Row`() {
        val nodes = listOf(
            node(index = 0, parent = -1, clickable = false),
            node(index = 1, parent = 0, clickable = true, bounds = listOf(0, 0, 1080, 120)),
            node(index = 2, parent = 1, text = "功能开关"),
        )
        val ancestor = UiTree.clickableAncestor(nodes, 2)
        assertEquals(1, ancestor?.index)
        // 命中节点自己可点：直接用自己，不再往上跑
        assertEquals(1, UiTree.clickableAncestor(nodes, 1)?.index)
    }

    @Test
    fun `可点祖先 - 一路到根都没有可点节点时返回 null（交给手势兜底）`() {
        val nodes = listOf(
            node(index = 0, parent = -1),
            node(index = 1, parent = 0, text = "纯文本"),
        )
        assertNull(UiTree.clickableAncestor(nodes, 1))
        assertNull(UiTree.clickableAncestor(nodes, 99))
    }

    @Test
    fun `可点祖先 - 祖先 disabled 时不算可点`() {
        val nodes = listOf(
            node(index = 0, parent = -1, clickable = true, enabled = false),
            node(index = 1, parent = 0, text = "灰掉的项"),
        )
        assertNull(UiTree.clickableAncestor(nodes, 1))
    }

    @Test
    fun `findFirst - 按遍历顺序取第一个命中`() {
        val nodes = listOf(
            node(index = 0, text = "设置"),
            node(index = 1, text = "功能开关"),
            node(index = 2, text = "功能开关（副本）"),
        )
        assertEquals(1, UiTree.findFirst(nodes, UiSelector(text = "功能开关"))?.index)
        assertNull(UiTree.findFirst(nodes, UiSelector(text = "不存在")))
    }

    @Test
    fun `firstScrollable - 跳过 0 尺寸与 disabled 的容器`() {
        val nodes = listOf(
            node(index = 0, scrollable = true, bounds = listOf(0, 0, 0, 0)),
            node(index = 1, scrollable = true, enabled = false),
            node(index = 2, scrollable = true, bounds = listOf(0, 0, 1080, 2000)),
        )
        assertEquals(2, UiTree.firstScrollable(nodes)?.index)
        assertNull(UiTree.firstScrollable(emptyList()))
    }

    @Test
    fun `firstScrollable - 页顶横向横条不再吃掉竖向滚动（真机实测缺陷）`() {
        // 实测形态：RecyclerView 顶部一条 346..466 的 HorizontalScrollView，竖向列表在它下面
        val nodes = listOf(
            node(index = 0, scrollable = true, bounds = listOf(0, 346, 1080, 466)),
            node(index = 1, scrollable = true, bounds = listOf(0, 500, 1080, 2400)),
        )
        assertEquals(1, UiTree.firstScrollable(nodes, screenHeight = 2400)?.index)
        // 不知道屏幕高度时至少按方向挑，也仍是竖向那个
        assertEquals(1, UiTree.firstScrollable(nodes)?.index)
    }

    @Test
    fun `firstScrollable - 贯通屏幕中部的竖向容器优先于只露出顶部的小容器`() {
        val nodes = listOf(
            node(index = 0, scrollable = true, bounds = listOf(0, 0, 600, 700)),
            node(index = 1, scrollable = true, bounds = listOf(0, 200, 1080, 2400)),
        )
        assertEquals(1, UiTree.firstScrollable(nodes, screenHeight = 2400)?.index)
    }

    @Test
    fun `firstScrollable - 只有横向容器时不交给 down，返回 null 让手势兜底（真机实测 2）`() {
        // 设置页实测：唯一 scrollable 就是那条横向横条，向下滚它 = 滚错轴、内容一动不动
        val nodes = listOf(node(index = 0, scrollable = true, bounds = listOf(48, 346, 1152, 478)))
        assertNull(UiTree.firstScrollable(nodes, screenHeight = 2400))
        assertEquals(0, UiTree.firstScrollable(nodes, screenHeight = 2400, preferVertical = false)?.index)
        assertTrue(UiTree.anyScrollable(nodes))
    }

    @Test
    fun `anyScrollable - 能把「没得滚」与「只有反方向的轴」分开`() {
        assertTrue(!UiTree.anyScrollable(listOf(node(index = 0, scrollable = false))))
        assertTrue(!UiTree.anyScrollable(emptyList()))
        // 0 尺寸容器滚不动，不算「有得滚」
        assertTrue(!UiTree.anyScrollable(listOf(node(index = 0, scrollable = true, bounds = listOf(0, 0, 0, 0)))))
        assertTrue(UiTree.anyScrollable(listOf(node(index = 0, scrollable = true, bounds = listOf(0, 0, 10, 10)))))
    }

    @Test
    fun `firstScrollable - 横向滚动时优先横向容器`() {
        val nodes = listOf(
            node(index = 0, scrollable = true, bounds = listOf(0, 500, 1080, 2400)),
            node(index = 1, scrollable = true, bounds = listOf(0, 346, 1080, 466)),
        )
        assertEquals(1, UiTree.firstScrollable(nodes, screenHeight = 2400, preferVertical = false)?.index)
    }

    @Test
    fun `中心点 - 点按坐标取矩形中心`() {
        val target = node(bounds = listOf(100, 200, 300, 400))
        assertEquals(200, target.centerX)
        assertEquals(300, target.centerY)
    }

    // ─────────────────────────── tag / 截断 ───────────────────────────

    @Test
    fun `tag - 空值用默认名，非法字符替换，中文不成文件名`() {
        assertEquals(UiTag.DEFAULT, UiTag.sanitize(null))
        assertEquals(UiTag.DEFAULT, UiTag.sanitize("   "))
        assertEquals("settings", UiTag.sanitize(" settings "))
        assertEquals("a_b", UiTag.sanitize("a/b"))
        // 路径分隔符全部变成下划线：`../../etc/passwd` 只剩一个文件名，不可能穿越目录
        assertEquals("etc_passwd", UiTag.sanitize("../../etc/passwd"))
        assertEquals(UiTag.DEFAULT, UiTag.sanitize(".."))
        assertEquals("x".repeat(40), UiTag.sanitize("x".repeat(80)))
    }

    @Test
    fun `截断 - 200 字以内原样、超出截到 200`() {
        val short = "a".repeat(UiText.MAX_TEXT_CHARS)
        assertEquals(short, UiText.truncate(short))
        assertEquals(UiText.MAX_TEXT_CHARS, UiText.truncate("b".repeat(UiText.MAX_TEXT_CHARS + 1)).length)
        assertEquals("", UiText.truncate(""))
    }
}
