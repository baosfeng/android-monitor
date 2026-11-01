package com.bsfeng.monitor.totp.ui

import android.app.Application
import com.bsfeng.monitor.totp.SoftwareAesGcmCipher
import com.bsfeng.monitor.totp.TotpAccountDao
import com.bsfeng.monitor.totp.TotpBackup
import com.bsfeng.monitor.totp.TotpClipboard
import com.bsfeng.monitor.totp.TotpDatabaseHelper
import com.bsfeng.monitor.totp.TotpEntity
import com.bsfeng.monitor.totp.TotpQrDecoder
import com.bsfeng.monitor.totp.TotpRepository
import com.bsfeng.monitor.totp.TotpSecretStore
import com.bsfeng.monitor.totp.TotpSettings
import com.bsfeng.monitor.totp.testAccount
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 装配层（TotpViewModel）的接线验证：读库 / 复制 / 手动新增 / 排序。
 *
 * 用真 Dao + 内存级软件加密替身（Robolectric 沙箱没有 AndroidKeyStore），
 * 每个用例新建一份 ViewModel，覆盖正常、边界与异常三条路径。
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class TotpViewModelTest {

    private lateinit var app: Application
    private lateinit var repository: TotpRepository
    private lateinit var viewModel: TotpViewModel
    private lateinit var settings: TotpSettings

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Default)
        app = RuntimeEnvironment.getApplication()
        val helper = TotpDatabaseHelper(app)
        repository = TotpRepository(
            dao = TotpAccountDao(helper),
            secrets = TotpSecretStore(app, SoftwareAesGcmCipher()),
        )
        settings = TotpSettings(app)
        settings.resetToDefaults()
        viewModel = TotpViewModel(
            application = app,
            repository = repository,
            settings = settings,
            clipboard = TotpClipboard(app),
            qrDecoder = TotpQrDecoder(),
            backup = TotpBackup(),
        )
        awaitIdle { !viewModel.loading.value }
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun awaitIdle(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
        throw AssertionError("等待超时：界面状态没有在 5 秒内就绪")
    }

    @Test
    fun `刷新读到账户并可复制验证码`() {
        viewModel.submitManual { }
        viewModel.setManualForm(
            TotpManualForm(issuer = "GitHub", accountName = "alice@acme.com", secretBase32 = "JBSWY3DPEHPK3PXP"),
        )
        viewModel.submitManual { }
        awaitIdle { viewModel.accounts.value.size == 1 }

        val account = viewModel.accounts.value.first()
        assertTrue("合法账户应能复制", viewModel.copyCode(account))
        assertTrue(viewModel.message.value!!.contains("30 秒后自动清空"))
    }

    @Test
    fun `关闭复制即焚后不再排自动清理`() {
        viewModel.setManualForm(
            TotpManualForm(issuer = "AWS", accountName = "root@acme", secretBase32 = "JBSWY3DPEHPK3PXP"),
        )
        viewModel.submitManual { }
        awaitIdle { viewModel.accounts.value.size == 1 }

        settings.clipboardAutoClearEnabled = false
        assertTrue(viewModel.copyCode(viewModel.accounts.value.first()))
        assertEquals("已复制", viewModel.message.value)
    }

    @Test
    fun `密钥不可读时只提示不崩溃`() {
        // 只插元数据不写密钥：模拟 Keystore 重置后的账户
        runBlocking {
            TotpAccountDao(TotpDatabaseHelper(app)).insert(
                TotpEntity.toRow(testAccount(issuer = "腾讯云", accountName = "ops@acme.cn"), 1, 1_700_000_000_000L),
            )
        }
        viewModel.refresh()
        awaitIdle { viewModel.accounts.value.size == 1 }

        val account = viewModel.accounts.value.first()
        assertEquals("", account.secretBase32)
        assertFalse(viewModel.copyCode(account))
        assertEquals("密钥不可读，无法生成验证码", viewModel.message.value)
    }

    @Test
    fun `手动新增校验不通过时不落库`() {
        viewModel.setManualForm(
            TotpManualForm(issuer = "GitHub", accountName = "", secretBase32 = "JBSWY3DPEHPK3PXP"),
        )
        viewModel.submitManual { }
        assertEquals("请填写账户", viewModel.manualError.value)
        assertTrue(viewModel.accounts.value.isEmpty())
    }

    @Test
    fun `上移改变顺序并写回`() {
        viewModel.setManualForm(
            TotpManualForm(issuer = "GitHub", accountName = "a@acme.com", secretBase32 = "JBSWY3DPEHPK3PXP"),
        )
        viewModel.submitManual { }
        awaitIdle { viewModel.accounts.value.size == 1 }
        viewModel.setManualForm(
            TotpManualForm(issuer = "阿里云", accountName = "b@acme.cn", secretBase32 = "JBSWY3DPEHPK3PXP"),
        )
        viewModel.submitManual { }
        awaitIdle { viewModel.accounts.value.size == 2 }

        val second = viewModel.accounts.value[1]
        viewModel.move(second.id, -1)
        assertEquals(second.id, viewModel.accounts.value.first().id)
        awaitIdle {
            runBlocking { repository.accounts().first().id } == second.id
        }
    }
}
