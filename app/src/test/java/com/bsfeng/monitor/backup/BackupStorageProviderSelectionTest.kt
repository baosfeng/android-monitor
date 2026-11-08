package com.bsfeng.monitor.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupStorageProviderSelectionTest {

    @Test
    fun selectsLocalAndServerProviders() {
        val local = BackupStorageProviders.forMode(StorageMode.LOCAL, null)
        val server = BackupStorageProviders.forMode(StorageMode.SERVER, null)
        assertTrue(local is LocalBackupStorageProvider)
        assertTrue(server is ServerBackupStorageProvider)
    }

    @Test
    fun storageModeDecisionDefaultsToLocal() {
        assertTrue(StorageModeDecision.provider(null) is LocalBackupStorageProvider)
        assertEquals(StorageMode.LOCAL, StorageModeDecision.decide(null))
    }
}
