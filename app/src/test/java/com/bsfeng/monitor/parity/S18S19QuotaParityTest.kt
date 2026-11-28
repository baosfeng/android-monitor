package com.bsfeng.monitor.parity

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import com.bsfeng.monitor.domain.quota.QuotaBucket
import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import com.bsfeng.monitor.domain.quota.QuotaGranularity
import com.bsfeng.monitor.domain.quota.QuotaItem
import com.bsfeng.monitor.domain.quota.QuotaKind
import com.bsfeng.monitor.domain.quota.QuotaMetrics
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
import com.bsfeng.monitor.ui.quota.QuotaAlertTriadSection
import com.bsfeng.monitor.ui.quota.QuotaScreen
import com.bsfeng.monitor.ui.quota.QuotaSettingsScreen
import com.bsfeng.monitor.ui.quota.QuotaViewModel
import com.bsfeng.monitor.ui.quota.trackPairs
import com.bsfeng.monitor.ui.theme.LocalDesignTokens
import com.bsfeng.monitor.ui.theme.MonitorTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Before
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
import java.io.FileOutputStream
import java.math.BigDecimal
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * s18 额度 / s19 额度设置「实现真值」导出。
 *
 * 两屏的 ViewModel 只依赖 [QuotaRepository]（数据层本身不碰网络，provider 是构造注入的），
 * 所以这里**不绕开屏**，直接跑真实 [QuotaScreen] / [QuotaSettingsScreen]：用假 provider +
 * 软件密码器装配一份真 repository，喂原型 s18/s19 的同一批数据，再导出锚点几何。
 *
 * 口径：
 * - 时钟固定在 2026-10-14 的 9:41（repository 的 nowSeconds），否则「更新于 x 分钟前」跟着机器时间飘；
 * - s18 照原型铺 7 个账号（oc×2 / ol / od×2 / ds×2），provider 顺序也按原型（oc 在前）：
 *   主屏列表顺序 = 注册顺序 + 组内序号，对照时要与原型同序，否则每行 y 都会差一行；
 * - ds-1 先成功拉到 ¥110（8:41），9:41 再刷一次拿 401 → 状态保留旧额度 + 「令牌过期」徽标；
 * - 三态区的「已恢复」来自事件记录，直接写 quota_alerts 的 recoveries（与通知器同一份 prefs）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], qualifiers = "w393dp-h6000dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class S18S19QuotaParityTest {

    private val compose = createAndroidComposeRule<ComponentActivity>()

    @get:Rule
    val chain: TestRule = RuleChain.outerRule(RegisterComponentActivityRule()).around(compose)

    private val zone: ZoneId = ZoneId.systemDefault()

    /** 原型时间线的「现在」：repository 的 nowSeconds 读它。 */
    private var clock: Long = 0L

    /**
     * 主屏的「N 分钟前」读 System.currentTimeMillis（生产与数据层同一个系统时钟）。
     * Robolectric 会影子化 System 时钟，这里把它钉到原型的 9:41，否则相对时间会按真实日期飘。
     */
    /** 假数据的时间基准：跟真实系统时钟对齐（界面与 B 的三态区都读系统时钟）。 */
    private fun baseNow(): Long = System.currentTimeMillis() / 1000

    @Before
    fun alignClock() {
        clock = baseNow()
    }

    /** 原型「更新于 9:41」：固定时钟（epoch 秒）。 */
    private fun at(hour: Int, minute: Int): Long =
        LocalDateTime.of(2026, 10, 14, hour, minute).atZone(zone).toInstant().epochSecond

    private fun day(year: Int, month: Int, dayOfMonth: Int, hour: Int): Long =
        LocalDateTime.of(year, month, dayOfMonth, hour, 0).atZone(zone).toInstant().epochSecond

    /** 真实「今天 17:00」；已过点就顺延到明天，避免跑测试的时刻影响文案。 */
    private fun todayAt(hour: Int): Long {
        val at = java.time.LocalDate.now(zone).atTime(hour, 0).atZone(zone).toInstant().epochSecond
        return if (at <= baseNow()) at + 86_400 else at
    }

    /** 真实「明天 00:00」。 */
    private fun tomorrowStart(): Long =
        java.time.LocalDate.now(zone).plusDays(1).atStartOfDay(zone).toInstant().epochSecond

    /** 真实「下周一 00:00」。 */
    private fun nextMondayStart(): Long {
        val today = java.time.LocalDate.now(zone)
        val delta = ((8 - today.dayOfWeek.value) % 7).let { if (it == 0) 7 else it }
        return today.plusDays(delta.toLong()).atStartOfDay(zone).toInstant().epochSecond
    }

    /** 真实「N 天后 00:00 / N 天后 hh:00」。 */
    private fun inDaysStart(days: Long, hour: Int = 0): Long =
        java.time.LocalDate.now(zone).plusDays(days).atTime(hour, 0).atZone(zone).toInstant().epochSecond

    /**
     * 软件密码器：Robolectric 沙箱里没有可用的 AndroidKeyStore provider
     * （生产只走 KeystoreQuotaSecretCipher，见 QuotaCredentialStore 注释）。
     */
    private class PlainCipher : QuotaSecretCipher {
        override fun encrypt(plain: ByteArray): ByteArray = plain
        override fun decrypt(blob: ByteArray): ByteArray = blob
    }

    private fun percent(
        id: String,
        title: String,
        remaining: String,
        primary: Boolean = false,
        resetsAt: Long? = null,
    ): QuotaItem = QuotaItem(
        id = id,
        title = title,
        kind = QuotaKind.PERCENT,
        remaining = BigDecimal(remaining),
        unit = QuotaUnit.PERCENT,
        resetsAtEpochSecond = resetsAt,
        primary = primary,
    )

    /** 纯余额型（DeepSeek 的样子）：金额、没有总额 → 算不出百分比、不进榜单。 */
    private fun balance(id: String, title: String, remaining: String): QuotaItem = QuotaItem(
        id = id,
        title = title,
        kind = QuotaKind.MONEY,
        remaining = BigDecimal(remaining),
        total = null,
        unit = QuotaUnit.CNY,
    )

    private fun fakeUsage(seed: Int, range: QuotaRange, now: Long): QuotaUsage {
        val count = when (range) {
            QuotaRange.LAST_24_HOURS -> 24
            QuotaRange.LAST_7_DAYS -> 7
            QuotaRange.LAST_30_DAYS -> 30
        }
        val step = range.seconds / count
        val start = now - range.seconds
        val buckets = (0 until count).map { index ->
            val value = ((index * 37 + seed * 11) % 90 + 10).toFloat()
            QuotaBucket(
                startEpochSecond = start + index * step,
                endEpochSecond = start + (index + 1) * step,
                partial = index == count - 1,
                metrics = QuotaMetrics(requestCount = value.toInt()),
            )
        }
        val granularity = if (count == 24) QuotaGranularity.HOUR else QuotaGranularity.DAY
        return QuotaUsage(
            range = range,
            granularity = granularity,
            startEpochSecond = start,
            endEpochSecond = now,
            totals = QuotaMetrics(requestCount = buckets.sumOf { it.metrics.requestCount }),
            buckets = buckets,
        )
    }

    /**
     * 假 provider：额度按**凭据**分派（同一类型挂多个账号时，两个账号的 Key 不同），
     * 其余元数据按真 provider 的注册值写死，这样 s19 的连接状态与凭据说明与原型逐字一致。
     */
    private inner class FakeProvider(
        override val id: String,
        override val displayName: String,
        override val balanceCredentialKind: QuotaCredentialKind,
        override val usageCredentialKind: QuotaCredentialKind?,
        private val usageSeed: Int,
        private val itemsFor: (String) -> List<QuotaItem>,
    ) : QuotaProvider {
        override val supportsModelBreakdown: Boolean = false
        override val credentialHint: String = "API Key"
        override val credentialHelp: String = ""
        override suspend fun fetchItems(credential: String): List<QuotaItem> = itemsFor(credential)
        override suspend fun fetchUsage(credential: String, range: QuotaRange): QuotaUsage =
            fakeUsage(usageSeed, range, at(9, 41))
    }

    /** ds-1 的第二次拉取会 401（令牌过期），其余账号一直正常。 */
    private var deepseekExpired = false

    private fun ds1Items(key: String): List<QuotaItem> {
        if (deepseekExpired) {
            throw com.bsfeng.monitor.domain.quota.QuotaError(
                com.bsfeng.monitor.domain.quota.QuotaErrorKind.UNAUTHORIZED,
            )
        }
        if (key.endsWith("deepseek")) deepseekExpired = true
        return listOf(balance("deepseek.balance", "余额", "110"))
    }

    /**
     * 原型 s18 的 7 个账号（顺序也照原型：oc → ol → od → ds）。
     *
     * 窗口与重置时刻照 s18-额度.html:203：5 小时今天 17:00 / 本周周一 00:00 / 本月 12-01 00:00。
     */
    private fun s18Providers(): List<QuotaProvider> {
        val today17 = todayAt(17)
        val nextMonday = nextMondayStart()
        val monthReset = inDaysStart(20)
        val periodReset = inDaysStart(17, 15)
        return listOf(
            FakeProvider(
                "opencode-go", "opencode-go",
                QuotaCredentialKind.API_KEY, QuotaCredentialKind.API_KEY, usageSeed = 1,
            ) { key ->
                if (key.endsWith("#2")) {
                    listOf(
                        percent("oc.session", "5 小时", "0", primary = true, resetsAt = today17),
                        percent("oc.weekly", "本周", "62", resetsAt = nextMonday),
                        percent("oc.monthly", "本月", "88", resetsAt = monthReset),
                    )
                } else {
                    listOf(
                        percent("oc.session", "5 小时", "12", primary = true, resetsAt = today17),
                        percent("oc.weekly", "本周", "46", resetsAt = nextMonday),
                        percent("oc.monthly", "本月", "71", resetsAt = monthReset),
                    )
                }
            },
            FakeProvider(
                "ollama", "Ollama",
                QuotaCredentialKind.API_KEY, QuotaCredentialKind.API_KEY, usageSeed = 2,
            ) {
                listOf(
                    percent("ollama.today", "今日", "63", resetsAt = tomorrowStart()),
                    percent("ollama.weekly", "本周", "96", primary = true, resetsAt = nextMonday),
                    percent("ollama.period", "订阅周期", "72", resetsAt = periodReset),
                )
            },
            FakeProvider(
                "opendesign", "Open Design",
                QuotaCredentialKind.SESSION_COOKIE, QuotaCredentialKind.SESSION_COOKIE, usageSeed = 3,
            ) { key ->
                if (key.endsWith("#2")) {
                    listOf(percent("od.monthly", "本月", "0", primary = true, resetsAt = monthReset))
                } else {
                    listOf(percent("od.monthly", "本月", "34", primary = true, resetsAt = monthReset))
                }
            },
            FakeProvider(
                "deepseek", "DeepSeek",
                QuotaCredentialKind.PLATFORM_TOKEN, QuotaCredentialKind.PLATFORM_TOKEN, usageSeed = 4,
            ) { key ->
                if (key.endsWith("#2")) {
                    listOf(balance("deepseek.balance", "余额", "8.60"))
                } else {
                    ds1Items(key)
                }
            },
        )
    }

    /**
     * 原型 s19 的三家（不含 opencode-go）：Ollama 每周 96%、DeepSeek 鉴权失败、open-design 月度 34%。
     *
     * 多账号 = `quotaViewModel(secondOllama = true)` 再 add 一个 Ollama 实例（原型「备用号」）。
     */
    private fun s19Providers(): List<QuotaProvider> = listOf(
        FakeProvider("ollama", "Ollama", QuotaCredentialKind.API_KEY, QuotaCredentialKind.API_KEY, 2) {
            listOf(
                percent("ollama.session", "会话", "75"),
                percent("ollama.weekly", "每周", "96", primary = true),
            )
        },
        FakeProvider(
            "deepseek", "DeepSeek",
            QuotaCredentialKind.PLATFORM_TOKEN, QuotaCredentialKind.PLATFORM_TOKEN, 4,
        ) { throw com.bsfeng.monitor.domain.quota.QuotaError(com.bsfeng.monitor.domain.quota.QuotaErrorKind.UNAUTHORIZED) },
        FakeProvider(
            "opendesign", "Open Design",
            QuotaCredentialKind.SESSION_COOKIE, QuotaCredentialKind.SESSION_COOKIE, 3,
        ) { listOf(percent("od.monthly", "月度", "34", primary = true)) },
    )

    /**
     * s19 专用的仓库装配：数据层时钟 9:36（页面 9:41 → 「5 分钟前更新」），并追加 Ollama 备用号。
     *
     * 与 s18 的 [quotaViewModel] 分开：s19 不铺 7 个账号，也不做「令牌过期」的时间线。
     */
    private fun s19ViewModel(): QuotaViewModel {
        val app = RuntimeEnvironment.getApplication()
        val providers = s19Providers()
        val credentials = QuotaCredentialStore(app, PlainCipher())
        listOf("ollama", "deepseek", "opendesign").forEach { id ->
            credentials.save(id, providers.first { it.id == id }.balanceCredentialKind, "test-key-" + id)
        }
        val repository = QuotaRepository(
            registry = QuotaProviderRegistry(providers),
            credentials = credentials,
            nowSeconds = { at(9, 36) },
            cache = NoQuotaSnapshotCache,
            catalog = InMemoryQuotaInstanceCatalog(providers.map { it.id }),
        )
        repository.addInstance("ollama")
        runBlocking {
            listOf("ollama", "deepseek", "opendesign").forEach { repository.refresh(it, force = true) }
        }
        return QuotaViewModel(app, repository)
    }

    /** 三态区的「已恢复」：写恢复事件 prefs（与 QuotaAlertNotifier 同一份）。 */
    private fun seedRecovery() {
        // 三态区（B 的组件）自己读系统时钟算「22 分钟前」，这里跟着真实时钟走；
        // 主屏正文的时间基准由 QuotaScreen(nowEpochSecond = …) 注入，两边不打架。
        val occurredAt = System.currentTimeMillis() / 1000 - 22 * 60
        val reset = inDaysStart(5)
        val json = "[{\"iid\":\"ollama\",\"item\":\"weekly\",\"title\":\"本周\"," +
            "\"from\":4,\"to\":96,\"at\":" + occurredAt + ",\"reset\":" + reset + "}]"
        RuntimeEnvironment.getApplication()
            .getSharedPreferences("quota_alerts", android.content.Context.MODE_PRIVATE)
            .edit().putString("recoveries", json).commit()
    }

    /**
     * 真仓库 + 真 ViewModel：凭据先落盘（软件密码器），再按原型时间线 force 刷。
     *
     * [extraInstances] 里每加一个类型 id 就多一个账号（id 为 类型#2），用来铺原型的 7 行。
     */
    private fun quotaViewModel(
        providers: List<QuotaProvider>,
        configured: List<String>,
        extraInstances: List<String> = emptyList(),
    ): QuotaViewModel {
        val app = RuntimeEnvironment.getApplication()
        val credentials = QuotaCredentialStore(app, PlainCipher())
        configured.forEach { id ->
            val typeId = id.substringBefore("#")
            val provider = providers.first { it.id == typeId }
            credentials.save(id, provider.balanceCredentialKind, "test-key-" + id)
            provider.usageCredentialKind
                ?.takeIf { it != provider.balanceCredentialKind }
                ?.let { credentials.save(id, it, "test-key-" + id + "-usage") }
        }
        val catalog = InMemoryQuotaInstanceCatalog(providers.map { it.id })
        extraInstances.forEach { typeId -> catalog.add(typeId) }
        val repository = QuotaRepository(
            registry = QuotaProviderRegistry(providers),
            credentials = credentials,
            nowSeconds = { clock },
            cache = NoQuotaSnapshotCache,
            catalog = catalog,
        )
        // 原型时间线：ds-1 一小时前成功、ds-2 八分钟前、其余四分钟前；最后 ds-1 再刷一次（401）
        val base = baseNow()
        configured.forEach { id ->
            clock = when {
                id == "deepseek" -> base - 3_600
                id == "deepseek#2" -> base - 480
                else -> base - 240
            }
            runBlocking { repository.refresh(id, force = true) }
        }
        clock = base
        if ("deepseek" in configured) runBlocking { repository.refresh("deepseek", force = true) }
        return QuotaViewModel(app, repository)
    }

    @Test
    fun exportQuotaS18() {
        seedRecovery()
        val viewModel = quotaViewModel(
            providers = s18Providers(),
            configured = listOf(
                "opencode-go", "opencode-go#2", "ollama", "opendesign", "opendesign#2", "deepseek", "deepseek#2",
            ),
            extraInstances = listOf("opencode-go", "opendesign", "deepseek"),
        )
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                QuotaScreen(onOpenSettings = {}, viewModel = viewModel)
            }
        }
        compose.waitForIdle()
        // 调试用：把每个账号的 fetchedAt / error 落一份（确认失败态与相对时间的输入）
        File("/tmp/s18-states.txt").writeText(
            viewModel.providerStates.value.values.joinToString("\n") { s ->
                s.instanceId + " fetchedAt=" + s.fetchedAtEpochSecond + " now=" + clock +
                    " error=" + s.errorMessage + " stale=" + s.stale + " items=" + s.items.size
            } + "\nSYSTEM_NOW=" + System.currentTimeMillis() / 1000,
        )
        val items = withMergedTexts(
            collectTaggedNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), compose.density),
            collectMergedTexts(compose),
        )
        writeScreens(mapOf("s18" to items), File("build/parity/impl-s18.json"))
        println("PARITY(s18) tags=" + items.size + " " + items.joinToString { it.id })
    }

    @Test
    fun exportQuotaSettingsS19() {
        // 原型 s19：Ollama 主号（5 分钟前拉过）+ 备用号（未配置）、DeepSeek 令牌过期、open-design 已连接
        val viewModel = s19ViewModel()
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                QuotaSettingsScreen(onBack = {}, viewModel = viewModel, nowSeconds = { at(9, 41) })
            }
        }
        compose.waitForIdle()
        val items = withMergedTexts(
            collectTaggedNodes(compose.onRoot(useUnmergedTree = true).fetchSemanticsNode(), compose.density),
            collectMergedTexts(compose),
        )
        writeScreens(mapOf("s19" to items), File("build/parity/impl-s19.json"))
        // 交付截图：393 宽整页（视口高 6000dp，按最后一个锚点的底 + 24 裁掉空白）
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val contentBottomDp = items
            .filter { it.id != "s19-content" && it.id != "screen-s19" }
            .maxOf { it.y + it.h } + 24f
        val cropHeight = (contentBottomDp * compose.density.density).toInt().coerceIn(1, image.height)
        val cropped = Bitmap.createBitmap(image, 0, 0, image.width, cropHeight)
        val scaled = Bitmap.createScaledBitmap(cropped, 393, cropHeight * 393 / image.width, true)
        val png = File("../.open-design/impl-notes/quota-redesign/shots/impl-s19.png")
        png.parentFile?.mkdirs()
        FileOutputStream(png).use { scaled.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("PARITY(s19) tags=" + items.size + " " + items.joinToString { it.id })
        println("SHOT(s19) " + png.absolutePath + " " + scaled.width + "x" + scaled.height)
    }

    /**
     * 三态区截图（393dp 宽，原型 s18-alerts 三行）。
     *
     * 与 s18/s19 导出同一个类：原生渲染只在**已有的** NATIVE 类里初始化一次，
     * 不再为一张图新开 @GraphicsMode(NATIVE) 测试类（并行 fork 下多一个初始化点，
     * 就多一处 RenderNodeNatives 偶发）。假数据见 alertTriadShotFixture。
     */
    @Test
    fun exportAlertTriadShot() {
        val triad = alertTriadShotFixture(System.currentTimeMillis() / 1000)
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                Box(Modifier.width(393.dp).background(LocalDesignTokens.current.bg)) {
                    QuotaAlertTriadSection(triad = triad, onOpenSettings = {})
                }
            }
        }
        compose.waitForIdle()
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val png = saveShotPng(image, "../.open-design/impl-notes/quota-redesign/shots/impl-alerts.png")
        println("SHOT(alert-triad) " + png.absolutePath + " " + image.width + "x" + image.height)
    }

    /**
     * 393×852 视口下取三张图 + 实测数字（交付报告的「实测数字表」来源）。
     *
     * 与上面的导出用同一份 7 账号数据；视口高改成 852（真实手机），所以三态区与组件样张
     * 要滚下去才看得到 —— 第三张图就是滚到那里。
     */
    @Test
    @Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
    fun captureS18ShotsAndMetrics() {
        seedRecovery()
        val viewModel = quotaViewModel(
            providers = s18Providers(),
            configured = listOf(
                "opencode-go", "opencode-go#2", "ollama", "opendesign", "opendesign#2", "deepseek", "deepseek#2",
            ),
            extraInstances = listOf("opencode-go", "opendesign", "deepseek"),
        )
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                QuotaScreen(onOpenSettings = {}, viewModel = viewModel)
            }
        }
        compose.waitForIdle()
        val density = compose.density.density
        val lines = mutableListOf<String>()

        fun root() = compose.onRoot(useUnmergedTree = true).fetchSemanticsNode()
        fun nodes() = collectTaggedNodes(root(), compose.density)
        fun topOf(tag: String) = nodes().firstOrNull { it.id == tag }?.y ?: -1f

        // 1) 首屏
        shot("impl-s18-first.png")
        val first = nodes()
        val rows = first.filter { it.id.startsWith("s18-row-") }
        lines += "首屏可见账号行数=" + rows.count { it.y < 852f && it.y + it.h > 0f } + "（共 " + rows.size + " 行）"
        lines += "行高dp=" + rows.firstOrNull()?.h
        lines += "首屏横向溢出锚点数=" + first.count { it.x < -0.5f || it.x + it.w > 393.5f }
        lines += "结论条 top@scrollY=0=" + topOf("s18-concl")
        // 触达区在**首屏未滚动**时测：滚动后顶部行会被 LazyColumn 裁切，裁出来的 5dp 不是控件尺寸
        lines += "小于48dp可点元素数=" + smallClickableCount(root(), density)
        lines += "小于44dp可点元素数=" + smallClickableCount(root(), density, limit = 44f)
        lines += "可点元素尺寸清单=" + clickableSizes(root(), density)
        // 横条宽度（判据：fill / track = fraction ±0.02，断言见 QuotaMeterTrackTest）
        lines += "账号行横条 fill/track=" + trackPairs(root(), density).joinToString(",") { (track, fill) ->
            "%.1f/%.1f=%.3f".format(fill, track, if (track > 0f) fill / track else 0f)
        }

        // 2) 展开第一行（含柱图）
        compose.onNodeWithTag("s18-content").performScrollToNode(hasTestTag("s18-row-oc-main"))
        compose.onNodeWithTag("s18-row-oc-main").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("s18-detail-oc-main").assertExists()
        shot("impl-s18-expanded.png")
        val expanded = nodes()
        val chartH = expanded.firstOrNull { it.id == "s18-chart-oc-main" }?.h
        lines += "展开区横条 fill/track=" + trackPairs(root(), density).joinToString(",") { (track, fill) ->
            "%.1f/%.1f=%.3f".format(fill, track, if (track > 0f) fill / track else 0f)
        }
        lines += "展开后柱图高dp=" + chartH
        // 柱图的读屏描述里带「共 N 根，峰值 X」：纯绘制图没有可数节点，从这里取真值
        val chartNode = compose.onNodeWithTag("s18-chart-oc-main").fetchSemanticsNode()
        val chartDesc = chartNode.config
            .getOrNull(androidx.compose.ui.semantics.SemanticsProperties.StateDescription)
            .orEmpty()
        lines += "柱图描述=" + chartDesc
        lines += "展开态横向溢出锚点数=" + expanded.count { it.x < -0.5f || it.x + it.w > 393.5f }

        // 3) 滚到三态提醒与组件样张
        compose.onNodeWithTag("s18-content").performScrollToNode(hasTestTag("s18-widget"))
        compose.waitForIdle()
        shot("impl-s18-scrolled.png")
        lines += "scrolled 后 s18-widget 可见=" + nodes().any { it.id == "s18-widget" }

        // sticky 结论条在三个滚动位置都钉在页头下沿（顶不动）
        val content = compose.onNodeWithTag("s18-content")
        val stickyTops = mutableListOf<Float>()
        listOf(0f, 150f, 300f).forEach { delta ->
            if (delta > 0f) {
                content.performSemanticsAction(SemanticsActions.ScrollBy) { action -> action(0f, delta) }
                compose.waitForIdle()
            }
            stickyTops += topOf("s18-concl")
        }
        lines += "结论条 top@scrollY=0/150/300=" + stickyTops.joinToString("/")

        val out = File("../.open-design/impl-notes/quota-redesign/shots/impl-s18-metrics.txt")
        out.parentFile?.mkdirs()
        out.writeText(lines.joinToString("\n") + "\n")
        println("METRICS(s18)\n" + lines.joinToString("\n"))
    }

    /**
     * 首装空态：一个 Key 都没配时列表位置换成引导，点入口进额度设置。
     *
     * 这条路由原型没有画，所以**不打锚点**（检查见 QuotaEmptyAccounts 注释）；验收靠文案断言。
     */
    @Test
    @Config(sdk = [34], qualifiers = "w393dp-h852dp-xxhdpi")
    fun emptyStateGuidesToSettings() {
        var opened = false
        val viewModel = quotaViewModel(providers = s19Providers(), configured = emptyList())
        compose.setContent {
            MonitorTheme(themeId = "apple") {
                QuotaScreen(onOpenSettings = { opened = true }, viewModel = viewModel)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithText("还没有配置额度账号").assertExists()
        compose.onNodeWithText("去额度设置添加").performClick()
        assertTrue("空态入口要真的能进额度设置", opened)
    }

    private fun shot(name: String) {
        val image = compose.onRoot().captureToImage().asAndroidBitmap()
        val png = File("../.open-design/impl-notes/quota-redesign/shots/" + name)
        png.parentFile?.mkdirs()
        FileOutputStream(png).use { image.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("SHOT(s18) " + png.absolutePath + " " + image.width + "x" + image.height)
    }

    /** 可点元素清单（tag + 宽高 dp）：用来核对「44dp 判定线」是不是浮点噪声。 */
    private fun clickableSizes(
        node: androidx.compose.ui.semantics.SemanticsNode,
        density: Float,
    ): String {
        val out = mutableListOf<String>()
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            val tag = n.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag) ?: "-"
            val clickable = n.config.getOrNull(SemanticsActions.OnClick) != null
            if (clickable && !n.boundsInRoot.isEmpty) {
                val w = n.size.width / density
                val h = n.size.height / density
                out += tag + "(%.1f×%.1f)".format(w, h)
            }
            n.children.forEach { walk(it) }
        }
        walk(node)
        return out.joinToString(",")
    }

    /** 统计 <48dp 的可点元素（无障碍基线判定线是 44dp，这里按 48dp 更严）。 */
    private fun smallClickableCount(
        node: androidx.compose.ui.semantics.SemanticsNode,
        density: Float,
        limit: Float = 48f,
    ): Int {
        var count = 0
        fun walk(n: androidx.compose.ui.semantics.SemanticsNode) {
            val clickable = n.config.getOrNull(SemanticsActions.OnClick) != null
            // 用未裁切边界量尺寸：屏幕下沿被 LazyColumn 裁掉的行会量成 41.7dp，那不是控件尺寸
            val w = n.size.width / density
            val h = n.size.height / density
            if (clickable && (w < limit || h < limit) && !n.boundsInRoot.isEmpty) count++
            n.children.forEach { walk(it) }
        }
        walk(node)
        return count
    }
}
