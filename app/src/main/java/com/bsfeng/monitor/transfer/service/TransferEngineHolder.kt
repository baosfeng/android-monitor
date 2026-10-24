package com.bsfeng.monitor.transfer.service

// 从 transfer/service/TransferEngine.kt 拆出：引擎单例的持有者。

import android.content.Context

/**
 * 引擎单例的持有者。
 *
 * 单例存在的原因：HTTP 端口与 UDP 发现必须是进程级唯一。测试用例间要能复位 —— Robolectric 每个用例
 * 会新建 Application，而单例持有的是首个用例的 context，复用会读到已释放的缓存目录与设置。
 */
internal object TransferEngineHolder {
    @Volatile
    private var instance: TransferEngine? = null

    fun get(context: Context): TransferEngine =
        instance ?: synchronized(this) {
            instance ?: TransferEngine(context.applicationContext).also { instance = it }
        }

    @Synchronized
    fun reset() {
        instance?.let { runCatching { it.stop() } }
        instance = null
    }
}
