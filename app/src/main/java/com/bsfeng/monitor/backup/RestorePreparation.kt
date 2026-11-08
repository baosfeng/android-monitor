package com.bsfeng.monitor.backup

import android.content.Context
import java.io.InputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 还原第一步的**调度层**：把 [BackupManager.prepareRestore] 那整段阻塞 IO 顶到 `Dispatchers.IO`。
 *
 * 为什么单独一层而不是让调用方自觉：`prepareRestore` 内部依次做「SAF 流 copyTo 落盘 →
 * `backupNow` 整包 pre-restore（全库打包 + sha256）→ 解包 + 全量校验」，几十 MB 时在主线程上
 * 就是一次界面冻死。它是 suspend 但不自己切调度器，调用方很容易（曾经也确实）忘了包一层。
 *
 * 纯函数式的实现让「跑在哪个线程」可以被单测断言（`RestorePreparationTest`）：注入的
 * `openStream` 在 prepareRestore 内部执行，正好是观察实际执行线程的探针。
 */
internal suspend fun prepareRestoreOnIo(
    context: Context,
    openStream: suspend () -> InputStream?,
): RestoreOutcome = withContext(Dispatchers.IO) {
    BackupManager(context).prepareRestore(openStream)
}
