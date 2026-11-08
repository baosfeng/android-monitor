package com.bsfeng.monitor.autobook

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 账目日志的**源码级门禁**（安全审计 S-03）。
 *
 * 为什么用静态断言而不是抓 logcat：真实链路要等微信/支付宝的支付页，模拟器里造不出来；
 * 而「哪条日志语句带账目字段」是纯静态事实，扫源码即可，且能进 CI 常驻防回归。
 * 抓 logcat 只能证明「这次没打出来」，抓不到「下次谁又加了一条」。
 *
 * 判据：`autobook/` 下**裸 `Log.v/d/i/w/e` 的参数里不得出现**账目或屏幕原文的字段名。
 * 允许的两条出路：
 * - 结构性日志（去重命中、找不到窗口、耗时）继续用 `Log.x`；
 * - 含用户数据的走 `DebugConfig.log`（release 恒静默、默认关闭，见 DebugConfig）。
 */
class AutoBookLogPrivacyTest {

    /** 单元测试的工作目录是 app/（Gradle 默认把模块目录设为 CWD）。 */
    private val sourceRoot = File("src/main/java/com/bsfeng/monitor/autobook")

    /** 账目 / 屏幕原文的字段名：出现在日志参数里就等于把隐私写进 logcat。 */
    private val forbidden = listOf(
        "金额", "商户", "备注", "账单页面行", "文本=[",
        "amount=", "merchant=", "remark=", "payMethod=",
    )

    private val logCall = Regex("""Log\.[vdiwe]\(""")

    @Test
    fun `autobook 目录存在且可扫描`() {
        assertTrue("找不到源码目录：${sourceRoot.absolutePath}（CWD=${File(".").absolutePath}）", sourceRoot.isDirectory)
        assertTrue("目录里没有 kotlin 文件", sourceRoot.listFiles { f -> f.name.endsWith(".kt") }!!.isNotEmpty())
    }

    @Test
    fun `裸日志调用里不出现账目与屏幕原文字段`() {
        val offenders = mutableListOf<String>()
        sourceRoot.listFiles { f -> f.name.endsWith(".kt") }!!.forEach { file ->
            file.readLines().forEachIndexed { index, line ->
                if (!logCall.containsMatchIn(line)) return@forEachIndexed
                // 单行 Log.x(...) 直接判；跨行调用由下面那条用例按调用块判
                val hit = forbidden.firstOrNull { line.contains(it) }
                if (hit != null) {
                    offenders += "${file.name}:${index + 1} 裸 Log 里含「$hit」→ ${line.trim()}"
                }
            }
        }
        assertTrue(
            "账目 / 屏幕原文必须走 DebugConfig.log（release 恒静默），不要写进裸 Log：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `跨行日志调用块同样受约束`() {
        val offenders = mutableListOf<String>()
        sourceRoot.listFiles { f -> f.name.endsWith(".kt") }!!.forEach { file ->
            val lines = file.readLines()
            var i = 0
            while (i < lines.size) {
                if (logCall.containsMatchIn(lines[i])) {
                    // 取这次调用的参数块：从当前行到括号配平为止（最多看 12 行）
                    val block = StringBuilder()
                    var depth = 0
                    var j = i
                    do {
                        val current = lines[j]
                        block.append(current).append('\n')
                        depth += current.count { it == '(' } - current.count { it == ')' }
                        j++
                    } while (depth > 0 && j < lines.size && j - i < 12)
                    val hit = forbidden.firstOrNull { block.contains(it) }
                    if (hit != null) {
                        offenders += "${file.name}:${i + 1} 日志块含「$hit」"
                    }
                    i = j
                } else {
                    i++
                }
            }
        }
        assertTrue(
            "跨行拼接的日志同样不能用账目字段：\n" + offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `含用户数据的诊断确实走了 DebugConfig 闸门`() {
        val gated = sourceRoot.listFiles { f -> f.name.endsWith(".kt") }!!
            .sumOf { file -> file.readLines().count { it.contains("DebugConfig.log(") } }
        // 账单页行 / 备注 / 红包金额 / 不可见节点样本 / 分类回退 —— 目前 6 处
        assertTrue("DebugConfig.log 调用点太少（$gated），有敏感日志漏在裸 Log 里？", gated >= 6)
    }
}
