package com.bsfeng.monitor.parity

import android.app.Application
import android.content.ComponentName
import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * 原型 ↔ 实现 对照的测试侧支撑。
 *
 * 三件事：
 * 1. 把 ComponentActivity 注册进 Robolectric 的清单（createAndroidComposeRule 需要它可启动）；
 * 2. 递归读出语义树里每个带 testTag 的节点（几何转 dp，文案按节点拼）；
 * 3. 写成与 proto-screens.json 同构的 JSON，交给 scripts/parity-native.mjs 对照。
 */

/** 让 ComponentActivity 能被 ActivityScenario 启动：探针实测无需 ui-test-manifest 依赖。 */
class RegisterComponentActivityRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            val app: Application = RuntimeEnvironment.getApplication()
            shadowOf(app.packageManager).addActivityIfNotPresent(
                ComponentName(app.packageName, ComponentActivity::class.java.name),
            )
            base.evaluate()
        }
    }
}

/** 一个带 testTag 的语义节点，坐标单位 dp、相对根节点左上角。 */
data class TaggedNode(
    val id: String,
    val x: Float,
    val y: Float,
    val w: Float,
    val h: Float,
    val text: String,
)

/** 递归收集带 testTag 的节点（同名只取第一个，与 testTag 唯一性口径一致）。 */
fun collectTaggedNodes(root: SemanticsNode, density: Density): List<TaggedNode> {
    val out = LinkedHashMap<String, TaggedNode>()
    fun walk(node: SemanticsNode) {
        val tag = node.config.getOrNull(SemanticsProperties.TestTag)
        val bounds = node.boundsInRoot
        if (tag != null && !out.containsKey(tag) && !bounds.isEmpty) {
            val text = node.config.getOrNull(SemanticsProperties.Text)
                ?.joinToString("") { it.text }
                .orEmpty()
                .replace(Regex("\\s+"), " ")
                .trim()
            out[tag] = TaggedNode(
                id = tag,
                x = bounds.left / density.density,
                y = bounds.top / density.density,
                w = bounds.width / density.density,
                h = bounds.height / density.density,
                text = text.take(120),
            )
        }
        node.children.forEach { walk(it) }
    }
    walk(root)
    return out.values.toList()
}

/** 滚动收集：长页（s12 高 3910px）在 844dp 视口里只组合首屏，逐次上滑合并。 */
fun collectAllWithScroll(rule: androidx.compose.ui.test.junit4.ComposeContentTestRule): List<TaggedNode> {
    val acc = LinkedHashMap<String, TaggedNode>()
    var idleRounds = 0
    repeat(40) {
        val root = rule.onRoot().fetchSemanticsNode()
        val before = acc.size
        for (n in collectTaggedNodes(root, rule.density)) acc.putIfAbsent(n.id, n)
        if (acc.size == before) idleRounds++ else idleRounds = 0
        if (idleRounds >= 2) return acc.values.toList()
        // 小步滚动：一次 swipeUp() 会跳过整屏，滚动中途组合的区块来不及采集
        rule.onRoot().performTouchInput { swipeUp(startY = centerY + 160f, endY = centerY - 160f, durationMillis = 120) }
        rule.waitForIdle()
    }
    return acc.values.toList()
}

/** 把一屏的结果拼进总 JSON；node 形状与 scripts/export-proto-spec.sh 的产物一致。 */
fun writeScreens(screens: Map<String, List<TaggedNode>>, outFile: File) {
    val sb = StringBuilder("{\n")
    screens.entries.forEachIndexed { si, (screen, items) ->
        sb.append("  \"").append(screen).append("\": {\n    \"items\": [\n")
        items.forEachIndexed { i, n ->
            sb.append("      {\"id\":\"").append(esc(n.id)).append("\",")
                .append("\"x\":").append(fmt(n.x)).append(",")
                .append("\"y\":").append(fmt(n.y)).append(",")
                .append("\"w\":").append(fmt(n.w)).append(",")
                .append("\"h\":").append(fmt(n.h)).append(",")
                .append("\"text\":\"").append(esc(n.text)).append("\",")
                .append("\"visible\":true}")
            if (i != items.lastIndex) sb.append(",")
            sb.append("\n")
        }
        sb.append("    ]\n  }")
        if (si != screens.size - 1) sb.append(",")
        sb.append("\n")
    }
    sb.append("}\n")
    outFile.parentFile?.mkdirs()
    outFile.writeText(sb.toString())
}

private fun esc(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ")
private fun fmt(v: Float): String = "%.1f".format(v)