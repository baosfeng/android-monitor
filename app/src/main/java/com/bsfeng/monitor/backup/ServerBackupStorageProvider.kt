package com.bsfeng.monitor.backup

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest

class ServerBackupStorageProvider(
    private val context: Context? = null,
) : BackupStorageProvider {

    private val cacheDir: File =
        (context?.applicationContext?.cacheDir ?: File(System.getProperty("java.io.tmpdir") ?: "."))
            .resolve("server-backup-cache").apply { mkdirs() }

    override fun list(): List<BackupFileInfo> {
        return try {
            val resp = ApiServiceBackup.listBackups()
            BackupServerRecordMapper.toBackupFileInfo(resp)
        } catch (_: Exception) {
            emptyList()
        }
    }

    override fun put(name: String, source: File): Boolean {
        return try {
            if (!source.isFile) return false
            ApiServiceBackup.uploadBackup(name, source)
        } catch (_: Exception) {
            false
        }
    }

    override fun get(name: String): File? {
        return try {
            val cached = File(cacheDir, name)
            if (ApiServiceBackup.downloadBackup(name, cached)) cached else null
        } catch (_: Exception) {
            null
        }
    }

    override fun delete(name: String): Boolean {
        return try {
            ApiServiceBackup.deleteBackup(name)
        } catch (_: Exception) {
            false
        }
    }

    override fun exists(name: String): Boolean {
        return get(name) != null || list().any { it.name == name }
    }

    companion object {
        fun fromContext(context: Context): ServerBackupStorageProvider =
            ServerBackupStorageProvider(context)

        fun sha256(file: File): String {
            val digest = MessageDigest.getInstance("SHA-256")
            file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                var read = input.read(buffer)
                while (read > 0) {
                    digest.update(buffer, 0, read)
                    read = input.read(buffer)
                }
            }
            return digest.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

object BackupServerRecordMapper {
    fun toBackupFileInfo(items: List<JSONObject>): List<BackupFileInfo> =
        items.mapNotNull { json ->
            try {
                val backupId = json.optString("backupId", json.optString("id", ""))
                val name = if (backupId.isBlank()) {
                    json.optString("name", "")
                } else {
                    if (backupId.endsWith(".zip")) backupId else "$backupId.zip"
                }
                if (name.isBlank()) return@mapNotNull null
                BackupFileInfo(
                    name = name,
                    modifiedAt = json.optLong("createdAtMs", json.optLong("receivedAtMs", 0L)),
                    sizeBytes = json.optLong("sizeBytes", 0L),
                )
            } catch (_: Exception) {
                null
            }
        }

    fun toBackupFileInfo(response: org.json.JSONArray?): List<BackupFileInfo> =
        if (response == null) emptyList() else (0 until response.length()).mapNotNull { i ->
            val obj = response.optJSONObject(i)
            if (obj == null) null else toBackupFileInfo(listOf(obj)).firstOrNull()
        }

    fun toBackupFileInfo(resp: String?): List<BackupFileInfo> {
        if (resp.isNullOrBlank()) return emptyList()
        return try {
            val root = JSONObject(resp)
            val arr = root.optJSONArray("items")
                ?: root.optJSONObject("data")?.optJSONArray("items")
                ?: root.optJSONArray("data")
            toBackupFileInfo(arr)
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun toBackupFileInfo(resp: JSONObject?): List<BackupFileInfo> {
        if (resp == null) return emptyList()
        val arr = resp.optJSONArray("items")
            ?: resp.optJSONObject("data")?.optJSONArray("items")
            ?: resp.optJSONArray("data")
        return toBackupFileInfo(arr)
    }
}
