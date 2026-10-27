package com.bsfeng.monitor.autobook

// 从 autobook/AutoBookEngine.kt 拆出：把一张草稿真正挂成浮窗，并接住三个按钮回调。
// 关闭契约的唯一裁决点是 AutoBookDismissPolicy，这里只按裁决结果去重、落库、记忽略。

import android.util.Log
import android.widget.Toast
import com.bsfeng.monitor.finance.CategoryTree
import kotlinx.coroutines.launch

internal fun AutoBookEngine.display(
    draft: AutoBookDraft,
    tree: CategoryTree,
    scheduledAtMs: Long = System.currentTimeMillis(),
) {
    val mainAt = System.currentTimeMillis()
    scrimHintShown = false
    try {
        overlay.show(
            draft = draft,
            categoryTree = tree,
            onConfirm = { type, categoryName, remark ->
                // 先打标再落库：落库是异步的，这期间页面若再抛事件不能复弹
                val effect = AutoBookDismissPolicy.effectOf(AutoBookCloseSource.CONFIRM)
                if (effect.markConfirmed) {
                    dedup.markConfirmed(draft.dedupKeys, System.currentTimeMillis())
                }
                Log.i(
                    AutoBookEngine.TAG,
                    "用户确认记账: type=$type category=$categoryName amount=${draft.amount} 说明=$remark"
                )
                scope.launch {
                    persist(draft, type, categoryName, remark, tree)
                    // 落库是异步的（persist 内部吞异常只写日志），浮窗场景最怕「用户以为记上了」。
                    // 用同一套持久化去重口径回查一次，给成功 / 失败两条明确的提示。
                    val booked = isAlreadyBooked(draft)
                    toast(if (booked) "已自动记账" else "自动记账失败，请到记账页手动补记")
                }
            },
            onIgnore = {
                // **唯一**会写「忽略」记忆的地方：只有用户点「忽略」按钮才走到这里
                val effect = AutoBookDismissPolicy.effectOf(AutoBookCloseSource.IGNORE)
                if (effect.markDismissed) {
                    dedup.markDismissed(draft.dedupKeys, System.currentTimeMillis())
                }
                if (effect.persistIgnored) ignoreStore.markIgnored(draft.sourceId)
                Log.i(AutoBookEngine.TAG, "用户忽略: sourceId=${draft.sourceId}")
                toast("已忽略，7 天内不再提示同笔")
            },
            onScrimTap = {
                // 遮罩点击 = 吞掉点击：不关浮窗、不写 ignore、不写 DISMISSED（2026-10-20 用户要求）
                val effect = AutoBookDismissPolicy.effectOf(AutoBookCloseSource.SCRIM_TAP)
                Log.d(AutoBookEngine.TAG, "遮罩点击已吞掉（closeOverlay=${effect.closeOverlay}）")
                if (!scrimHintShown) {
                    scrimHintShown = true
                    // 文案逐字取自 s15 契约 s15-scrim 行
                    toast("点遮罩不会关闭，请选『记一笔』或『忽略』")
                }
            }
        )
        Log.i(
            AutoBookEngine.TAG,
            "延迟追踪: 查库→主线程=${mainAt - scheduledAtMs}ms " +
                "挂载=${System.currentTimeMillis() - mainAt}ms"
        )
        dedup.markShown(draft.dedupKeys, System.currentTimeMillis())
        shownCount++
        // 浮窗已展示：预采集完成使命，清掉避免串到之后的同金额交易
        preDraft = null
        pendingRemark = null
        redPacketPaymentPending = false
        redPacketFingerprintArmed = false
    } catch (e: Exception) {
        // 浮窗挂不上（例如系统限制）不能静默：记日志，下一笔还会再试
        Log.e(AutoBookEngine.TAG, "浮窗展示失败: ${e.message}", e)
    } finally {
        pendingOverlaySourceId = null
    }
}

/**
 * 浮窗里的轻提示。
 *
 * 走系统 Toast 而不是 [com.bsfeng.monitor.ui.components.ToastHost]：浮窗是无障碍叠加窗口，
 * 不在本 App 的 Compose 树里，够不到 App 级 ToastHost；系统 Toast 在浮窗之上仍可见。
 */
private fun AutoBookEngine.toast(text: String) {
    service.mainExecutor.execute {
        Toast.makeText(service, text, Toast.LENGTH_SHORT).show()
    }
}
