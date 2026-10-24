package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：把 SAF uri 读成可发送的 SendSource。
// 只做「名字 / 大小 / MIME / sha256」的探测，不发网络、不动任务列表。

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.bsfeng.monitor.transfer.transport.StreamCopier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 发送源的探测（IO 线程内调用）。 */
internal object TransferSourceReader {

    private const val TAG = "TransferEngine"

    /** 读不到大小或算不出 sha256 时返回 null（该文件跳过，不阻塞整批）。 */
    suspend fun read(context: Context, uri: Uri): SendSource? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        var name = ""
        var size = -1L
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: ""
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }.onFailure { Log.w(TAG, "查询文件信息失败: ${it.message}") }
        if (size < 0L) {
            runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
            }
        }
        if (size < 0L) return@withContext null
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val sha = runCatching {
            resolver.openInputStream(uri)?.use { input -> StreamCopier.sha256Of(input) }
        }.getOrNull() ?: return@withContext null
        SendSource(
            uri = uri,
            name = name.ifBlank { uri.lastPathSegment ?: "未命名文件" },
            size = size,
            mime = mime,
            sha256 = sha,
        )
    }
}
