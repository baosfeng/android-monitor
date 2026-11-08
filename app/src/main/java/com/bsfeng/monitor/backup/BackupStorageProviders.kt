package com.bsfeng.monitor.backup

import android.content.Context

object BackupStorageProviders {
    fun forMode(mode: StorageMode, context: Context? = null): BackupStorageProvider =
        when (mode) {
            StorageMode.LOCAL -> if (context == null) LocalBackupStorageProvider(java.io.File(System.getProperty("java.io.tmpdir") ?: ".")) else LocalBackupStorageProvider(context)
            StorageMode.SERVER -> ServerBackupStorageProvider(context)
        }

    fun providerFor(mode: StorageMode, context: Context? = null): BackupStorageProvider =
        forMode(mode, context)

    fun select(mode: StorageMode, context: Context? = null): BackupStorageProvider =
        forMode(mode, context)

    fun fromContext(context: Context?): BackupStorageProvider {
        val mode = StorageModePrefs.read(context)
        return forMode(mode, context)
    }

    fun current(context: Context?): BackupStorageProvider = fromContext(context)
}

object StorageModeDecision {
    fun decide(context: Context?): StorageMode = StorageModePrefs.read(context)

    fun decide(context: Context?, requested: StorageMode?): StorageMode =
        requested ?: StorageModePrefs.read(context)

    fun provider(context: Context?): BackupStorageProvider =
        BackupStorageProviders.fromContext(context)
}
