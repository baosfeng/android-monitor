package com.bsfeng.monitor.backup

import org.json.JSONArray
import org.json.JSONObject

/** 归档内单个文件的指纹：还原前按 `size + sha256` 校验，任一不符即中止。 */
data class BackupFileEntry(
    val path: String,
    val size: Long,
    val sha256: String,
)

/** 单张表的行数快照，用于还原后自检「数据是否真的回来了」。 */
data class BackupTableCount(
    val table: String,
    val rows: Long,
)

/**
 * 备份包清单（`manifest.json`）。
 *
 * 校验口径：`schemaVersion` 高于当前代码支持值 → 拒绝还原（旧 App 读不懂新结构）；
 * 低于等于当前值 → 允许还原（列按名读取，旧结构缺列走默认值）。
 */
data class BackupManifest(
    val schemaVersion: Int,
    val appVersionName: String,
    val createdAt: Long,
    val files: List<BackupFileEntry>,
    val tables: List<BackupTableCount>,
) {

    fun toJson(): JSONObject = JSONObject().apply {
        put(KEY_SCHEMA_VERSION, schemaVersion)
        put(KEY_APP_VERSION, appVersionName)
        put(KEY_CREATED_AT, createdAt)
        put(KEY_FILES, JSONArray().apply {
            files.forEach { f ->
                put(JSONObject().apply {
                    put(KEY_PATH, f.path)
                    put(KEY_SIZE, f.size)
                    put(KEY_SHA256, f.sha256)
                })
            }
        })
        put(KEY_TABLES, JSONArray().apply {
            tables.forEach { t ->
                put(JSONObject().apply {
                    put(KEY_TABLE, t.table)
                    put(KEY_ROWS, t.rows)
                })
            }
        })
    }

    companion object {
        /** 当前代码能写、也能读的最新结构版本。 */
        const val SCHEMA_VERSION = 1
        const val MANIFEST_NAME = "manifest.json"

        private const val KEY_SCHEMA_VERSION = "schemaVersion"
        private const val KEY_APP_VERSION = "appVersionName"
        private const val KEY_CREATED_AT = "createdAt"
        private const val KEY_FILES = "files"
        private const val KEY_TABLES = "tables"
        private const val KEY_PATH = "path"
        private const val KEY_SIZE = "size"
        private const val KEY_SHA256 = "sha256"
        private const val KEY_TABLE = "table"
        private const val KEY_ROWS = "rows"

        /** 非法 / 缺字段 → null，由调用方按「清单不可信」处理，不抛异常。 */
        fun parse(raw: String?): BackupManifest? {
            if (raw.isNullOrBlank()) return null
            return runCatching { fromJson(JSONObject(raw)) }.getOrNull()
        }

        fun fromJson(obj: JSONObject): BackupManifest? {
            // schemaVersion / appVersionName / createdAt 是必备字段；缺失即视为非法清单
            if (!obj.has(KEY_SCHEMA_VERSION) || !obj.has(KEY_APP_VERSION) || !obj.has(KEY_CREATED_AT)) {
                return null
            }
            val schemaVersion = obj.optInt(KEY_SCHEMA_VERSION, -1)
            if (schemaVersion <= 0) return null
            val files = mutableListOf<BackupFileEntry>()
            val filesArray = obj.optJSONArray(KEY_FILES) ?: return null
            for (i in 0 until filesArray.length()) {
                val f = filesArray.optJSONObject(i) ?: return null
                val path = f.optString(KEY_PATH, "")
                // sha256 缺失或长度不符（64 hex）即视为非法：校验依赖它
                val sha256 = f.optString(KEY_SHA256, "")
                if (path.isBlank() || sha256.length != SHA256_HEX_LENGTH) return null
                if (!f.has(KEY_SIZE)) return null
                files += BackupFileEntry(path, f.optLong(KEY_SIZE, -1L), sha256)
            }
            val tables = mutableListOf<BackupTableCount>()
            obj.optJSONArray(KEY_TABLES)?.let { array ->
                for (i in 0 until array.length()) {
                    val t = array.optJSONObject(i) ?: continue
                    val table = t.optString(KEY_TABLE, "")
                    if (table.isBlank()) continue
                    tables += BackupTableCount(table, t.optLong(KEY_ROWS, 0L))
                }
            }
            return BackupManifest(
                schemaVersion = schemaVersion,
                appVersionName = obj.optString(KEY_APP_VERSION, ""),
                createdAt = obj.optLong(KEY_CREATED_AT, 0L),
                files = files,
                tables = tables,
            )
        }

        private const val SHA256_HEX_LENGTH = 64
    }
}

/**
 * 归档内路径 → 应用数据目录相对路径。
 *
 * 还原时按 `dataDir + path` 落盘，因此必须先拦掉绝对路径与 `..`，
 * 否则一个伪造的备份包可以把文件写到应用目录之外（Zip Slip）。
 */
object BackupPaths {
    const val DB_DIR = "databases"
    const val PREFS_DIR = "shared_prefs"
    const val FILES_DIR = "files"

    fun isSafe(relativePath: String): Boolean {
        if (relativePath.isBlank()) return false
        if (relativePath.startsWith("/") || relativePath.startsWith("\\")) return false
        if (relativePath.contains('\\')) return false
        val segments = relativePath.split('/')
        return segments.none { it.isEmpty() || it == "." || it == ".." }
    }
}
