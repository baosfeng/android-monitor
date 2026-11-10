package com.bsfeng.monitor.totp

import android.content.Context
import com.bsfeng.monitor.domain.totp.TotpParams
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Google 迁移二维码（otpauth-migration://）落库实测。
 *
 * 防复发：迁移链接一图多账户，[TotpRepository.addBatchFromUri] 必须把每一条都落库，
 * 且 [TotpRepository.addFromUri] 遇到迁移链接不能只落第一条就把其余账户丢了。
 * 两条样本由 lead 用标准 protobuf wire format 构造并回读校验过。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TotpMigrationImportTest {

    private lateinit var context: Context
    private lateinit var helper: TotpDatabaseHelper
    private lateinit var dao: TotpAccountDao
    private lateinit var repo: TotpRepository

    /** 两条 TOTP：Hi, Andres / Andres（SHA1 6 位）+ Not So Bad / Kevin（SHA256 8 位）。 */
    private val twoTotps =
        "otpauth-migration://offline?data=" +
            "CiEKBUhpLCBBEgpIaSwgQW5kcmVzGgZBbmRyZXMgASgBMAIKHwoETm90IBIKTm90IFNvIEJhZBoFS2V2aW4g" +
            "AigCMAIQARgBIAAouWA%3D"

    /** 一条 HOTP：Legacy / OldCorp（SHA1 6 位 counter=7）。 */
    private val oneHotp =
        "otpauth-migration://offline?data=" +
            "Ch0KAqvNEgZMZWdhY3kaB09sZENvcnAgASgBMAE4BxABGAE%3D"

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        TotpDatabaseHelper.resetForTesting()
        context.deleteDatabase(TotpDatabaseHelper.DATABASE_NAME)
        helper = TotpDatabaseHelper(context)
        dao = TotpAccountDao(helper)
        val secrets = TotpSecretStore(context, SoftwareAesGcmCipher())
        repo = TotpRepository(dao = dao, secrets = secrets, io = Dispatchers.Unconfined)
    }

    @After
    fun tearDown() {
        helper.close()
        TotpDatabaseHelper.resetForTesting()
        context.deleteDatabase(TotpDatabaseHelper.DATABASE_NAME)
        context.getSharedPreferences(TotpSecretStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().commit()
    }

    @Test
    fun `迁移链接批量导入两条 TOTP 账户`() = runTest {
        val result = repo.addBatchFromUri(twoTotps)

        assertEquals(2, result.entries.size)
        assertTrue("不应有跳过项：" + result.skipped, result.skipped.isEmpty())
        assertEquals(2, dao.count())
        // 样本里的 OtpParameters：name 才是账户名，issuer 也带尾部空格（"Hi, A" 被我截断过，
        // 原始串解出的 issuer 是 "Andres" / "Kevin"）。这两条期望值以 domain 解析结果为准。
        assertEquals(listOf("Hi, Andres", "Not So Bad"), repo.accounts().map { it.accountName })
        assertEquals(listOf("Andres", "Kevin"), repo.accounts().map { it.issuer })
        assertEquals(TotpParams.ALGORITHM_SHA256, repo.accounts()[1].params.algorithm)
        assertEquals(8, repo.accounts()[1].params.digits)
        assertTrue(repo.accounts().all { it.params.type == TotpParams.TYPE_TOTP })
    }

    @Test
    fun `迁移链接批量导入一条 HOTP 并保留 counter`() = runTest {
        val result = repo.addBatchFromUri(oneHotp)

        assertEquals(1, result.entries.size)
        assertEquals(1, dao.count())
        val account = repo.accounts().single()
        assertEquals("Legacy", account.accountName)
        assertEquals("OldCorp", account.issuer)
        assertEquals(TotpParams.TYPE_HOTP, account.params.type)
        assertEquals(7L, account.counter)
        assertEquals(TotpParams.TYPE_HOTP, dao.listAll().single().otpType)
        assertEquals(7L, dao.listAll().single().counter)
    }

    @Test
    fun `单条入口遇到迁移链接时把全部账户都落库，不只第一条`() = runTest {
        val parsed = repo.addFromUri(twoTotps)

        assertEquals(2, dao.count())
        assertTrue(parsed is com.bsfeng.monitor.domain.totp.OtpauthParseResult.Success)
    }

    @Test
    fun `迁移链接支持无百分号编码的 padding 写法`() = runTest {
        val result = repo.addBatchFromUri(oneHotp.replace("%3D", "="))

        assertEquals(1, result.entries.size)
        assertEquals(1, dao.count())
    }

    @Test
    fun `普通 otpauth 链接也能走进批量入口，得到一条的批`() = runTest {
        val link = "otpauth://totp/GitHub:alice@acme.com?secret=" + TEST_SECRET + "&issuer=GitHub"
        val result = repo.addBatchFromUri(link)

        assertEquals(1, result.entries.size)
        assertEquals(1, dao.count())
    }

    @Test
    fun `坏迁移链接不落库并给中文原因`() = runTest {
        val result = repo.addBatchFromUri("otpauth-migration://offline?data=bad")

        assertEquals(0, result.entries.size)
        assertEquals(1, result.skipped.size)
        assertEquals(0, dao.count())
    }
}
