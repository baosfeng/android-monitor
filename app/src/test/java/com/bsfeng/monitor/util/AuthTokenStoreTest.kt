package com.bsfeng.monitor.util

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
 * [AuthTokenStore]（SEC-01）：bearer token 不许明文落 `monitor_prefs.auth_token`。
 *
 * 覆盖三条硬口径：落盘是密文、读回是原文、旧明文迁移一次后不再有明文；
 * 外加两类失败边界：密文损坏 / 换钥匙 → 按未登录处理（不清数据），加密不可用 → 不退回明文。
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AuthTokenStoreTest {

    private lateinit var context: Context
    private lateinit var store: AuthTokenStore

    private val prefs
        get() = context.getSharedPreferences("monitor_prefs", Context.MODE_PRIVATE)

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        prefs.edit().clear().commit()
        store = AuthTokenStore(context, SoftSecretCipher())
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `保存后落盘是密文且能原样读回`() {
        store.save(TOKEN)

        assertEquals(TOKEN, store.load())
        val stored = prefs.getString(KEY, null)
        assertTrue("新值必须是 v1: 密文格式", stored != null && stored.startsWith("v1:"))
        assertFalse("落盘内容不能包含 token 明文", stored!!.contains(TOKEN))
    }

    @Test
    fun `旧明文 token 读一次即升级为密文且仍可读`() {
        // 老版本写法：直接把明文塞进同一个键
        prefs.edit().putString(KEY, "legacy-token").commit()

        assertEquals("legacy-token", store.load())

        val stored = prefs.getString(KEY, null)
        assertTrue("迁移后应为 v1: 密文", stored != null && stored.startsWith("v1:"))
        assertFalse("迁移后盘上不应再有明文", stored!!.contains("legacy-token"))
        assertEquals("legacy-token", store.load())
    }

    @Test
    fun `空白 token 等同清除`() {
        store.save(TOKEN)
        store.save("   ")

        assertNull(store.load())
        assertNull("空白值不应该留一行空串在盘上", prefs.getString(KEY, null))
    }

    @Test
    fun `密文损坏按未登录处理且不删原值`() {
        store.save(TOKEN)
        prefs.edit().putString(KEY, "v1:AAAA").commit()

        assertNull(store.load())
        assertTrue("不能静默删掉用户的凭据行", prefs.contains(KEY))
    }

    @Test
    fun `换一把密钥解不开时按未登录处理`() {
        store.save(TOKEN)

        // 另一把钥匙等价于「Keystore 被系统重置」：解不开，但也不该崩
        assertNull(AuthTokenStore(context, SoftSecretCipher()).load())
    }

    @Test
    fun `加密不可用时绝不退回明文`() {
        AuthTokenStore(context, FailingSecretCipher()).save(TOKEN)

        assertNull(prefs.getString(KEY, null))
    }

    @Test
    fun `迁移时加密不可用则旧明文仍可用且不被覆盖`() {
        prefs.edit().putString(KEY, "legacy-token").commit()

        assertEquals("legacy-token", AuthTokenStore(context, FailingSecretCipher()).load())
        assertEquals("legacy-token", prefs.getString(KEY, null))
    }

    private companion object {
        /** 假 token：形如后端下发的短串，不含真实凭据。 */
        const val TOKEN = "test-bearer-token-8f3a"
        const val KEY = "auth_token"
    }
}
