package com.bsfeng.monitor.transfer.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.domain.transfer.DeviceTypes
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferFile
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask

/**
 * 任务历史持久化（`transfer_tasks` 表，`DatabaseHelper` v4 新增）。
 *
 * - 只保留最近 [MAX_PERSISTED_TASKS] 条，避免无限增长（每次写入后自动裁剪）；
 * - 由 [com.bsfeng.monitor.transfer.service.TransferEngine] 在任务创建 / 终态 / 删除 / 撤销时调用；
 * - 读写都在 IO 线程（调用方负责调度），本类不做线程切换。
 */
class TransferTaskStore(private val helper: DatabaseHelper) {

    /** 一行历史（含落盘 Uri）。 */
    data class Entry(
        val task: TransferTask,
        val fileUri: String?,
    )

    /** 读取最近 [limit] 条（新任务在前）。 */
    fun loadRecent(limit: Int = MAX_PERSISTED_TASKS): List<Entry> {
        val entries = mutableListOf<Entry>()
        helper.readableDatabase.query(
            TABLE,
            COLUMNS,
            null,
            null,
            null,
            null,
            "$COL_CREATED_AT DESC, $COL_UPDATED_AT DESC",
            limit.toString(),
        ).use { cursor ->
            while (cursor.moveToNext()) {
                entries += cursor.toEntry()
            }
        }
        return entries
    }

    /** 写入 / 覆盖一条任务（按 `session_id + file_id` 主键）。 */
    fun upsert(entry: Entry) {
        val task = entry.task
        val values = ContentValues().apply {
            put(COL_SESSION_ID, task.sessionId)
            put(COL_FILE_ID, task.file.fileId)
            put(COL_DIRECTION, task.direction.name)
            put(COL_PEER_DEVICE_ID, task.peer.deviceId)
            put(COL_PEER_ALIAS, task.peer.alias)
            put(COL_FILE_NAME, task.file.name)
            put(COL_FILE_SIZE, task.file.size)
            put(COL_SHA256, task.file.sha256)
            put(COL_MIME, task.file.mime)
            put(COL_STATUS, task.status.name)
            put(COL_RECEIVED_BYTES, task.receivedBytes)
            entry.fileUri?.let { put(COL_URI, it) } ?: putNull(COL_URI)
            put(COL_CREATED_AT, if (task.createdAtMs > 0L) task.createdAtMs else System.currentTimeMillis())
            put(COL_UPDATED_AT, System.currentTimeMillis())
        }
        helper.writableDatabase.insertWithOnConflict(
            TABLE,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        )
        trimTo(MAX_PERSISTED_TASKS)
    }

    /** 删除一条任务记录。 */
    fun delete(sessionId: String, fileId: String) {
        helper.writableDatabase.delete(
            TABLE,
            "$COL_SESSION_ID = ? AND $COL_FILE_ID = ?",
            arrayOf(sessionId, fileId),
        )
    }

    /** 删除所有「接收」方向的记录（清理接收目录时用）。 */
    fun deleteReceived() {
        helper.writableDatabase.delete(TABLE, "$COL_DIRECTION = ?", arrayOf(TransferDirection.RECV.name))
    }

    /** 只保留最近 [limit] 条。 */
    fun trimTo(limit: Int = MAX_PERSISTED_TASKS) {
        val keep = limit.coerceAtLeast(0)
        helper.writableDatabase.execSQL(
            "DELETE FROM $TABLE WHERE rowid NOT IN " +
                "(SELECT rowid FROM $TABLE ORDER BY $COL_CREATED_AT DESC, $COL_UPDATED_AT DESC LIMIT $keep)",
        )
    }

    /** 清空历史。 */
    fun clear() {
        helper.writableDatabase.delete(TABLE, null, null)
    }

    /** 当前记录数（测试 / 设置页用）。 */
    fun count(): Int = helper.readableDatabase.rawQuery("SELECT COUNT(*) FROM $TABLE", null).use { cursor ->
        if (cursor.moveToFirst()) cursor.getInt(0) else 0
    }

    private fun android.database.Cursor.toEntry(): Entry {
        val direction = runCatching {
            TransferDirection.valueOf(getString(getColumnIndexOrThrow(COL_DIRECTION)))
        }.getOrDefault(TransferDirection.RECV)
        val status = runCatching {
            TransferStatus.valueOf(getString(getColumnIndexOrThrow(COL_STATUS)))
        }.getOrDefault(TransferStatus.FAILED)
        val file = TransferFile(
            fileId = getString(getColumnIndexOrThrow(COL_FILE_ID)),
            name = getString(getColumnIndexOrThrow(COL_FILE_NAME)),
            size = getLong(getColumnIndexOrThrow(COL_FILE_SIZE)),
            sha256 = getString(getColumnIndexOrThrow(COL_SHA256)) ?: "",
            mime = getString(getColumnIndexOrThrow(COL_MIME)) ?: "",
        )
        val peer = PeerDevice(
            deviceId = getString(getColumnIndexOrThrow(COL_PEER_DEVICE_ID)) ?: "",
            alias = getString(getColumnIndexOrThrow(COL_PEER_ALIAS)) ?: "",
            deviceType = DeviceTypes.UNKNOWN,
            hostAddress = "",
            port = 0,
        )
        val uriIndex = getColumnIndexOrThrow(COL_URI)
        val task = TransferTask(
            sessionId = getString(getColumnIndexOrThrow(COL_SESSION_ID)),
            peer = peer,
            direction = direction,
            file = file,
            status = status,
            receivedBytes = getLong(getColumnIndexOrThrow(COL_RECEIVED_BYTES)),
            updatedAtMs = getLong(getColumnIndexOrThrow(COL_UPDATED_AT)),
            createdAtMs = getLong(getColumnIndexOrThrow(COL_CREATED_AT)),
        )
        return Entry(task = task, fileUri = if (isNull(uriIndex)) null else getString(uriIndex))
    }

    companion object {
        /** 最多保留多少条历史（避免无限增长）。 */
        const val MAX_PERSISTED_TASKS: Int = 100

        /** 表名（与 [DatabaseHelper.TABLE_TRANSFER_TASKS] 一致）。 */
        const val TABLE: String = DatabaseHelper.TABLE_TRANSFER_TASKS

        private const val COL_SESSION_ID = "session_id"
        private const val COL_FILE_ID = "file_id"
        private const val COL_DIRECTION = "direction"
        private const val COL_PEER_DEVICE_ID = "peer_device_id"
        private const val COL_PEER_ALIAS = "peer_alias"
        private const val COL_FILE_NAME = "file_name"
        private const val COL_FILE_SIZE = "file_size"
        private const val COL_SHA256 = "sha256"
        private const val COL_MIME = "mime"
        private const val COL_STATUS = "status"
        private const val COL_RECEIVED_BYTES = "received_bytes"
        private const val COL_URI = "uri"
        private const val COL_CREATED_AT = "created_at"
        private const val COL_UPDATED_AT = "updated_at"

        private val COLUMNS = arrayOf(
            COL_SESSION_ID, COL_FILE_ID, COL_DIRECTION, COL_PEER_DEVICE_ID, COL_PEER_ALIAS,
            COL_FILE_NAME, COL_FILE_SIZE, COL_SHA256, COL_MIME, COL_STATUS, COL_RECEIVED_BYTES,
            COL_URI, COL_CREATED_AT, COL_UPDATED_AT,
        )
    }
}
