package com.bsfeng.monitor.totp

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import com.google.zxing.qrcode.QRCodeWriter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [TotpQrDecoder] 单元测试（Robolectric）。
 *
 * 覆盖：真二维码往返、亮度帧解码、空白图返回 null、条形码不冒充二维码、尺寸非法返回 null。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TotpQrDecoderTest {

    private val decoder = TotpQrDecoder()
    private val content = "otpauth://totp/GitHub:alice?secret=JBSWY3DPEHPK3PXP&issuer=GitHub"

    private fun qrBitmap(text: String, size: Int = 400): Bitmap {
        val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        for (x in 0 until size) {
            for (y in 0 until size) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }
        return bitmap
    }

    @Test
    fun `二维码位图解码出原文`() {
        assertEquals(content, decoder.decode(qrBitmap(content)))
    }

    @Test
    fun `亮度帧解码出原文`() {
        val size = 400
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size)
        val luminance = ByteArray(size * size) { i ->
            val x = i % size
            val y = i / size
            if (matrix[x, y]) 0 else 255.toByte()
        }

        assertEquals(content, decoder.decodeLuminance(luminance, size, size))
    }

    @Test
    fun `空白图与非法尺寸返回 null`() {
        val blank = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)

        assertNull(decoder.decode(blank))
        assertNull(decoder.decodeLuminance(ByteArray(10), 200, 200))
        assertNull(decoder.decodeLuminance(ByteArray(0), 0, 0))
    }

    @Test
    fun `条形码不会被当成二维码返回`() {
        val matrix = MultiFormatWriter().encode("123456789012", BarcodeFormat.CODE_128, 400, 200)
        val bitmap = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888)
        for (x in 0 until 400) {
            for (y in 0 until 200) {
                bitmap.setPixel(x, y, if (matrix[x, y]) Color.BLACK else Color.WHITE)
            }
        }

        assertNull(decoder.decode(bitmap))
    }
}
