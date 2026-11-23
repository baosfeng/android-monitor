package com.bsfeng.monitor.parity

import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.bsfeng.monitor.domain.totp.TotpAccount
import com.bsfeng.monitor.domain.totp.TotpParams
import com.bsfeng.monitor.totp.ui.TotpAddScreen
import com.bsfeng.monitor.totp.ui.TotpBackupScreen
import com.bsfeng.monitor.totp.ui.TotpDetailScreen
import com.bsfeng.monitor.totp.ui.TotpManualEntryScreen
import com.bsfeng.monitor.totp.ui.TotpManualForm
import com.bsfeng.monitor.totp.ui.TotpScanScreen
import com.bsfeng.monitor.totp.ui.TotpScreen
import com.bsfeng.monitor.ui.theme.MonitorTheme
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * s22–s27 验证器六屏「实现真值」导出。
 *
 * 六屏的 ViewModel 依赖 Room / DataStore / CameraX，这里按原型 [.open-design/src/screens/s22..s27]
 * 的排布直接组合真实的屏 Composable（TotpScreen / TotpAddScreen / TotpScanScreen /
 * TotpManualEntryScreen / TotpDetailScreen / TotpBackupScreen），账户、参数、位置标签全部
 * 走原型里的同一份固定假数据，几何可与 proto-screens.json 的 s22–s27 直接对照。
 *
 * 口径：s22–s27 的 56 条原型可见锚点已全部落到生产代码（scripts/anchor-ledger.tsv 登记、
 * scripts/anchor-retired.tsv 对应行已销），导出结果与 proto-screens.json 逐屏一一对应，
 * 判据是 parity-native.mjs 每屏 P0 = 0。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h6000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class S22S27TotpParityTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    /** 固定时钟：14400000s = 2025-10-14 08:00:00Z，让五行的倒计时都有确定值。 */
    private val fixedNow = 1_760_000_000_000L + 24_000L

    /** 原型 s22 的 5 行：腾讯云 / 阿里云 / AWS / GitHub / Google。 */
    private fun protoAccounts(): List<TotpAccount> = listOf(
        TotpAccount(id = 1, issuer = "腾讯云", accountName = "ops@acme.cn", secretBase32 = "JBSWY3DPEHPK3PXP", sortOrder = 0),
        TotpAccount(id = 2, issuer = "阿里云", accountName = "dev@acme.cn", secretBase32 = "JBSWY3DPEHPK3PXP", sortOrder = 1),
        TotpAccount(id = 3, issuer = "AWS", accountName = "root@acme", secretBase32 = "JBSWY3DPEHPK3PXP", sortOrder = 2),
        TotpAccount(id = 4, issuer = "GitHub", accountName = "alice@acme.com", secretBase32 = "JBSWY3DPEHPK3PXP", sortOrder = 3),
        TotpAccount(id = 5, issuer = "Google", accountName = "alice.work@gmail.com", secretBase32 = "JBSWY3DPEHPK3PXP", sortOrder = 4),
    )

    private fun groupOf(account: TotpAccount): String =
        if (account.id <= 3L) "云服务" else "代码与账号"

    private fun export(screen: String, content: @androidx.compose.runtime.Composable () -> Unit) {
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                Box(Modifier.fillMaxSize().background(Color.White)) { content() }
            }
        }
        compose.waitForIdle()
        val items = withMergedTexts(
            collectTaggedNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), compose.density),
            collectMergedTexts(compose),
        )
        writeScreens(mapOf(screen to items), File("build/parity/impl-" + screen + ".json"))
        println("PARITY(" + screen + ") tags=" + items.size + " " + items.joinToString { it.id + "@" + it.y })
    }

    @Test
    fun exportS22() {
        val accounts = protoAccounts()
        export("s22") {
            TotpScreen(
                accounts = accounts,
                nowMillis = fixedNow,
                pausedIds = emptySet(),
                groupOf = ::groupOf,
            )
        }
    }

    @Test
    fun exportS23() {
        export("s23") { TotpAddScreen() }
    }

    @Test
    fun exportS24() {
        export("s24") { TotpScanScreen() }
    }

    @Test
    fun exportS25() {
        val form = TotpManualForm(
            issuer = "GitHub",
            accountName = "alice@acme.com",
            secretBase32 = "JBSWY3DPEHPK3PXP",
            params = TotpParams(),
        )
        export("s25") {
            TotpManualEntryScreen(
                form = form,
                onFormChange = {},
                nowMillis = fixedNow,
            )
        }
    }

    @Test
    fun exportS26() {
        val account = protoAccounts()[3]
        export("s26") {
            TotpDetailScreen(
                account = account,
                issuerDraft = account.issuer,
                accountDraft = account.accountName,
                onIssuerChange = {},
                onAccountChange = {},
                nowMillis = fixedNow,
                positionLabel = "第 4 位 / 共 5",
                sourceLabel = "扫码识别 · 2025-10-14",
                lastUsedLabel = "今天 09:26",
            )
        }
    }

    @Test
    fun exportS27() {
        export("s27") { TotpBackupScreen() }
    }
}
