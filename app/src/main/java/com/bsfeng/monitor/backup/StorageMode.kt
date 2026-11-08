package com.bsfeng.monitor.backup

import android.content.Context
import androidx.core.content.edit

enum class StorageMode(val value: String, val label: String) {
    LOCAL("local", "本地"),
    SERVER("server", "服务器");

    companion object {
        fun fromValue(raw: String?): StorageMode =
            values().firstOrNull {
                it.value.equals(raw, ignoreCase = true) || it.name.equals(raw, ignoreCase = true)
            } ?: LOCAL
    }
}

fun StorageMode.provider(context: android.content.Context? = null): BackupStorageProvider =
    BackupStorageProviders.forMode(this, context)

object StorageModePrefs {
    const val PREFS_NAME: String = "backup_prefs"
    const val KEY_STORAGE_MODE: String = "storageMode"
    const val DEFAULT_VALUE: String = "local"

    fun read(context: Context?): StorageMode =
        StorageMode.fromValue(
            context?.applicationContext
                ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ?.getString(KEY_STORAGE_MODE, null),
        )

    fun write(context: Context?, mode: StorageMode) {
        context?.applicationContext
            ?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.edit { putString(KEY_STORAGE_MODE, mode.value) }
    }
}
