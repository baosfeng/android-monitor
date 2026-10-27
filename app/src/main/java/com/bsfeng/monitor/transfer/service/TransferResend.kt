package com.bsfeng.monitor.transfer.service

// C-17「重新发送」与 C-08「重试失败项」的共用能力：把一条终态发送任务按原文件、原目标
// 重新建一次会话上传（旧记录保留在「最近完成」）。
//
// 契约依据（docs/交互设计/s22-局域网互传-交互契约.md:25）：
// - 前置：任务终态且**发送方向**的源文件仍可读；
// - 源文件被删除或无读权限 → 本项置灰并说明「源文件已不在本机」；
// - 目标设备离线 → 置灰「对方不在线」；
// - 接收方向不显示本项。
//
// 为什么这里能做（此前 canResend 恒 false 的原因已解除）：发送时把源 Uri 记进
// [TransferEngine.sourceFiles] 并随任务落库，重启后按方向回填，于是「原文件」找得回来。

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.util.Log
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferTask
import kotlinx.coroutines.launch

/** 「重新发送」的可用性（C-17 / C-08 共用；纯逻辑，可 JVM 单测）。 */
internal sealed class ResendAvailability {

    /** 本项**不渲染**（接收方向 / 未到终态）。 */
    object Hidden : ResendAvailability()

    /** 渲染但置灰，[reason] 是给用户看的原因（契约逐字口径）。 */
    data class Blocked(val reason: String) : ResendAvailability()

    /** 渲染且可点。 */
    object Ready : ResendAvailability()
}

/** 是否「可点重发」（Hidden / Blocked 都不可点；Blocked 仍要渲染成置灰态）。 */
internal fun isResendReady(state: ResendAvailability): Boolean = state is ResendAvailability.Ready

/** 源文件已不在本机（无读权限 / 已删除）。契约 s22 C-17 边界逐字。 */
internal const val RESEND_BLOCK_SOURCE_GONE: String = "源文件已不在本机"

/** 目标设备不在线。契约 s22 C-17 边界逐字。 */
internal const val RESEND_BLOCK_PEER_OFFLINE: String = "对方不在线"

/**
 * 定「重新发送」这一项渲染成什么。
 *
 * 判定顺序（先报更能指导用户动作的那条）：方向 / 终态 → 源文件 → 对方在线。
 * 源文件排在前：文件没了，对方在不在线都没意义。
 */
internal fun resendAvailabilityOf(
    direction: TransferDirection,
    isTerminal: Boolean,
    hasReadableSource: Boolean,
    peerOnline: Boolean,
): ResendAvailability = when {
    direction != TransferDirection.SEND -> ResendAvailability.Hidden
    !isTerminal -> ResendAvailability.Hidden
    !hasReadableSource -> ResendAvailability.Blocked(RESEND_BLOCK_SOURCE_GONE)
    !peerOnline -> ResendAvailability.Blocked(RESEND_BLOCK_PEER_OFFLINE)
    else -> ResendAvailability.Ready
}

/**
 * 源 Uri 是否还读得出来。
 *
 * 只问「能不能拿到描述符」：不读内容、不算 sha256 —— 那是真正发送时才做的事。
 * 读不出来就是「已不在本机」（SAF 授权在进程重启后失效也落到这一档）。
 */
internal object TransferSourceProbe {

    private const val TAG = "TransferEngine"

    fun canRead(context: Context, uri: Uri): Boolean = runCatching {
        // 先按权限位问一次：能省掉一次 openFileDescriptor 的开销
        val granted = context.checkCallingOrSelfUriPermission(
            uri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION,
        ) == PackageManager.PERMISSION_GRANTED
        if (granted) return@runCatching true
        context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false
    }.getOrElse {
        Log.w(TAG, "源文件读取探测失败: " + it.message)
        false
    }
}

/** 一次「重新发送」失败的原因（UI 直接展示 [message]）。 */
internal class ResendFailedException(message: String) : Exception(message)

/**
 * 把一条终态发送任务重新发起（C-17 / C-08 的共同实现）。
 *
 * @param task 目标任务；非发送方向或未到终态时回失败（UI 本不该显示入口，防连点 / 越界调用）。
 * @param onResult 真实结果：
 *   - 成功 = 新批次已建（新卡片自行出现在「进行中」顶部，进度由既有链路回写）；
 *   - 失败 = [ResendFailedException]，message 是契约逐字口径，UI 直接 toast。
 */
internal fun TransferEngine.resendTaskImpl(task: TransferTask, onResult: (Result<Unit>) -> Unit) {
    scope.launch {
        val uri = sourceUriForResend(task) {
            onResult(Result.failure(ResendFailedException(RESEND_BLOCK_SOURCE_GONE)))
        } ?: return@launch
        val peer = resendPeerFor(task, _devices.value) ?: run {
            onResult(Result.failure(ResendFailedException(RESEND_BLOCK_PEER_OFFLINE)))
            return@launch
        }
        // 旧记录保留在「最近完成」：sendFiles 用新 sessionId 建新批次，不动 [task]
        sendFiles(peer, listOf(uri))
        onResult(Result.success(Unit))
    }
}

/**
 * 重发要用的源 Uri；拿不回就不发，并把失败交给调用方（[onMissing]）。
 *
 * 拿不回 = 没登记过（老版本落库的任务 / 接收任务）或已读不出来（用户删了文件、
 * SAF 授权随进程失效）。**不静默返回**：调用方一定会收到一条失败原因。
 */
internal fun TransferEngine.sourceUriForResend(task: TransferTask, onMissing: () -> Unit): Uri? {
    if (task.direction != TransferDirection.SEND) {
        onMissing()
        return null
    }
    val key = taskKey(task.sessionId, task.file.fileId)
    val uri = sourceFiles[key]
    if (uri == null || !TransferSourceProbe.canRead(appContext, uri)) {
        sourceFiles.remove(key)
        onMissing()
        return null
    }
    return uri
}

/**
 * 重发要发给哪台设备。
 *
 * 优先用**当前**发现到的设备信息（对端可能换过 IP / 端口），且必须在线；
 * 发现列表里没有它时，回落到任务里那份快照仅当快照标记在线 —— 这样「对方不在线」
 * 才真的是不在线，而不是「此刻没扫到」（后者会误伤正在传的合法情况）。
 */
internal fun resendPeerFor(task: TransferTask, devices: List<PeerDevice>): PeerDevice? {
    if (task.direction != TransferDirection.SEND) return null
    val live = devices.firstOrNull { it.deviceId == task.peer.deviceId }
    if (live != null) return live.takeIf { it.online }
    return task.peer.takeIf { it.online }
}
