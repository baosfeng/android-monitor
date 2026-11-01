package com.bsfeng.monitor.totp

import android.content.Context
import com.bsfeng.monitor.domain.totp.OtpauthParseResult
import com.bsfeng.monitor.domain.totp.OtpauthUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [TotpRepository] 单元测试（Robolectric + 真实 SQLite + 软件 AES-GCM）。
 *
 * 覆盖：增删改查与密钥联动、排序、非法参数拒绝且不落库、密钥不可读时不清数据、
 * 批量导入逐条跳过、otpauth:// 入口走 domain 校验。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TotpRepositoryTest {

    private lateinit var context: Context
    private lateinit var helper: TotpDatabaseHelper
    private lateinit var dao: TotpAccountDao
    private lateinit var secrets: TotpSecretStore
    private lateinit var repo: TotpRepository

    private val fixedNow = 1_700_000_000_000L

    private val prefs
        get() = context.getSharedPreferences(TotpSecretStore.PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        TotpDatabaseHelper.resetForTesting()
        context.deleteDatabase(TotpDatabaseHelper.DATABASE_NAME)
        prefs.edit().clear().commit()
        helper = TotpDatabaseHelper(context)
        dao = TotpAccountDao(helper)
        secrets = TotpSecretStore(context, SoftwareAesGcmCipher())
        repo = TotpRepository(
            dao = dao,
            secrets = secrets,
            clock = { fixedNow },
            io = Dispatchers.Unconfined,
        )
    }

    @After
    fun tearDown() {
        helper.close()
        TotpDatabaseHelper.resetForTesting()
        context.deleteDatabase(TotpDatabaseHelper.DATABASE_NAME)
        prefs.edit().clear().commit()
    }

    @Test
    fun `新增后可读回账户与已解密密钥`() = runTest {
        val id = repo.add(
            testAccount(
                issuer = "腾讯云",
                accountName = "ops@acme.cn",
                algorithm = "SHA256",
                digits = 8,
                periodSec = 60,
            ),
        )
        assertTrue(id > 0)

        val accounts = repo.accounts()
        assertEquals(1, accounts.size)
        assertEquals(id, accounts[0].id)
        assertEquals("腾讯云", accounts[0].issuer)
        assertEquals("ops@acme.cn", accounts[0].accountName)
        assertEquals(TEST_SECRET, accounts[0].secretBase32)
        assertEquals("SHA256", accounts[0].params.algorithm)
        assertEquals(8, accounts[0].params.digits)
        assertEquals(60, accounts[0].params.periodSec)
        assertEquals(fixedNow, dao.getById(id)!!.createdAt)
    }

    @Test
    fun `新增默认追加到末尾`() = runTest {
        repo.add(testAccount(accountName = "a"))
        repo.add(testAccount(accountName = "b"))

        assertEquals(listOf("a", "b"), repo.accounts().map { it.accountName })
        assertEquals(listOf(0, 1), repo.accounts().map { it.sortOrder })
    }

    @Test
    fun `非法密钥与非法参数拒绝且不落库`() = runTest {
        assertRejected { repo.add(testAccount(secret = "not base32!")) }
        assertRejected { repo.add(testAccount(digits = 7)) }
        assertRejected { repo.add(testAccount(periodSec = 45)) }
        assertRejected { repo.add(testAccount(accountName = "   ")) }

        assertEquals(0, dao.count())
        assertTrue(repo.accounts().isEmpty())
    }

    @Test
    fun `更新保留创建时间并重写密钥`() = runTest {
        val id = repo.add(testAccount(issuer = "旧平台"))
        val created = dao.getById(id)!!.createdAt

        repo.update(
            testAccount(issuer = "新平台", accountName = "new@acme.com", secret = "GEZDGNBVGY3TQOJQ")
                .copy(id = id, sortOrder = 0),
        )

        val accounts = repo.accounts()
        assertEquals("新平台", accounts[0].issuer)
        assertEquals("new@acme.com", accounts[0].accountName)
        assertEquals("GEZDGNBVGY3TQOJQ", accounts[0].secretBase32)
        assertEquals(created, dao.getById(id)!!.createdAt)
    }

    @Test
    fun `删除账户同时删除密钥`() = runTest {
        val id = repo.add(testAccount())

        repo.delete(id)

        assertTrue(repo.accounts().isEmpty())
        assertFalse(secrets.has(id))
    }

    @Test
    fun `重排后列表顺序跟随给定 id`() = runTest {
        val a = repo.add(testAccount(accountName = "a"))
        val b = repo.add(testAccount(accountName = "b"))
        val c = repo.add(testAccount(accountName = "c"))

        repo.reorder(listOf(c, a, b))

        assertEquals(listOf("c", "a", "b"), repo.accounts().map { it.accountName })
    }

    @Test
    fun `密钥不可读时账户仍在列表且数据不删`() = runTest {
        val id = repo.add(testAccount())
        prefs.edit().putString("account:" + id, "AAAA").commit()

        val accounts = repo.accounts()
        assertEquals(1, accounts.size)
        assertEquals("", accounts[0].secretBase32)
        assertEquals(listOf(id), repo.unreadableAccountIds())
        assertTrue("解密失败不能删数据", prefs.contains("account:" + id))
    }

    @Test
    fun `批量导入逐条校验并给出跳过原因`() = runTest {
        val result = repo.importEntries(
            listOf(
                TotpBackupEntry("GitHub", "alice@acme.com", TEST_SECRET),
                TotpBackupEntry("坏参数", "bad@acme.com", TEST_SECRET, digits = 7),
            ),
        )

        assertEquals(1, result.entries.size)
        assertEquals(1, result.skipped.size)
        assertTrue(result.skipped[0].contains("bad@acme.com"))
        assertEquals(1, dao.count())
    }

    @Test
    fun `otpauth 入口先走 domain 校验再落库`() = runTest {
        val added = repo.addFromUri(
            OtpauthUri.build(testAccount(issuer = "GitHub", accountName = "alice@acme.com")),
        )
        assertTrue(added is OtpauthParseResult.Success)
        assertEquals(1, dao.count())

        val rejected = repo.addFromUri("https://example.com/steal")
        assertTrue(rejected is OtpauthParseResult.Failure)
        assertEquals(OtpauthUri.REASON_BAD_SCHEME, (rejected as OtpauthParseResult.Failure).reason)
        assertEquals(1, dao.count())
    }

    @Test
    fun `备份条目只含密钥可读的账户`() = runTest {
        val readable = repo.add(testAccount(accountName = "ok"))
        val unreadable = repo.add(testAccount(accountName = "broken"))
        prefs.edit().putString("account:" + unreadable, "AAAA").commit()

        val entries = repo.backupEntries()

        assertEquals(1, entries.size)
        assertEquals("ok", entries[0].account)
        assertEquals(TEST_SECRET, entries[0].secret)
        assertTrue(readable > 0)
    }

    private fun assertRejected(block: suspend () -> Unit) = runTest {
        try {
            block()
            fail("应当抛 IllegalArgumentException")
        } catch (_: IllegalArgumentException) {
            // 预期
        }
    }
}
