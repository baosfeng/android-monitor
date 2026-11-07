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
 * 断开 provider 的数据层行为（Robolectric + 真 SharedPreferences + 软件 AES-GCM 替身）。
 *
 * 盯住四件事，任何一件漏了用户都会看到「断开没生效」：
 * 1. 两种凭据都被清（余额 Key 与用量令牌）；
 * 2. 状态回落到 balanceConfigured / usageConfigured = false 且不再带旧数字；
 * 3. **快照缓存按 provider 清掉**：重建仓库不会再把上一次的额度读回来（这条是主缺口）；
 * 4. 清除不发请求，也不动用户的刷新间隔偏好。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class QuotaClearCredentialsTest {

    private lateinit var context: Context
    private lateinit var credentials: QuotaCredentialStore
    private lateinit var snapshots: QuotaSnapshotStore
    private lateinit var provider: FakeQuotaProvider

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
    }

    @After
    fun tearDown() {
        credentialPrefs.edit().clear().commit()
        context.getSharedPreferences(SNAPSHOT_PREFS, Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun repository() = QuotaRepository(
        registry = QuotaProviderRegistry(listOf(provider)),
        credentials = credentials,
        cache = snapshots,
    )

    private fun seedCredentialAndSnapshot() {
        credentials.save(provider.id, QuotaCredentialKind.API_KEY, placeholderSecret())
        credentials.save(provider.id, QuotaCredentialKind.PLATFORM_TOKEN, placeholderSecret("tok-test-"))
        snapshots.save(
            QuotaSnapshot(
                providerId = provider.id,
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
    fun `清除会删掉该家的快照缓存`() = runTest {
        seedCredentialAndSnapshot()
        assertTrue("前置：快照应已落盘", snapshots.load(provider.id) != null)

        repository().clearCredentials(provider.id)

        assertNull("断开后不能继续回显旧的额度数字", snapshots.load(provider.id))
    }

    @Test
    fun `只清自己那家不清别家`() = runTest {
        val other = FakeQuotaProvider(id = "other")
        seedCredentialAndSnapshot()
        credentials.save(other.id, QuotaCredentialKind.API_KEY, placeholderSecret())
        snapshots.save(QuotaSnapshot(providerId = other.id, items = listOf(sampleItem())))
        val repo = QuotaRepository(
            registry = QuotaProviderRegistry(listOf(provider, other)),
            credentials = credentials,
            cache = snapshots,
        )

        repo.clearCredentials(provider.id)

        assertTrue("没被断开的那家凭据必须还在", credentials.has(other.id, QuotaCredentialKind.API_KEY))
        assertTrue("没被断开的那家快照必须还在", snapshots.load(other.id) != null)
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

        // 冷启动路径：init 会 cache.load 每个 provider；快照若没清，这里会恢复出旧数字
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

    private companion object {
        /** 与 [QuotaSnapshotStore] 的 prefs 名一致（测试直接清盘用）。 */
        const val SNAPSHOT_PREFS = "quota_snapshots"
    }
}
