package com.bsfeng.monitor.totp.ui

// 验证器模块的路由：六个落点（列表 / 添加 / 扫码 / 手动 / 详情 / 备份）与旋转、进程重建后的复原。
// 编解码口径照 MainRoutes.kt 的 FinanceRouteSaver：只存可保存的原语，未知标签回落到列表。

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver

/** 验证器模块内的落点；全部是 push 屏（模块没有底部 Tab）。 */
sealed class TotpRoute {
    data object List : TotpRoute()
    data object Add : TotpRoute()
    data object Scan : TotpRoute()
    data object Manual : TotpRoute()
    data class Detail(val accountId: Long) : TotpRoute()
    data object Backup : TotpRoute()
}

/**
 * 路由编解码器（rememberSaveable 用）。
 *
 * Detail 丢主键或主键类型不符时回落到列表而不是抛异常：进程重建后账户可能已被删除，
 * 硬还原到详情只会得到一张空屏。
 */
val TotpRouteSaver: Saver<TotpRoute, Any> = listSaver(
    save = { route ->
        when (route) {
            TotpRoute.List -> listOf(KIND_LIST)
            TotpRoute.Add -> listOf(KIND_ADD)
            TotpRoute.Scan -> listOf(KIND_SCAN)
            TotpRoute.Manual -> listOf(KIND_MANUAL)
            is TotpRoute.Detail -> listOf(KIND_DETAIL, route.accountId)
            TotpRoute.Backup -> listOf(KIND_BACKUP)
        }
    },
    restore = { saved ->
        when (saved.firstOrNull() as? String) {
            KIND_ADD -> TotpRoute.Add
            KIND_SCAN -> TotpRoute.Scan
            KIND_MANUAL -> TotpRoute.Manual
            KIND_DETAIL -> (saved.getOrNull(1) as? Long)?.let(TotpRoute::Detail) ?: TotpRoute.List
            KIND_BACKUP -> TotpRoute.Backup
            else -> TotpRoute.List
        }
    },
)

private const val KIND_LIST = "list"
private const val KIND_ADD = "add"
private const val KIND_SCAN = "scan"
private const val KIND_MANUAL = "manual"
private const val KIND_DETAIL = "detail"
private const val KIND_BACKUP = "backup"
