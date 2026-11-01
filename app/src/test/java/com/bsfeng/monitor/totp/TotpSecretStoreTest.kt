package com.bsfeng.monitor.totp

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * [TotpSecretStore] 单元测试（Robolectric + 软件 AES-GCM 替身）。
 *
 * 覆盖：加解密往返、空值删除、篡改密文返回 null 且**原数据保留**、账户间密钥隔离。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class TotpSecretStoreTest {

    private lateinit var context: Context
    private lateinit var store: TotpSecretStore

    private val prefs
        get() = context.getSharedPreferences(TotpSecretStore.PREFS_NAME, Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs.edit().clear().commit()
        store = TotpSecretStore(context, SoftwareAesGcmCipher())
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `加解密往返：写入后可读回同一密钥且落盘不是明文`() {
        store.save(7L, TEST_SECRET)

        assertEquals(TEST_SECRET, store.load(7L))
        assertTrue(store.has(7L))
        val stored = prefs.getString("account:7", null)
        assertTrue("落盘内容必须是密文", stored != null && !stored.contains(TEST_SECRET))
    }

    @Test
    fun `空值等同于删除`() {
        store.save(7L, TEST_SECRET)
        store.save(7L, "  ")

        assertNull(store.load(7L))
        assertFalse(store.has(7L))
    }

    @Test
    fun `篡改密文返回 null 且不清数据不抛异常`() {
        store.save(7L, TEST_SECRET)
        prefs.edit().putString("account:7", "AAAA").commit()

        assertNull(store.load(7L))
        assertTrue("解密失败不能删数据", prefs.contains("account:7"))
        assertTrue("数据仍在但不可读，has 应为 true", store.has(7L))
    }

    @Test
    fun `账户密钥互相隔离且删除只影响自己`() {
        store.save(1L, "JBSWY3DPEHPK3PXP")
        store.save(2L, "GEZDGNBVGY3TQOJQ")

        assertEquals("JBSWY3DPEHPK3PXP", store.load(1L))
        assertEquals("GEZDGNBVGY3TQOJQ", store.load(2L))

        store.delete(1L)
        assertNull(store.load(1L))
        assertEquals("GEZDGNBVGY3TQOJQ", store.load(2L))
    }
}
