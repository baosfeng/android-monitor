package com.bsfeng.monitor.backup

import android.content.Context
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * 备份包的 prefs 白名单（隐私红线）。
 *
 * 备份 zip 只做 sha256、不加密，落地还是公共 Downloads —— 一旦把凭据类 prefs 打进去，
 * 等于把 auth_token / 配对密钥 / Keystore 密文摊给同机任何文件管理器。
 * 这里逐条钉死「哪四类必须不在包里」，避免下次改 collectSources 时又变成无差别收集。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupSourceWhitelistTest {

    private lateinit var context: Context
    private lateinit var prefsDir: File

    /** 必须排除：内含凭据 / 密钥 / 可被同机解开的密文 */
    private val forbidden = listOf(
        "monitor_prefs", // auth_token（现为 Keystore 密文，仍属凭据）
        "lan_transfer", // pairing_keys（现为密文，拿到即可免 PIN 推文件）
        "totp_secrets", // TOTP 私钥密文
        "quota_credentials", // 额度凭据密文
    )

    /** 必须保留：纯偏好，换机后能省几步重设 */
    private val kept = listOf(
        "monitor_appearance",
        "feature_flags",
        "backup_prefs",
        "quota_settings",
    )

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefsDir = File(context.dataDir, BackupPaths.PREFS_DIR).apply { mkdirs() }
        (forbidden + kept).forEach { File(prefsDir, "$it.xml").writeText("<map />") }
        // 白名单外、也不在排除清单里的普通文件，同样不该进包
        File(prefsDir, "some_other.xml").writeText("<map />")
        File(prefsDir, "notes.txt").writeText("not xml")
    }

    private fun archivedPrefs(): List<String> =
        // 只关心 prefs 白名单：库快照那部分给一个空清单，避免这里去开真库
        BackupSourceCollector(context).collectSources(BackupSnapshot(emptyList(), emptyList()))
            .map { it.archivePath }
            .filter { it.startsWith("${BackupPaths.PREFS_DIR}/") }
            .map { it.substringAfterLast('/') }
            .sorted()

    @Test
    fun `凭据类 prefs 一律不进备份包`() {
        val archived = archivedPrefs()
        forbidden.forEach {
            assertFalse("$it 含凭据，绝不能进备份包", archived.contains("$it.xml"))
        }
    }

    @Test
    fun `纯偏好类 prefs 保留在备份包里`() {
        val archived = archivedPrefs()
        kept.forEach {
            assertTrue("$it 是纯偏好，应随备份还原", archived.contains("$it.xml"))
        }
    }

    @Test
    fun `白名单之外的文件不进包`() {
        val archived = archivedPrefs()
        assertFalse("未登记的白名单外 prefs 不进包", archived.contains("some_other.xml"))
        assertTrue("非 xml 本来就不过滤", archived.none { it.endsWith(".txt") })
    }
}
