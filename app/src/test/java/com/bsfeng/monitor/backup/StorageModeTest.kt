package com.bsfeng.monitor.backup

import org.junit.Assert.assertEquals
import org.junit.Test

class StorageModeTest {

    @Test
    fun parsesLocalAndServerCaseInsensitively() {
        assertEquals(StorageMode.LOCAL, StorageMode.fromValue("local"))
        assertEquals(StorageMode.LOCAL, StorageMode.fromValue("LOCAL"))
        assertEquals(StorageMode.SERVER, StorageMode.fromValue("server"))
        assertEquals(StorageMode.SERVER, StorageMode.fromValue("SeRvEr"))
    }

    @Test
    fun unknownOrNullDefaultsToLocal() {
        assertEquals(StorageMode.LOCAL, StorageMode.fromValue(null))
        assertEquals(StorageMode.LOCAL, StorageMode.fromValue("nope"))
    }

    @Test
    fun StorageModePrefsReadsAndWritesThroughPrefs() {
        assertEquals(StorageMode.LOCAL, StorageModePrefs.read(null))
    }
}
