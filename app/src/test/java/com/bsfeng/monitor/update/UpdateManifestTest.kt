package com.bsfeng.monitor.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class UpdateManifestTest {

    private val validJson = """
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
    fun `合法清单解析出全部字段`() {
        val manifest = UpdateManifest.parse(validJson)
        assertNotNull(manifest)
        assertEquals(2, manifest!!.versionCode)
        assertEquals("1.1.0", manifest.versionName)
        assertEquals("abc123", manifest.apkSha256)
        assertEquals("- 修复问题", manifest.changelog)
    }

    @Test
    fun `缺少 apkUrl 时返回 null`() {
        val json = """{"versionCode":2,"versionName":"1.1.0"}"""
        assertNull(UpdateManifest.parse(json))
    }

    @Test
    fun `versionCode 非正数时返回 null`() {
        val json = """{"versionCode":0,"versionName":"1.1.0","apkUrl":"https://example.com/a.apk"}"""
        assertNull(UpdateManifest.parse(json))
    }

    @Test
    fun `非法 JSON 返回 null`() {
        assertNull(UpdateManifest.parse("not json"))
    }
}
