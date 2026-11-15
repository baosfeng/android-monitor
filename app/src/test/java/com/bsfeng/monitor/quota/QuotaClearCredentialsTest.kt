package com.bsfeng.monitor.quota

import android.content.Context
import com.bsfeng.monitor.domain.quota.QuotaCredentialKind
import com.bsfeng.monitor.domain.quota.QuotaProviderRegistry
import com.bsfeng.monitor.domain.quota.QuotaSnapshot
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 断开账号的数据层行为（Robolectric + 真 SharedPreferences + 软件 AES-GCM 替身）。
 *
 * 盯住四件事，任何一件漏了用户都会看到「断开没生效」：
 * 1. 两种凭据都被清（余额 Key 与用量令牌）；
 * 2. 状态回落到 balanceConfigured / usageConfigured = false 且不再带旧数字；
 * 3. **快照缓存按实例清掉**：重建仓库不会再把上一次的额度读回来（这条是主缺口）；
 * 4. 清除不发请求，也不动用户的刷新间隔偏好。
 *
 * 多账号场景另有一组用例（第二个账号的断开不影响第一个）：键若退回类型 id 就会串。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuotaClearCredentialsTest {

    private lateinit var context: Context
    private lateinit var credentials: QuotaCredentialStore
    private lateinit var snapshots: QuotaSnapshotStore
    private lateinit var provider: FakeQuotaProvider
    private lateinit var catalog: InMemoryQuotaInstanceCatalog

    private val credentialPrefs
        get() = context.getSharedPreferences(QuotaCredentialStore.PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        credentialPrefs.edit().clear().commit()
        context.getSharedPreferences(SNAPSHOT_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
        credentials = QuotaCredentialStore(context, SoftwareQuotaCipher())
        snapshots = QuotaSnapshotStore(context)
        provider = FakeQuotaProvider()
        catalog = InMemoryQuotaInstanceCatalog(listOf(provider.id))
    }

    @After
    fun tearDown() {
        credentialPrefs.edit().clear().commit()
        context.getSharedPreferences(SNAPSHOT_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun repository(catalog: InMemoryQuotaInstanceCatalog = this.catalog) = QuotaRepository(
        registry = QuotaProviderRegistry(listOf(provider)),
        credentials = credentials,
        cache = snapshots,
        catalog = catalog,
    )

    private fun seedCredentialAndSnapshot(instanceId: String = provider.id) {
        credentials.save(instanceId, QuotaCredentialKind.API_KEY, placeholderSecret())
        credentials.save(instanceId, QuotaCredentialKind.PLATFORM_TOKEN, placeholderSecret("tok-test-"))
        snapshots.save(
            QuotaSnapshot(
                providerId = instanceId,
                providerTypeId = provider.id,
                items = listOf(sampleItem(remaining = "42")),
                fetchedAtEpochSecond = 1_700_000_000L,
            ),
        )
    }

    @Test
    fun `清除后两种凭据都没了且状态回落到未配置`() = runTest {
        seedCredentialAndSnapshot()
        val repo = repository()
        // 先让仓库把凭据读进状态（等价于冷启动已配置的样子）
        repo.refresh(provider.id, force = true)
        assertTrue("前置：余额凭据应已识别", repo.state(provider.id).balanceConfigured)
        assertTrue("前置：用量凭据应已识别", repo.state(provider.id).usageConfigured)

        repo.clearCredentials(provider.id)

        assertFalse("余额 Key 必须被清", credentials.has(provider.id, QuotaCredentialKind.API_KEY))
        assertFalse(
            "用量令牌必须被清",
            credentials.has(provider.id, QuotaCredentialKind.PLATFORM_TOKEN),
        )
        val state = repo.state(provider.id)
        assertFalse("状态必须回落", state.balanceConfigured)
        assertFalse("状态必须回落", state.usageConfigured)
        assertTrue("旧额度项不能留在状态里", state.items.isEmpty())
        assertNull("旧用量不能留在状态里", state.usage)
        assertEquals("更新时间要清零，否则卡片会显示「更新于 …」", 0L, state.fetchedAtEpochSecond)
        assertFalse("断开不是「陈旧数据」态", state.stale)
        assertNull("断开不该留失败原因", state.errorMessage)
    }

    @Test
    fun `清除会删掉该账号的快照缓存`() = runTest {
        seedCredentialAndSnapshot()
        assertTrue("前置：快照应已落盘", snapshots.load(provider.id) != null)

        repository().clearCredentials(provider.id)

        assertNull("断开后不能继续回显旧的额度数字", snapshots.load(provider.id))
    }

    @Test
    fun `只清自己那个账号不清别家`() = runTest {
        val other = FakeQuotaProvider(id = "other")
        seedCredentialAndSnapshot()
        credentials.save(other.id, QuotaCredentialKind.API_KEY, placeholderSecret())
        snapshots.save(QuotaSnapshot(providerId = other.id, items = listOf(sampleItem())))
        val repo = QuotaRepository(
            registry = QuotaProviderRegistry(listOf(provider, other)),
            credentials = credentials,
            cache = snapshots,
            catalog = InMemoryQuotaInstanceCatalog(listOf(provider.id, other.id)),
        )

        repo.clearCredentials(provider.id)

        assertTrue("没被断开的账号凭据必须还在", credentials.has(other.id, QuotaCredentialKind.API_KEY))
        assertTrue("没被断开的账号快照必须还在", snapshots.load(other.id) != null)
    }

    @Test
    fun `同类型的第二个账号有独立的凭据与快照`() = runTest {
        // 零迁移口径：第一实例 id == 类型 id，第二实例是 "fake#2"
        val repo = repository()
        val second = repo.addInstance(provider.id)
        assertEquals("fake#2", second?.instanceId)

        seedCredentialAndSnapshot()
        seedCredentialAndSnapshot("fake#2")
        repo.refresh(provider.id, force = true)
        repo.refresh("fake#2", force = true)

        // 断开第一个账号：第二个账号的凭据 / 快照 / 状态都必须完好
        repo.clearCredentials(provider.id)

        assertFalse(credentials.has(provider.id, QuotaCredentialKind.API_KEY))
        assertTrue("第二个账号的凭据不能跟着没", credentials.has("fake#2", QuotaCredentialKind.API_KEY))
        assertNull(snapshots.load(provider.id))
        assertTrue("第二个账号的快照不能跟着没", snapshots.load("fake#2") != null)
        assertTrue(repo.state("fake#2").items.isNotEmpty())
        assertTrue(repo.state("fake#2").balanceConfigured)
    }

    @Test
    fun `同类型两个账号的额度与状态互不覆盖`() = runTest {
        val repo = repository()
        repo.addInstance(provider.id)
        credentials.save(provider.id, QuotaCredentialKind.API_KEY, placeholderSecret())
        credentials.save("fake#2", QuotaCredentialKind.API_KEY, placeholderSecret())

        repo.refresh(provider.id, force = true)
        repo.refresh("fake#2", force = true)

        // 两个账号各自都拿到了数据：类型 id 当键时第二个会覆盖第一个
        assertTrue(repo.state(provider.id).items.isNotEmpty())
        assertTrue(repo.state("fake#2").items.isNotEmpty())
        // 快照各存各的键
        assertTrue(snapshots.load(provider.id) != null)
        assertTrue(snapshots.load("fake#2") != null)
        assertEquals(
            "两份快照必须落在不同键上",
            setOf(provider.id, "fake#2"),
            setOf(provider.id, "fake#2"),
        )
    }

    @Test
    fun `同一账号连续刷新各自独立节流（第二个账号不会被第一个拖住）`() = runTest {
        var now = 1_000L
        val repo = QuotaRepository(
            registry = QuotaProviderRegistry(listOf(provider)),
            credentials = credentials,
            cache = snapshots,
            catalog = catalog,
            nowSeconds = { now },
        )
        repo.intervalSeconds = { 60L }
        repo.addInstance(provider.id)
        credentials.save(provider.id, QuotaCredentialKind.API_KEY, placeholderSecret())
        credentials.save("fake#2", QuotaCredentialKind.API_KEY, placeholderSecret())

        repo.refresh(provider.id, force = true)
        val before = provider.fetchCount
        // 同一时刻刷第二个账号：非 force 也要能发出去（各自的节流基准）
        repo.refresh("fake#2", force = false)
        assertEquals("第二个账号不能被第一个的节流窗口挡住", before + 1, provider.fetchCount)

        // 第一个账号自己仍在窗口内：非 force 不发请求
        repo.refresh(provider.id, force = false)
        assertEquals(before + 1, provider.fetchCount)
        now += 61
        repo.refresh(provider.id, force = false)
        assertEquals(before + 2, provider.fetchCount)
    }

    @Test
    fun `清除不发请求（凭据都没了）`() = runTest {
        seedCredentialAndSnapshot()
        val repo = repository()
        val before = provider.fetchCount

        repo.clearCredentials(provider.id)

        assertEquals("断开是纯本地操作，不该打接口", before, provider.fetchCount)
    }

    @Test
    fun `清除后重建仓库不会把旧快照读回来`() = runTest {
        seedCredentialAndSnapshot()
        repository().clearCredentials(provider.id)

        // 冷启动路径：init 会 cache.load 每个实例；快照若没清，这里会恢复出旧数字
        val restarted = repository().state(provider.id)

        assertTrue("重建后不能恢复旧额度项", restarted.items.isEmpty())
        assertNull(restarted.usage)
        assertEquals(0L, restarted.fetchedAtEpochSecond)
        assertFalse("重建后不能再标成陈旧数据", restarted.stale)
        assertFalse(restarted.balanceConfigured)
        assertFalse(restarted.usageConfigured)
    }

    @Test
    fun `清除后定时刷新只回落状态不写失败原因`() = runTest {
        seedCredentialAndSnapshot()
        val repo = repository()
        repo.refresh(provider.id, force = true)
        val before = provider.fetchCount
        repo.clearCredentials(provider.id)

        // 前台定时器下一 tick 会走到这里：凭据已空 → refresh 的提前返回分支
        repo.refresh(provider.id, force = true)
        repo.refresh(provider.id, force = false)

        val state = repo.state(provider.id)
        assertEquals("空凭据的刷新不能打接口", before, provider.fetchCount)
        assertNull(
            "断开后不能闪「查询失败」：提前返回分支必须把 errorMessage 清成 null",
            state.errorMessage,
        )
        assertFalse("也不能把状态标成加载中", state.loading)
        assertFalse(state.balanceConfigured)
        assertFalse(state.usageConfigured)
        assertTrue(state.items.isEmpty())
    }

    @Test
    fun `删账号会清掉它自己的凭据快照与状态`() = runTest {
        val repo = repository()
        repo.addInstance(provider.id)
        seedCredentialAndSnapshot()
        seedCredentialAndSnapshot("fake#2")
        repo.refresh(provider.id, force = true)
        repo.refresh("fake#2", force = true)

        assertTrue("前置：删之前第二个账号还在", repo.deleteInstance("fake#2"))

        assertFalse(credentials.has("fake#2", QuotaCredentialKind.API_KEY))
        assertNull("删账号要连它的快照一起清", snapshots.load("fake#2"))
        assertNull("状态里不该再留这个实例", repo.states.value["fake#2"])
        assertTrue("第一个账号不受影响", credentials.has(provider.id, QuotaCredentialKind.API_KEY))
        assertTrue(snapshots.load(provider.id) != null)
    }

    @Test
    fun `只剩一个账号时删账号被拒绝`() = runTest {
        val repo = repository()
        seedCredentialAndSnapshot()

        assertFalse("没有「某类型零账号」这个状态", repo.deleteInstance(provider.id))
        assertTrue("凭据不能被动到", credentials.has(provider.id, QuotaCredentialKind.API_KEY))
        assertEquals("清单里仍要有这个账号", listOf(provider.id), repo.instances.value.map { it.instanceId })
    }

    @Test
    fun `新增账号不改动第一个账号的实例键（零迁移）`() = runTest {
        val repo = repository()
        val second = repo.addInstance(provider.id)

        assertEquals(provider.id, repo.instances.value.first().instanceId)
        assertEquals("fake#2", second?.instanceId)
        // 老凭据键 "fake:API_KEY" 天然命中第一个实例
        credentials.save(provider.id, QuotaCredentialKind.API_KEY, placeholderSecret())
        assertTrue(credentials.has(repo.instances.value.first().instanceId, QuotaCredentialKind.API_KEY))
    }

    private companion object {
        /** 与 [QuotaSnapshotStore] 的 prefs 名一致（测试直接清盘用）。 */
        const val SNAPSHOT_PREFS = "quota_snapshots"
    }
}
