package com.bsfeng.monitor.backup

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * 清单序列化 / 反序列化与非法输入口径。
 *
 * 关键口径：非法清单返回 null 而不是抛异常（调用方按「清单不可信」中止还原）。
 * Robolectric 提供 org.json 的真实实现（纯 JVM 下 org.json 是空壳）。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupManifestTest {

    private val sha = "a".repeat(64)

    @Test
    fun `toJson 与 parse 往返保真`() {
        val manifest = BackupManifest(
            schemaVersion = BackupManifest.SCHEMA_VERSION,
            appVersionName = "1.2.3",
            createdAt = 1_700_000_000_000L,
            files = listOf(
                BackupFileEntry("databases/finance.db", 4096L, sha),
                BackupFileEntry("shared_prefs/backup_prefs.xml", 128L, "b".repeat(64)),
            ),
            tables = listOf(BackupTableCount("finance_transactions", 42L)),
        )
        val parsed = BackupManifest.parse(manifest.toJson().toString())
        assertEquals(manifest, parsed)
    }

    @Test
    fun `非 JSON 文本返回 null`() {
        assertNull(BackupManifest.parse("not a json"))
        assertNull(BackupManifest.parse(""))
        assertNull(BackupManifest.parse(null))
    }

    @Test
    fun `缺少必备字段返回 null`() {
        val noVersion = JSONObject().apply {
            put("schemaVersion", 1)
            put("createdAt", 1L)
            put("files", org.json.JSONArray())
        }
        assertNull(BackupManifest.fromJson(noVersion))

        val noSchema = JSONObject().apply {
            put("appVersionName", "1.0")
            put("createdAt", 1L)
            put("files", org.json.JSONArray())
        }
        assertNull(BackupManifest.fromJson(noSchema))

        val noFiles = JSONObject().apply {
            put("schemaVersion", 1)
            put("appVersionName", "1.0")
            put("createdAt", 1L)
        }
        assertNull(BackupManifest.fromJson(noFiles))
    }

    @Test
    fun `文件条目 sha256 长度不符返回 null`() {
        val raw = JSONObject().apply {
            put("schemaVersion", 1)
            put("appVersionName", "1.0")
            put("createdAt", 1L)
            put(
                "files",
                org.json.JSONArray().put(
                    JSONObject().apply {
                        put("path", "databases/finance.db")
                        put("size", 1L)
                        put("sha256", "abc")
                    }
                ),
            )
        }
        assertNull(BackupManifest.fromJson(raw))
    }

    @Test
    fun `路径安全判定拦截绝对路径与上跳`() {
        assertTrue(BackupPaths.isSafe("databases/finance.db"))
        assertTrue(BackupPaths.isSafe("shared_prefs/backup_prefs.xml"))
        assertTrue(!BackupPaths.isSafe("../evil.txt"))
        assertTrue(!BackupPaths.isSafe("/data/data/x/finance.db"))
        assertTrue(!BackupPaths.isSafe("databases/../../evil"))
        assertTrue(!BackupPaths.isSafe(""))
        assertTrue(!BackupPaths.isSafe("databases//finance.db"))
    }
}
