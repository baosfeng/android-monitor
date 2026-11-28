package com.bsfeng.monitor.ui.quota

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.quota.QuotaAlertEvaluator
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.parity.RegisterComponentActivityRule
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.MonitorTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.io.FileOutputStream

/**
 * 主屏三态区的实现真值导出（原型 s18-alerts 三行结构）。
 *
 * 用三条假数据按 393dp 宽渲染并落 PNG：几何与原型对平后还要人眼确认
 * 「圆点颜色对、标题没被省略号吃掉、副行没换行成两行撑高」——这些都不是断言能全覆盖的。
 *
 * 假数据照原型 s18-额度.html:207-214：
 * - 已恢复 Ollama 主号 · 本周 22 分钟前 4% → 96%
 * - 快见底 open-design 工作区 A · 本月 34%
 * - 已耗尽 opencode-go 备用 · 5 小时 0%
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class QuotaAlertTriadShotTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    /** createAndroidComposeRule 需要 ComponentActivity 能启动：先注册进 Robolectric 清单。 */
    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    @Test
    fun `三态区三条假数据渲染成图`() {
        // 相对时间由 Section 按系统时钟算，假数据必须贴真实 now 才落到「22 分钟前」
        val now = System.currentTimeMillis() / 1000
        val triad = QuotaAlertTriad(
            rows = listOf(
                QuotaAlertTriadRow(
                    kind = QuotaAlertTriadKind.RECOVERED,
                    instanceId = "ollama",
                    displayName = "Ollama 主号",
                    itemTitle = "本周",
                    fraction = 0.96f,
                    fractionText = "96%",
                    detailText = "4% → 96% · 周一 00:00 重置 · 已发「已恢复」",
                    occurredAtEpochSecond = now - 22 * 60,
                    recoveredFrom = 0.04f,
                ),
                QuotaAlertTriadRow(
                    kind = QuotaAlertTriadKind.LOW,
                    instanceId = "opendesign",
                    displayName = "open-design 工作区 A",
                    itemTitle = "本月",
                    fraction = 0.34f,
                    fractionText = "34%",
                    detailText = "12-01 00:00 重置 · 按当前速率约 4 天耗尽",
                    occurredAtEpochSecond = now,
                ),
                QuotaAlertTriadRow(
                    kind = QuotaAlertTriadKind.DEAD,
                    instanceId = "opencode-go#2",
                    displayName = "opencode-go 备用",
                    itemTitle = "5 小时",
                    fraction = 0f,
                    fractionText = "0%",
                    detailText = "今天 17:00 自动恢复 · 期间可切主号",
                    occurredAtEpochSecond = now,
                ),
            ),
        )
        compose.setContent {
            MonitorTheme {
                Box(Modifier.width(393.dp).background(LocalDesignTokens.current.bg)) {
                    QuotaAlertTriadSection(triad = triad, onOpenSettings = {})
                }
            }
        }
        compose.waitForIdle()

        // 截图之外的兜底：三行真的渲染出来，空图不会靠断言蒙混过去
        compose.onNodeWithTag("s18-alerts").assertExists()
        compose.onNodeWithTag("s18-alert-back").assertExists()
        compose.onNodeWithTag("s18-alert-low").assertExists()
        compose.onNodeWithTag("s18-alert-dead").assertExists()
        compose.onNodeWithText("22 分钟前", substring = true).assertExists()
        assertEquals(3, triad.rows.size)

        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val png = File("../.open-design/impl-notes/quota-redesign/shots/impl-alerts.png")
        png.parentFile?.mkdirs()
        FileOutputStream(png).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SHOT(quota-alert-triad) png=" + png.absolutePath + " " + image.width + "x" + image.height)
    }

    @Test
    fun `恢复事件落盘后按时间倒序读回并截断到十二条`() {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        context.getSharedPreferences("quota_alerts", android.content.Context.MODE_PRIVATE)
            .edit().clear().commit()
        val store = QuotaAlertTriadStore(context) { 1_000L }
        store.append(
            listOf(
                QuotaAlertEvaluator.Recovery(
                    key = "ollama.session",
                    instanceId = "ollama",
                    itemTitle = "会话",
                    fromPercent = 4,
                    toPercent = 96,
                ),
            ),
        )
        val first = store.recent()
        assertEquals(1, first.size)
        assertEquals("session", first.first().itemId)
        assertEquals(4, first.first().fromPercent)
        assertEquals(96, first.first().toPercent)
        assertEquals(1_000L, first.first().occurredAtEpochSecond)

        // 余额型：金额与币种要一起留住，否则主屏只能显示「余额回升到」
        val later = QuotaAlertTriadStore(context) { 2_000L }
        later.append(
            listOf(
                QuotaAlertEvaluator.Recovery(
                    key = "deepseek.balance:cny",
                    instanceId = "deepseek",
                    itemTitle = "余额",
                    fromPercent = 0,
                    toPercent = 0,
                    remainingAmount = java.math.BigDecimal("200.00"),
                    unit = QuotaUnit.CNY,
                ),
            ),
        )
        val merged = later.recent()
        assertEquals(2, merged.size)
        assertEquals("¥200.00", merged.first().amountText)
        assertEquals(2_000L, merged.first().occurredAtEpochSecond)

        // 超过 12 条只留最近的（旧事件对「本轮恢复」没有意义）
        repeat(14) { index ->
            QuotaAlertTriadStore(context) { 3_000L + index }.append(
                listOf(QuotaAlertEvaluator.Recovery("ollama.s" + index, "ollama", "会话", 5, 90)),
            )
        }
        assertEquals(12, store.recent().size)
    }
}
