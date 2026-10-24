package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：协议报文的编解码与字节流工具。
// 纯函数（无 socket、无实例状态）：文件清单解析、/offer 请求体、accepted 偏移读取、按偏移跳过。

import com.bsfeng.monitor.domain.transfer.TransferFile
import com.bsfeng.monitor.transfer.transport.ApiResponse
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.io.InputStream

/** 协议报文编解码（纯函数）。 */
internal object TransferWireCodec {

    fun parseFiles(array: JSONArray): List<TransferFile>? {
        val files = mutableListOf<TransferFile>()
        for (i in 0 until array.length()) {
            val json = array.optJSONObject(i) ?: return null
            val fileId = json.optString("fileId", "")
            val name = json.optString("name", "")
            val size = json.optLong("size", -1L)
            val sha = json.optString("sha256", "")
            val mime = json.optString("mime", "application/octet-stream")
            if (fileId.isBlank() || name.isBlank() || size < 0L) return null
            files += TransferFile(fileId, name, size, sha, mime)
        }
        return files
    }

    fun buildOfferJson(sources: List<SendSource>, deviceId: String, alias: String, pin: String? = null): String =
        JSONObject().apply {
            put("senderDeviceId", deviceId)
            put("senderAlias", alias)
            put(
                "files",
                JSONArray().apply {
                    sources.forEachIndexed { index, source ->
                        put(
                            JSONObject().apply {
                                put("fileId", "f${index + 1}")
                                put("name", source.name)
                                put("size", source.size)
                                put("sha256", source.sha256)
                                put("mime", source.mime)
                            },
                        )
                    }
                },
            )
            if (pin != null) put("pin", pin)
        }.toString()

    fun readAccepted(api: ApiResponse): Map<String, Long> {
        val array = api.data.optJSONArray("accepted") ?: return emptyMap()
        val result = mutableMapOf<String, Long>()
        for (i in 0 until array.length()) {
            val json = array.optJSONObject(i) ?: continue
            val fileId = json.optString("fileId", "")
            if (fileId.isNotBlank()) result[fileId] = json.optLong("offset", 0L)
        }
        return result
    }

    fun skipFully(input: InputStream, offset: Long) {
        var remaining = offset
        val scratch = ByteArray(8 * 1024)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }
            val read = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (read <= 0) throw IOException("源文件比预期短，无法从 offset=$offset 续传")
            remaining -= read
        }
    }
}
