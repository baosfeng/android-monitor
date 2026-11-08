package com.bsfeng.monitor.util

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.edit
import com.bsfeng.monitor.MonitorApp
import java.util.UUID

/** 与设备身份 / 上报相关的键同住一个 prefs 文件（[AuthTokenStore] 也用同一份）。 */
private const val PREFS_MONITOR = "monitor_prefs"

/** bearer token 的 prefs 键（历史键名，不能改：老版本把明文写在这里）。 */
private const val KEY_AUTH_TOKEN = "auth_token"

object AppUtils {
    private const val KEY_DEVICE_ID = "device_id"

    private val appContext: Context get() = MonitorApp.instance

    /** token 只在注册 / 上报链路用到，懒建以免冷启动就碰 Keystore。 */
    private val tokenStore: AuthTokenStore by lazy { AuthTokenStore(MonitorApp.instance) }

    fun getDeviceId(): String {
        val prefs = appContext.getSharedPreferences(PREFS_MONITOR, Context.MODE_PRIVATE)
        return prefs.getString(KEY_DEVICE_ID, null) ?: run {
            val id = UUID.randomUUID().toString()
            prefs.edit { putString(KEY_DEVICE_ID, id) }
            id
        }
    }

    /**
     * 包名 → 应用显示名。**带缓存**：前台轮询每秒调用一次，而每次都要两次跨进程
     * （`getApplicationInfo` + `getApplicationLabel`），且它与事件采集、开屏跳过共享主线程。
     *
     * 缓存失效条件：包被安装/卸载/替换时 [invalidateAppName]，由 RecordReceiver 的 PACKAGE_* 分支调用；
     * 未命中时回退到包名本身（与应用商店表现一致，且不会因为查不到而丢记录）。
     */
    fun getAppName(packageName: String): String {
        appNameCache[packageName]?.let { return it }
        return try {
            val pm = appContext.packageManager
            val appInfo = pm.getApplicationInfo(packageName, 0)
            pm.getApplicationLabel(appInfo).toString().also { appNameCache.put(packageName, it) }
        } catch (_: PackageManager.NameNotFoundException) {
            packageName
        }
    }

    /** 包被安装 / 卸载 / 替换时清掉它的名字缓存（下次取到的是新版本 label）。 */
    fun invalidateAppName(packageName: String) {
        appNameCache.remove(packageName)
    }

    /** 64 条足够覆盖常用应用；超出后按 LRU 淘汰。安装/卸载清缓存只是加速，不是正确性依赖。 */
    private val appNameCache = android.util.LruCache<String, String>(64)

    fun getToken(): String? = tokenStore.load()

    fun saveToken(token: String) {
        tokenStore.save(token)
    }

    fun getDeviceName(): String {
        return android.os.Build.MODEL
    }

    fun getManufacturer(): String {
        return android.os.Build.MANUFACTURER
    }

    fun getAndroidVersion(): String {
        return android.os.Build.VERSION.RELEASE
    }
}

/**
 * bearer token 的落盘：`monitor_prefs.auth_token`，值是 `v1:` + Keystore AES-GCM 密文。
 *
 * 为什么单独成类：AppUtils 是 object，没法在测试里换掉 cipher，而 AndroidKeyStore
 * 在 Robolectric 沙箱里不存在；本类把 cipher 与 prefs 都变成构造参数，单测注入软件实现。
 *
 * 迁移：老版本直接写明文（键名不变）。读到不带 `v1:` 的值即视为旧明文 ——
 * 原样返回给调用方，同时立刻加密回写，下一次读取（含下次启动）就不会再有明文。
 *
 * 解密失败（Keystore 被系统重置、值被改坏）：按「未登录」返回 null，但**不清数据**。
 * 清掉只会让用户重新注册一次，留着等重新登录时被覆盖更省事；也避免把「密钥暂时不可用」
 * 误判成「用户已登出」后静默删掉唯一凭据。
 */
class AuthTokenStore(
    context: Context,
    private val cipher: SecretCipher = KeystoreAesGcmCipher(KEY_ALIAS),
) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_MONITOR, Context.MODE_PRIVATE)

    /** 读 token；未登录（含加密不可用、密文损坏）返回 null。 */
    fun load(): String? {
        val stored = prefs.getString(KEY_AUTH_TOKEN, null) ?: return null
        if (stored.isBlank()) return null
        if (!SecretBox.isEncoded(stored)) {
            // 旧明文：先用后升级。加密失败时 save 只记日志不动盘，本条仍按明文可用。
            save(stored)
            return stored
        }
        return runCatching { String(SecretBox.decode(cipher, stored), Charsets.UTF_8) }
            .onFailure { Log.w(TAG, "token 解密失败，按未登录处理：" + it.javaClass.simpleName) }
            .getOrNull()
    }

    /** 保存 token；空白等同于清除（旧实现会留一行空串，请求头就变成 `Bearer `）。 */
    fun save(token: String) {
        val trimmed = token.trim()
        if (trimmed.isEmpty()) {
            prefs.edit { remove(KEY_AUTH_TOKEN) }
            return
        }
        // 加密失败宁可保持未登录，也不退回明文落盘
        runCatching { SecretBox.encode(cipher, trimmed.toByteArray(Charsets.UTF_8)) }
            .onSuccess { encoded -> prefs.edit { putString(KEY_AUTH_TOKEN, encoded) } }
            .onFailure { Log.w(TAG, "token 加密失败（未落盘）：" + it.javaClass.simpleName) }
    }

    companion object {
        private const val TAG = "AuthTokenStore"

        /** 独立别名：与 quota_credential_key / totp_secret_key 互不复用。 */
        internal const val KEY_ALIAS = "auth_token_key"
    }
}
