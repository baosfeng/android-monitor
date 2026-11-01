package com.bsfeng.monitor.totp

import android.graphics.Bitmap
import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.MultiFormatReader
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer

/**
 * 二维码解码 —— zxing 的唯一触点（全仓只此一处 import com.google.zxing）。
 *
 * 边界：本类只回答「这张图里有没有二维码、内容是什么」，找不到返回 null，不抛异常；
 * 解出来的字符串是不可信输入，调用方必须交给 domain 层 OtpauthUri.parse 校验，
 * 这里不做任何业务判断（不认 otpauth://、不校验 Base32）。
 */
class TotpQrDecoder {

    /** 从位图解码二维码文本；无二维码 / 图片无效返回 null。 */
    fun decode(bitmap: Bitmap): String? {
        val width = bitmap.width
        val height = bitmap.height
        if (width <= 0 || height <= 0) return null
        val pixels = IntArray(width * height)
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        return decodeLuminance(luminanceOf(pixels), width, height)
    }

    /**
     * 从相机预览帧解码（Y 平面亮度，宽度 × 高度）。
     *
     * [luminance] 每字节一个像素的亮度值，长度必须不小于 width × height。
     */
    fun decodeLuminance(luminance: ByteArray, width: Int, height: Int): String? {
        if (width <= 0 || height <= 0) return null
        if (luminance.size < width * height) return null
        val source = PlanarYUVLuminanceSource(
            luminance, width, height, 0, 0, width, height, false,
        )
        val binary = BinaryBitmap(HybridBinarizer(source))
        val reader = MultiFormatReader().apply {
            setHints(
                mapOf(
                    // 只认 QR：相册里混入条形码时不能当 otpauth 内容返回
                    DecodeHintType.POSSIBLE_FORMATS to listOf(BarcodeFormat.QR_CODE),
                    DecodeHintType.TRY_HARDER to true,
                    DecodeHintType.CHARACTER_SET to "UTF-8",
                ),
            )
        }
        return try {
            reader.decodeWithState(binary).text
        } catch (_: Exception) {
            null
        } finally {
            reader.reset()
        }
    }

    /** ARGB → 亮度（BT.601 定点近似）；相机帧路径不走这里，仅位图需要。 */
    private fun luminanceOf(pixels: IntArray): ByteArray = ByteArray(pixels.size) { i ->
        val p = pixels[i]
        val r = (p shr 16) and 0xFF
        val g = (p shr 8) and 0xFF
        val b = p and 0xFF
        ((r * 66 + g * 129 + b * 25 + 128) shr 8).coerceIn(0, 255).toByte()
    }
}
