package com.bsfeng.monitor

// 从 MainActivity.kt 拆出：记账 Tab 的子页路由 —— FinanceRoute 密封类与它的 rememberSaveable 编解码器。
// 负责「记账首页 / 新增 / 编辑 / 统计 / 预算 / 导入」六个落点以及旋转、进程重建后的复原。

import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.listSaver
import com.bsfeng.monitor.finance.model.Transaction

/** 记账 Tab 内的落点：首页与其五个子页。 */
internal sealed class FinanceRoute {
    data object Main : FinanceRoute()
    data class AddTransaction(val fromMonth: String) : FinanceRoute()
    data class EditTransaction(val transaction: Transaction) : FinanceRoute()
    data object Stats : FinanceRoute()
    data object Budget : FinanceRoute()
    data object Import : FinanceRoute()
}

/** 旋转 / 进程重建后仍停留在原子页：把路由编码成可保存的原语列表。 */
internal val FinanceRouteSaver: Saver<FinanceRoute, Any> = listSaver(
    save = { route ->
        when (route) {
            is FinanceRoute.Main -> listOf("main")
            is FinanceRoute.AddTransaction -> listOf("add", route.fromMonth)
            is FinanceRoute.EditTransaction -> with(route.transaction) {
                listOf(
                    "edit", id, type, categoryName, amount, accountName, remark, tag,
                    date, dateTime, createdAt, updatedAt, source, sourceId,
                )
            }
            is FinanceRoute.Stats -> listOf("stats")
            is FinanceRoute.Budget -> listOf("budget")
            is FinanceRoute.Import -> listOf("import")
        }
    },
    restore = { saved ->
        when (saved.firstOrNull() as? String) {
            "add" -> FinanceRoute.AddTransaction(saved.getOrNull(1) as? String ?: "")
            "edit" -> if (saved.size < 14) FinanceRoute.Main else FinanceRoute.EditTransaction(
                Transaction(
                    id = saved[1] as Long,
                    type = saved[2] as Int,
                    categoryName = saved[3] as String,
                    amount = saved[4] as Double,
                    accountName = saved[5] as String,
                    remark = saved[6] as String,
                    tag = saved[7] as String,
                    date = saved[8] as String,
                    dateTime = saved[9] as String,
                    createdAt = saved[10] as Long,
                    updatedAt = saved[11] as Long,
                    source = saved[12] as String,
                    sourceId = saved[13] as String,
                )
            )
            "stats" -> FinanceRoute.Stats
            "budget" -> FinanceRoute.Budget
            "import" -> FinanceRoute.Import
            else -> FinanceRoute.Main
        }
    },
)
