package com.bsfeng.monitor.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateCheckerTest {

    private val manifestJson = """
        {
          "versionCode": 2,
          "versionName": "1.1.0",
          "apkUrl": "https://github.com/baosfeng/android-monitor/releases/download/v1.1.0/app-release.apk",
          "apkSha256": "abc123",
          "changelog": "- 修复问题",
          "publishedAt": "2026-10-19T00:00:00Z"
        }
    """.trimIndent()

    @Test
    fun `任意源返回新版本即 Available`() {
        val checker = UpdateChecker(currentVersionCode = 1, currentVersionName = "1.0.0") { url ->
            if (url.contains("jsdelivr")) manifestJson else null
        }
        val result = checker.check()
        assertTrue(result is UpdateCheckResult.Available)
        assertEquals("1.1.0", (result as UpdateCheckResult.Available).manifest.versionName)
    }

    @Test
    fun `全部源失败返回 Failed`() {
        val checker = UpdateChecker(currentVersionCode = 1, currentVersionName = "1.0.0") { null }
        assertTrue(checker.check() is UpdateCheckResult.Failed)
    }

    @Test
    fun `版本不高于当前返回 UpToDate`() {
        val checker = UpdateChecker(currentVersionCode = 2, currentVersionName = "1.1.0") { manifestJson }
        val result = checker.check()
        assertTrue(result is UpdateCheckResult.UpToDate)
        assertEquals("1.1.0", (result as UpdateCheckResult.UpToDate).currentVersionName)
    }

    @Test
    fun `下载源包含镜像前缀并以 GitHub 直连兜底`() {
        val checker = UpdateChecker(currentVersionCode = 1, currentVersionName = "1.0.0") { null }
        val manifest = requireNotNull(UpdateManifest.parse(manifestJson))
        val sources = checker.downloadSources(manifest)
        assertTrue(sources.any { it.startsWith("https://ghfast.top/") })
        assertEquals(manifest.apkUrl, sources.last())
    }
}
