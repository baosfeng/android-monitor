package com.bsfeng.monitor.totp.ui

// 扫码链路里的纯逻辑：取帧节流、YUV 取 Y 平面、解码结果去重。
// 之所以拆出来：相机回调线程里做不了断言，这三件事是唯一能被单测钉住的部分。

import java.nio.ByteBuffer

/**
 * 取帧节流：同一时刻只放一帧进解码。
 *
 * 相机 30fps，zxing 单帧解码几十毫秒；不节流会让解码线程排队越积越多，
 * 还会因为同一码被连解多次而重复入库。[DEFAULT_INTERVAL_MS] = 300ms 是
 * 可读性与响应速度的折中（3 帧/秒足够对准二维码）。
 *
 * 时钟回拨（now 比上次还小）同样按"还没到点"处理：宁可不解码，也不要解出重复结果。
 */
class TotpFrameGate(private val intervalMs: Long = DEFAULT_INTERVAL_MS) {

    private var lastAcceptedMs: Long? = null

    /** 是否允许这一帧进入解码；放行时同时记下时刻。 */
    fun tryAcquire(nowMillis: Long): Boolean {
        val last = lastAcceptedMs
        if (last != null && nowMillis - last < intervalMs) return false
        lastAcceptedMs = nowMillis
        return true
    }

    /** 重新开始计时（页面重新可见 / 相机重开时调用）。 */
    fun reset() {
        lastAcceptedMs = null
    }

    companion object {
        /** 默认节流间隔（毫秒）。 */
        const val DEFAULT_INTERVAL_MS = 300L
    }
}

/**
 * 解码结果去重：同一段文本只上抛一次。
 *
 * 相机连续帧会反复解出同一个二维码，不去重就会重复落库 / 重复弹提示。
 * 换一个新码（文本不同）立即放行。
 */
class TotpResultGate {

    private var lastText: String? = null

    /** null / 空白直接拒绝；与上次相同也拒绝。 */
    fun accept(text: String?): Boolean {
        val value = text?.trim()
        if (value.isNullOrEmpty()) return false
        if (value == lastText) return false
        lastText = value
        return true
    }

    fun reset() {
        lastText = null
    }
}

/**
 * 取 YUV_420_888 的 Y 平面（灰度）成连续字节。
 *
 * 相机给的 Y 平面按 rowStride 对齐（每行尾部可能有填充），直接 buffer→ByteArray 会把
 * 填充字节当成像素，图像被斜切、zxing 解不出来。这里逐行按 rowStride 取、按 pixelStride 跳。
 *
 * @param buffer 平面缓冲；按绝对下标 0 起读，要求 remaining() 足够
 * @return 长度 width*height 的灰度数据；尺寸或缓冲不合法返回 null（不让解码线程崩）
 */
fun copyLuminance(
    buffer: ByteBuffer,
    rowStride: Int,
    pixelStride: Int,
    width: Int,
    height: Int,
): ByteArray? {
    if (width <= 0 || height <= 0 || rowStride <= 0 || pixelStride <= 0) return null
    val required = (height - 1).toLong() * rowStride + (width - 1).toLong() * pixelStride + 1
    if (buffer.remaining() < required || required > Int.MAX_VALUE) return null
    val out = ByteArray(width * height)
    for (row in 0 until height) {
        val base = row * rowStride
        for (col in 0 until width) {
            out[row * width + col] = buffer.get(base + col * pixelStride)
        }
    }
    return out
}
