package com.bsfeng.monitor.transfer.transport

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.bsfeng.monitor.domain.transfer.DOWNLOAD_RELATIVE_DIR
import java.io.File
import java.io.OutputStream

/**
 * 落到 `Downloads/局域网互传/`（协议 §6）。
 *
 * `MediaStore.Downloads` + `RELATIVE_PATH`（minSdk 29 原生支持）+ `IS_PENDING=1` 写入，
 * 写完再清 `IS_PENDING`，保证文件管理器里不会看到半成品；
 * `.part` 临时文件放在应用缓存目录（`cacheDir/transfer`），进程重启后仍可续传。
 */
class DownloadsSink(private val context: Context) {

    /** 相对路径（协议 §6 的默认目录）。 */
    val relativePath: String = Environment.DIRECTORY_DOWNLOADS + "/" + DOWNLOAD_RELATIVE_DIR

    /** `.part` 临时目录（应用私有，重启可续传）。 */
    fun partDir(): File = File(context.cacheDir, "transfer").apply {
        if (!exists()) mkdirs()
    }

    /** 在 Downloads 里插入一条 pending 记录，返回目标 uri；失败返回 null。 */
    fun createPending(displayName: String, mime: String): Uri? = runCatching {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, displayName)
            put(MediaStore.Downloads.MIME_TYPE, mime.ifBlank { "application/octet-stream" })
            put(MediaStore.Downloads.RELATIVE_PATH, relativePath)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    }.onFailure { Log.w(TAG, "插入 Downloads 记录失败: ${it.message}") }
        .getOrNull()

    /** 打开目标 uri 的写通道。 */
    fun openOutput(uri: Uri): OutputStream? = runCatching {
        context.contentResolver.openOutputStream(uri)
    }.onFailure { Log.w(TAG, "打开目标文件失败: ${it.message}") }
        .getOrNull()

    /** 清除 pending 标记（文件变为可见）。 */
    fun publish(uri: Uri): Boolean = runCatching {
        val values = ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }
        context.contentResolver.update(uri, values, null, null) > 0
    }.onFailure { Log.w(TAG, "发布目标文件失败: ${it.message}") }
        .getOrDefault(false)

    /**
     * 删除（失败 / 取消 / 用户删除记录时清理）。
     *
     * @return 是否真的删掉了目标；文件已不存在 / 无权限时返回 false，调用方按「只移除记录」处理。
     */
    fun delete(uri: Uri): Boolean = runCatching {
        context.contentResolver.delete(uri, null, null) > 0
    }.onFailure { Log.w(TAG, "删除目标文件失败: ${it.message}") }
        .getOrDefault(false)

    /** 接收目录占用统计。 */
    data class ReceivedStats(val count: Int, val totalBytes: Long) {
        /** 目录是否为空。 */
        val isEmpty: Boolean get() = count == 0
    }

    /** 统计 `Downloads/局域网互传/` 下的文件条数与总大小；查询失败按 0 处理。 */
    fun stats(): ReceivedStats {
        var count = 0
        var totalBytes = 0L
        runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads.SIZE),
                "${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(relativePath + "/"),
                null,
            )?.use { cursor ->
                val sizeIndex = cursor.getColumnIndex(MediaStore.Downloads.SIZE)
                while (cursor.moveToNext()) {
                    count++
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) totalBytes += cursor.getLong(sizeIndex)
                }
            }
        }.onFailure { Log.w(TAG, "统计接收目录失败: ${it.message}") }
        return ReceivedStats(count, totalBytes)
    }

    /**
     * 清空接收目录（删除目录下所有文件记录）。
     *
     * @return 实际删除条数；失败返回 0。
     */
    fun clearAll(): Int = runCatching {
        context.contentResolver.delete(
            MediaStore.Downloads.EXTERNAL_CONTENT_URI,
            "${MediaStore.Downloads.RELATIVE_PATH} = ?",
            arrayOf(relativePath + "/"),
        )
    }.onFailure { Log.w(TAG, "清空接收目录失败: ${it.message}") }
        .getOrDefault(0)

    /** 目标目录下已有的文件名集合（用于同名追加 ` (1)`）。 */
    fun existingNames(): Set<String> {
        val names = mutableSetOf<String>()
        runCatching {
            context.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Downloads.DISPLAY_NAME),
                "${MediaStore.Downloads.RELATIVE_PATH} = ?",
                arrayOf(relativePath + "/"),
                null,
            )?.use { cursor ->
                val index = cursor.getColumnIndex(MediaStore.Downloads.DISPLAY_NAME)
                if (index >= 0) {
                    while (cursor.moveToNext()) {
                        cursor.getString(index)?.let { names += it }
                    }
                }
            }
        }.onFailure { Log.w(TAG, "查询已有文件名失败: ${it.message}") }
        return names
    }

    private companion object {
        const val TAG = "TransferDownloads"
    }
}
