package com.bsfeng.monitor.update

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * 更新包校验的两道闸（安全审计 S-04）。
 *
 * 钉住三件事：
 * 1. **fail-closed**：清单没给 sha256 / 给的位数不对时**直接拒绝**，不再静默放行
 *    （旧实现是 `isNotBlank() && !equals()` —— 空串等于「跳过校验」，一份被篡改的 version.json
 *    就能把任意 APK 送到安装器手里）；
 * 2. **签名指纹**：证书集合不一致 / 任一侧为空一律判不一致；
 * 3. **不联网也能验证**：空校验值在发请求之前就返回失败（否则单测会真的去拉 URL）。
 */
class ApkDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    // ────────────────────── sha256 校验值本身 ──────────────────────

    @Test
    fun `空校验值不合法`() {
        assertFalse(ApkDownloader.isValidSha256(""))
        assertFalse(ApkDownloader.isValidSha256("   "))
        assertFalse(ApkDownloader.isValidSha256(null))
    }

    @Test
    fun `位数不对的校验值不合法`() {
        assertFalse("太短", ApkDownloader.isValidSha256("abc123"))
        assertFalse("65 位", ApkDownloader.isValidSha256("0".repeat(65)))
        assertFalse("59 位", ApkDownloader.isValidSha256("0".repeat(59)))
    }

    @Test
    fun `非十六进制字符不合法`() {
        assertFalse(ApkDownloader.isValidSha256("g".repeat(64)))
        assertFalse(ApkDownloader.isValidSha256("0".repeat(63) + "-"))
    }

    @Test
    fun `64 位十六进制合法且大小写都接受`() {
        assertTrue(ApkDownloader.isValidSha256("0".repeat(64)))
        assertTrue(ApkDownloader.isValidSha256("ABCDEF0123456789abcdef0123456789abcdef0123456789abcdef0123456789"))
        assertTrue("首尾空白应被容忍", ApkDownloader.isValidSha256("  " + "a".repeat(64) + "\n"))
    }

    // ────────────────────── 下载入口 fail-closed ──────────────────────

    @Test
    fun `空校验值时下载直接失败且不产生文件`() {
        val dir = temp.newFolder("updates")
        // 给一个必然连不上的 URL：若实现放行了，这行会真的去连（并因 DNS 失败 fall through），
        // 我们靠「错误类型是 SecurityException 且消息提到校验值」来区分是闸门拒的。
        val result = ApkDownloader().download(
            sources = listOf("http://127.0.0.1:9/should-never-be-fetched.apk"),
            destDir = dir,
            expectedSha256 = "",
        )

        assertTrue("空 sha 必须 fail-closed", result.isFailure)
        val error = result.exceptionOrNull()
        assertTrue("应为 SecurityException，实际 ${error?.javaClass?.simpleName}", error is SecurityException)
        assertTrue("错误消息要说明缺校验值：" + error?.message, error?.message?.contains("校验值") == true)
        assertTrue("失败时不能留下半截文件", dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `位数不对的校验值同样拒绝`() {
        val dir = temp.newFolder("updates-short")
        val result = ApkDownloader().download(
            sources = listOf("http://127.0.0.1:9/x.apk"),
            destDir = dir,
            expectedSha256 = "abc123",
        )
        assertTrue(result.isFailure)
        assertTrue(result.exceptionOrNull() is SecurityException)
    }

    // ────────────────────── 签名指纹比对（纯函数）──────────────────────

    private val certA = byteArrayOf(0x01, 0x02, 0x03)
    private val certB = byteArrayOf(0x04, 0x05)

    @Test
    fun `证书完全一致时通过`() {
        assertTrue(ApkDownloader.signerMatches(listOf(certA), listOf(certA)))
        assertTrue(ApkDownloader.signerMatches(listOf(certA, certB), listOf(certB, certA)))
    }

    @Test
    fun `证书不一致时拒绝`() {
        assertFalse("换了一张证书", ApkDownloader.signerMatches(listOf(certB), listOf(certA)))
    }

    @Test
    fun `下载包多带一张未知证书时拒绝`() {
        // 相等口径（不是「包含」）：多出来的证书意味着签名者集合变了，不能放行
        assertFalse(ApkDownloader.signerMatches(listOf(certA, certB), listOf(certA)))
    }

    @Test
    fun `任一侧为空时拒绝`() {
        assertFalse("拿不到下载包签名", ApkDownloader.signerMatches(emptyList(), listOf(certA)))
        assertFalse("拿不到本机签名", ApkDownloader.signerMatches(listOf(certA), emptyList()))
        assertFalse(ApkDownloader.signerMatches(emptyList(), emptyList()))
    }

    @Test
    fun `同样的字节但不同顺序视为一致`() {
        assertTrue(ApkDownloader.signerMatches(listOf(certA, certB), listOf(certB, certA)))
    }
}
