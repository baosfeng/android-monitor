package com.bsfeng.monitor.parity

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaProvider
import com.bsfeng.monitor.domain.quota.QuotaProviderRegistry
import com.bsfeng.monitor.domain.quota.QuotaRange
import com.bsfeng.monitor.domain.quota.QuotaUnit
import com.bsfeng.monitor.domain.quota.QuotaUsage
import com.bsfeng.monitor.quota.InMemoryQuotaInstanceCatalog
import com.bsfeng.monitor.quota.NoQuotaSnapshotCache
import com.bsfeng.monitor.quota.QuotaCredentialStore
import com.bsfeng.monitor.quota.QuotaRepository
import com.bsfeng.monitor.quota.QuotaSecretCipher
import com.bsfeng.monitor.ui.quota.QuotaScreen
import com.bsfeng.monitor.ui.quota.QuotaSettingsScreen
import com.bsfeng.monitor.ui.quota.QuotaViewModel
import com.bsfeng.monitor.ui.theme.MonitorTheme
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TestRule
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * s18 额度 / s19 额度设置「实现真值」导出。
 *
 * 与 S01/S04 的做法不同：这两屏的 ViewModel 只依赖 [QuotaRepository]（数据层本身不碰网络，
 * provider 是构造注入的），所以这里**不绕开屏**，直接跑真实 [QuotaScreen] / [QuotaSettingsScreen]：
 * 用假 provider + 软件密码器装配一份真 repository，喂原型 s18/s19 的同一批数据，再导出锚点几何。
 *
 * 口径：
 * - 时钟固定 9:41（repository 的 nowSeconds），否则「更新于 HH:mm」文案判据跟着机器时间飘；
 * - 数据照原型铺满：聚合态 3 家进榜（opencode-go 12% / open-design 34% / ollama 40%）+ 1 家灰行（DeepSeek 余额型）；
 * - s19 只有 Ollama 已配置，其余三家按原型是「未配置」。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h6000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class S18S19QuotaParityTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    private val zone: ZoneId = ZoneId.systemDefault()

    /** 原型「更新于 9:41」：固定时钟（epoch 秒）。 */
    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, 14, hour, minute).atZone(zone).toInstant().epochSecond

    /**
     * 软件密码器：Robolectric 沙箱里没有可用的 AndroidKeyStore provider
     * （生产只走 [com.bsfeng.monitor.quota.KeystoreQuotaSecretCipher]，见 QuotaCredentialStore 注释）。
     */
    private class PlainCipher : QuotaSecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain
        override fun decrypt(blob: ByteArray): ByteArray = blob
    }

    private fun percent(id: String, title: String, remaining: String, primary: Boolean = false): QuotaItem =
        QuotaItem(
            id = id,
            title = title,
            kind = QuotaKind.PERCENT,
            remaining = BigDecimal(remaining),
            unit = QuotaUnit.PERCENT,
            primary = primary,
        )

    /** 纯余额型（DeepSeek 的样子）：金额、没有总额 → 算不出百分比、不进榜单。 */
    private fun balance(id: String, title: String, remaining: String): QuotaItem =
        QuotaItem(
            id = id,
            title = title,
            kind = QuotaKind.MONEY,
            remaining = BigDecimal(remaining),
            total = null,
            unit = QuotaUnit.CNY,
        )

    /**
     * 假 provider：只替换「拉回来的额度项」，其余元数据按**真 provider 的注册值**写死，
     * 这样 s19 的连接状态与凭据类型说明与原型逐字一致
     * （DeepSeek 是平台登录令牌、opencode-go 无用量接口、Open Design 显示名带空格）。
     */
    private fun provider(
        id: String,
        name: String,
        items: List<QuotaItem>,
        balanceKind: QuotaCredentialKind = QuotaCredentialKind.API_KEY,
        usageKind: QuotaCredentialKind? = null,
    ): QuotaProvider = object : QuotaProvider {
        override val id: String = id
        override val displayName: String = name
        override val balanceCredentialKind: QuotaCredentialKind = balanceKind
        override val usageCredentialKind: QuotaCredentialKind? = usageKind
        override val supportsModelBreakdown: Boolean = false
        override val credentialHint: String = "API Key"
        override val credentialHelp: String = ""
        override suspend fun fetchItems(credential: String): List<QuotaItem> = items
        override suspend fun fetchUsage(credential: String, range: QuotaRange): QuotaUsage? = null
    }

    /**
     * 原型四家：ollama 会话 75 / 每周 40；opencode-go 5 小时 12 / 每周 46 / 每月 71；
     * open-design 月度 34；deepseek 只有余额（灰行）。
     */
    private fun protoProviders(): List<QuotaProvider> = listOf(
        provider(
            "ollama", "Ollama",
            listOf(
                percent("ollama.session", "会话", "75"),
                percent("ollama.weekly", "每周", "40", primary = true),
            ),
        ),
        provider(
            "deepseek", "DeepSeek",
            listOf(balance("deepseek.balance", "余额", "110")),
            // 真 provider：余额与用量共用平台登录令牌
            balanceKind = QuotaCredentialKind.PLATFORM_TOKEN,
            usageKind = QuotaCredentialKind.PLATFORM_TOKEN,
        ),
        provider(
            "opencode-go", "opencode-go",
            listOf(
                percent("oc.session", "5 小时", "12", primary = true),
                percent("oc.weekly", "每周", "46"),
                percent("oc.monthly", "每月", "71"),
            ),
        ),
        provider("opendesign", "Open Design", listOf(percent("od.monthly", "月度", "34", primary = true))),
    )

    /**
     * 真仓库 + 真 ViewModel：凭据先落盘（软件密码器），再 force 刷一次把状态灌成「有数据」，
     * 这样 [QuotaScreen] 组合时读到的是与原型同一份展开态，不是空态骨架。
     */
    private fun quotaViewModel(configured: List<String>): QuotaViewModel {
        val app = RuntimeEnvironment.getApplication()
        val providers = protoProviders()
        val credentials = QuotaCredentialStore(app, PlainCipher())
        // 凭据类型按 provider 真实口径存（DeepSeek 是平台登录令牌、Open Design 是 Cookie）：
        // 一律写 API_KEY 会让那两家「已存但读不到」，灰行与连接角标都跟着错
        configured.forEach { id ->
            val kind = providers.first { it.id == id }.balanceCredentialKind
            credentials.save(id, kind, "test-key-" + id)
        }
        val repository = QuotaRepository(
            registry = QuotaProviderRegistry(providers),
            credentials = credentials,
            nowSeconds = { at(9, 41) },
            cache = NoQuotaSnapshotCache,
            catalog = InMemoryQuotaInstanceCatalog(providers.map { it.id }),
        )
        // 非 JVM 调度：refresh 是 suspend 且内部只读注入的假数据，runBlocking 直接落状态
        runBlocking { configured.forEach { repository.refresh(it, force = true) } }
        return QuotaViewModel(app, repository)
    }

    @Test
    fun exportQuotaS18() {
        // 聚合态默认开（QuotaSettings.aggregateMode 默认 true），四家都配齐 → 3 行进榜 + 1 行灰行
        val viewModel = quotaViewModel(listOf("ollama", "deepseek", "opencode-go", "opendesign"))
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                QuotaScreen(onOpenSettings = {}, viewModel = viewModel)
            }
        }
        compose.waitForIdle()
        val items = withMergedTexts(
            collectTaggedNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), compose.density),
            collectMergedTexts(compose),
        )
        writeScreens(mapOf("s18" to items), File("build/parity/impl-s18.json"))
        println("PARITY(s18) tags=" + items.size + " " + items.joinToString { it.id })
    }

    @Test
    fun exportQuotaSettingsS19() {
        // 原型 s19：Ollama 已连接，其余三家未配置
        val viewModel = quotaViewModel(listOf("ollama"))
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                QuotaSettingsScreen(onBack = {}, viewModel = viewModel)
            }
        }
        compose.waitForIdle()
        val items = withMergedTexts(
            collectTaggedNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), compose.density),
            collectMergedTexts(compose),
        )
        writeScreens(mapOf("s19" to items), File("build/parity/impl-s19.json"))
        println("PARITY(s19) tags=" + items.size + " " + items.joinToString { it.id })
    }
}
