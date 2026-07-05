package com.bsfeng.monitor.service

import android.content.Context

/**
 * 广播接收器注册器 — 当前已禁用。
 *
 * 项目已精简为仅追踪导航/页面跳转，不再需要任何系统广播监听。
 * 保留此类仅为保持 API 兼容，registerAll() 为空实现。
 */
class ReceiverRegistrar(context: Context) {
    fun registerAll() { /* 已禁用 - 不再注册任何广播 */ }
    fun unregisterAll() { /* 已禁用 */ }
}
