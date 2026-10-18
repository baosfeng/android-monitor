package com.bsfeng.monitor.transfer.service

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import com.bsfeng.monitor.BuildConfig
import com.bsfeng.monitor.domain.transfer.CONFIRM_TIMEOUT_MS
import com.bsfeng.monitor.domain.transfer.NO_PROGRESS_TIMEOUT_MS
import com.bsfeng.monitor.domain.transfer.DeletedTask
import com.bsfeng.monitor.domain.transfer.DeviceTypes
import com.bsfeng.monitor.domain.transfer.PeerDevice
import com.bsfeng.monitor.domain.transfer.RETRY_DELAYS_MS
import com.bsfeng.monitor.domain.transfer.TransferDirection
import com.bsfeng.monitor.domain.transfer.TransferErrorCode
import com.bsfeng.monitor.domain.transfer.TransferFile
import com.bsfeng.monitor.domain.transfer.TransferStatus
import com.bsfeng.monitor.domain.transfer.TransferTask
import com.bsfeng.monitor.domain.transfer.TransferTaskDeletion
import com.bsfeng.monitor.domain.transfer.UNKNOWN_PEER_ALIAS
import com.bsfeng.monitor.data.DatabaseHelper
import com.bsfeng.monitor.transfer.data.TransferTaskStore
import com.bsfeng.monitor.transfer.discovery.DeviceRegistry
import com.bsfeng.monitor.transfer.discovery.UdpDiscoveryService
import com.bsfeng.monitor.transfer.policy.ProgressSpeed
import com.bsfeng.monitor.transfer.policy.ProgressThrottle
import com.bsfeng.monitor.transfer.policy.SendStallReason
import com.bsfeng.monitor.transfer.policy.SendTaskTimeoutPolicy
import com.bsfeng.monitor.transfer.transport.ApiResponse
import com.bsfeng.monitor.transfer.transport.DownloadsSink
import com.bsfeng.monitor.transfer.transport.MiniHttpClient
import com.bsfeng.monitor.transfer.transport.MiniHttpServer
import com.bsfeng.monitor.transfer.transport.StreamCopier
import com.bsfeng.monitor.transfer.transport.TransferRoutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.lang.ref.WeakReference
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 「传送」中枢：UDP 发现 + HTTP 服务 + 收发会话（协议 §3 / §4 / §5 / §6 的落地）。
 *
 * - 进程内单例（[getInstance]），由 [TransferService] 以前台服务承载；
 * - 对外只暴露 [StateFlow]（[devices] / [tasks] / [pinPrompt] / [running]），UI 不直接碰 socket；
 * - 收：实现 [TransferRoutes]，落到 `Downloads/局域网互传/`；
 * - 发：`MiniHttpClient` 逐文件续传，断网按 1s/2s/4s 退避重试 3 次。
 *
 * 线程模型：所有网络 / 磁盘操作跑在 [scope]（IO）；公开方法可在任意线程调用。
 *
 * [confirmTimeoutMs] 默认取协议 §4.1 的 60s；测试通过 [createForTesting] 注入短超时验证 4007。
 */
class TransferEngine private constructor(
    private val appContext: Context,
    private val confirmTimeoutMs: Long = CONFIRM_TIMEOUT_MS,
    private val noProgressTimeoutMs: Long = NO_PROGRESS_TIMEOUT_MS,
    private val tickMs: Long = DEVICE_TICK_MS,
) : TransferRoutes {

    private val settings = TransferSettings(appContext)
    private val registry = DeviceRegistry()
    private val downloads = DownloadsSink(appContext)
    private val client = MiniHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val taskStore: TransferTaskStore by lazy {
        TransferTaskStore(DatabaseHelper.getInstance(appContext))
    }

    private val started = AtomicBoolean(false)

    /** 历史是否已从库中恢复（首次进入前台 / 首次启动服务时加载一次）。 */
    private val historyLoaded = AtomicBoolean(false)

    @Volatile
    private var server: MiniHttpServer? = null

    @Volatile
    private var discovery: UdpDiscoveryService? = null

    @Volatile
    private var hapticTarget: WeakReference<View>? = null

    /** App 是否在前台可见（「后台接收」关闭时据此启停服务与发现线程）。 */
    @Volatile
    private var appVisible: Boolean = false

    private val _devices = MutableStateFlow<List<PeerDevice>>(emptyList())
    private val _tasks = MutableStateFlow<List<TransferTask>>(emptyList())
    private val _pinPrompt = MutableStateFlow<PinPrompt?>(null)
    private val _running = MutableStateFlow(false)
    private val _discoverable = MutableStateFlow(false)
    private val _deletedTasks = MutableSharedFlow<DeletedTask>(extraBufferCapacity = DELETED_EVENT_BUFFER)
    private val _autoCloseEvents = MutableSharedFlow<Unit>(extraBufferCapacity = AUTO_CLOSE_EVENT_BUFFER)

    /** 「自动关闭接收」的倒计时（纯逻辑，起点只在内存，进程重启不恢复）。 */
    private val autoClose = AutoCloseCountdown()

    @Volatile
    private var autoCloseJob: Job? = null

    private val _autoCloseDeadline = MutableStateFlow(0L)

    /**
     * 删除事件（UI 用它开 5s 撤销窗口）。
     *
     * 任务列表是进程内内存态（协议未要求任务持久化）：删除只保证进程内一致，
     * 进程被回收后记录与撤销窗口一起消失。见 [TransferTaskDeletion]。
     */
    val deletedTasks: SharedFlow<DeletedTask> = _deletedTasks.asSharedFlow()

    /** 在线设备（在线优先、最近出现在前）。 */
    val devices: StateFlow<List<PeerDevice>> = _devices.asStateFlow()

    /** 收发任务列表（新任务在前）。 */
    val tasks: StateFlow<List<TransferTask>> = _tasks.asStateFlow()

    /** 当前 PIN 交互（接收端展示 / 发送端输入）。 */
    val pinPrompt: StateFlow<PinPrompt?> = _pinPrompt.asStateFlow()

    /** 端口是否已常驻。 */
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** 是否参与发现（「可被发现」开关）。 */
    val discoverable: StateFlow<Boolean> = _discoverable.asStateFlow()

    /**
     * 「自动关闭接收」的到期时刻（epoch ms；0 = 未计时）。
     *
     * 纯内存状态（不持久化）：UI 据此显示「N 分钟后自动关闭」，进程重启后归零。
     */
    val autoCloseDeadline: StateFlow<Long> = _autoCloseDeadline.asStateFlow()

    /**
     * 倒计时到点事件（宿主 [TransferService] 收到后停服务：移除常驻通知、磁贴回 INACTIVE）。
     *
     * 引擎负责计时与自身停止，服务负责收尾——两边都听同一个真值源 [running]。
     */
    val autoCloseEvents: SharedFlow<Unit> = _autoCloseEvents.asSharedFlow()

    /** 本端设置（UI 读写提示音开关）。 */
    fun settings(): TransferSettings = settings

    /** 本端设备 id / 别名（UI 展示）。 */
    val deviceId: String get() = settings.deviceId
    val alias: String get() = settings.alias

    /** 实际 HTTP 端口；未启动为 -1。 */
    val localPort: Int get() = server?.port ?: -1

    /** 设置触觉反馈目标（Compose 层的 `LocalView.current`）。 */
    fun setHapticTarget(view: View?) {
        hapticTarget = view?.let { WeakReference(it) }
    }

    /** 启动发现与服务（幂等）。返回实际 HTTP 端口（失败 -1）。 */
    fun start(): Int {
        if (started.compareAndSet(false, true)) {
            val httpServer = MiniHttpServer(this)
            val boundPort = runCatching { httpServer.start(scope) }
                .onFailure { Log.e(TAG, "HTTP 服务启动失败: ${it.message}") }
                .getOrDefault(-1)
            if (boundPort > 0) {
                server = httpServer
                val udp = UdpDiscoveryService(
                    context = appContext,
                    deviceId = settings.deviceId,
                    alias = settings.alias,
                    appVersion = appVersion(),
                    httpPort = { localPort },
                    registry = registry,
                    onDeviceSeen = { refreshDevices() },
                )
                discovery = udp
                if (ServiceModePolicy.shouldRunDiscovery(settings.backgroundReceive, appVisible, settings.discoverable)) {
                    udp.start(scope)
                }
                _discoverable.value = settings.discoverable
                scope.launch { tickerLoop() }
                historyLoaded.set(true)
                scope.launch { loadPersistedHistory() }
                _running.value = true
                // 启动成功后才起算「自动关闭接收」（设置关闭时不做任何事）
                startAutoCloseCountdown()
                Log.i(TAG, "传送服务已启动，HTTP 端口 $boundPort")
            } else {
                started.set(false)
            }
        } else if (ServiceModePolicy.shouldRunDiscovery(
                backgroundReceive = settings.backgroundReceive,
                appVisible = appVisible,
                discoverable = settings.discoverable,
            )
        ) {
            // 服务已在运行：补齐发现线程。首次启动时 App 可见性可能晚于点击，
            // 不补这一步会出现「HTTP 端口在跑、组播/广播却不发」的半启动状态。
            ensureDiscovery()
        }
        return localPort
    }

    /** 停止发现与服务，清理在途会话（保留 `.part` 供下次续传）。 */
    fun stop() {
        // 手动关闭 / 服务销毁：取消「自动关闭」计时（重新 start 会重新计时）
        cancelAutoCloseCountdown()
        if (!started.compareAndSet(true, false)) return
        server?.stop()
        server = null
        discovery?.stop()
        discovery = null
        // 停止不清理 `.part`：协议 §6 要求失败/中断保留临时文件供下次续传
        sessions.clear()
        outgoing.clear()
        pendingSend = null
        _pinPrompt.value = null
        _running.value = false
        refreshDevices()
    }

    /**
     * 「自动关闭接收」设置变化后重放一次计时。
     *
     * 运行中改时长 → 按新时长重新计时（与免配对窗口改时长同口径）；
     * 改成「关闭」→ 取消计时；服务没在跑 → 什么都不做（下次启动按新设置起算）。
     */
    fun refreshAutoCloseSchedule() {
        if (!started.get() || _running.value != true) {
            cancelAutoCloseCountdown()
            return
        }
        startAutoCloseCountdown()
    }

    /** 当前自动关闭剩余毫秒（未计时返回 0）。 */
    fun autoCloseRemainingMs(nowMs: Long = System.currentTimeMillis()): Long =
        ReceivingAutoClose.remainingMs(_autoCloseDeadline.value, nowMs)

    /**
     * 按当前设置起算倒计时（服务启动成功后调用）。
     *
     * 幂等：每次调用先清掉旧任务，因此「重新开启接收」= 重新计时、改设置 = 用新时长重算。
     */
    private fun startAutoCloseCountdown() {
        cancelAutoCloseCountdown()
        val minutes = ReceivingAutoClose.normalize(settings.autoCloseMinutes)
        if (minutes == ReceivingAutoClose.OFF) return
        autoClose.start(minutes = minutes)
        _autoCloseDeadline.value = autoClose.deadlineMs()
        Log.i(TAG, "自动关闭接收已计时：$minutes 分钟后停止")
        autoCloseJob = scope.launch { autoCloseLoop() }
    }

    /** 取消计时并清理任务（手动关闭服务、设置改回关闭、服务销毁）。 */
    private fun cancelAutoCloseCountdown() {
        autoCloseJob?.cancel()
        autoCloseJob = null
        autoClose.cancel()
        _autoCloseDeadline.value = 0L
    }

    /**
     * 倒计时循环：到点先发事件让宿主服务收尾（移除通知 / 磁贴回 INACTIVE），再自停兜底。
     *
     * 分段 `delay` 而非一次睡满：设置变更或手动关闭时 `cancel()` 立即生效，不留悬挂任务。
     */
    private suspend fun autoCloseLoop() {
        while (scope.isActive) {
            if (autoClose.shouldStop()) {
                Log.i(TAG, "自动关闭接收：到点，停止服务")
                // 先通知宿主（主线程执行 shutdown），没有监听者时下面的 stop() 也保证服务真的停
                _autoCloseEvents.tryEmit(Unit)
                stop()
                break
            }
            delay(autoClose.remainingMs().coerceAtLeast(1L).coerceAtMost(AUTO_CLOSE_TICK_MS))
        }
    }

    /**
     * 「可被发现」开关：关闭后停止 UDP 公告与应答（对端列表随之清空），HTTP 端口继续常驻，
     * 已配对设备仍可直接按地址发送。
     */
    fun setDiscoverable(enabled: Boolean) {
        settings.discoverable = enabled
        _discoverable.value = enabled
        if (!started.get()) return
        if (enabled &&
            ServiceModePolicy.shouldRunDiscovery(settings.backgroundReceive, appVisible, discoverable = true)
        ) {
            // 发现线程可能尚未创建（服务启动时 App 可见性还是 false），统一走 ensureDiscovery
            ensureDiscovery()
        } else {
            discovery?.stop()
            discovery = null
            refreshDevices()
        }
    }

    /**
     * 修改本机名称（对端设备列表里显示的名字）。
     *
     * 发现线程在构造时取别名，因此改名后重建一次公告线程，下一轮公告即生效。
     */
    fun setAlias(value: String) {
        settings.alias = value
        if (!started.get()) return
        discovery?.stop()
        discovery = null
        if (ServiceModePolicy.shouldRunDiscovery(settings.backgroundReceive, appVisible, settings.discoverable)) {
            ensureDiscovery()
        }
    }

    /**
     * App 前后台切换：更新可见标记，并按 [ServiceModePolicy] 启停发现线程。
     *
     * 「后台接收」关闭时，退到后台就停掉组播接收线程（服务本身由 UI 层 stopSelf）；
     * 常驻模式下发现线程保持运行。
     */
    fun setAppVisible(visible: Boolean) {
        appVisible = visible
        // 历史任务与「接收开关」无关：进入前台就恢复一次。
        // 否则重启 App 后要先去点「开启接收」才能看到上次的传输记录（用户会以为记录丢了）。
        if (visible && historyLoaded.compareAndSet(false, true)) {
            scope.launch { loadPersistedHistory() }
        }
        val shouldDiscover = ServiceModePolicy.shouldRunDiscovery(
            backgroundReceive = settings.backgroundReceive,
            appVisible = visible,
            discoverable = settings.discoverable,
        )
        val udp = discovery
        if (shouldDiscover) {
            if (started.get() && settings.discoverable && (udp == null || !udp.isRunning())) {
                ensureDiscovery()
            }
        } else {
            udp?.stop()
            refreshDevices()
        }
    }

    /** 按当前设置重建发现线程（服务 / 可见性变化后调用）。 */
    private fun ensureDiscovery() {
        if (!started.get()) return
        if (discovery?.isRunning() == true) return
        val udp = UdpDiscoveryService(
            context = appContext,
            deviceId = settings.deviceId,
            alias = settings.alias,
            appVersion = appVersion(),
            httpPort = { localPort },
            registry = registry,
            onDeviceSeen = { refreshDevices() },
        )
        discovery = udp
        udp.start(scope)
    }

    /** 通知快捷动作：暂停接收 [minutes] 分钟（期间 offer 直接拒绝、不弹 PIN）。 */
    fun pauseOffers(minutes: Int = PAUSE_MINUTES) {
        settings.pauseOffers(minutes)
        _pinPrompt.value = null
    }

    /** 通知快捷动作：立即恢复接收。 */
    fun resumeOffers() {
        settings.resumeOffers()
    }

    /** 当前是否处于暂停窗口。 */
    fun isOffersPaused(nowMs: Long = System.currentTimeMillis()): Boolean = settings.isOffersPaused(nowMs)

    private suspend fun tickerLoop() {
        while (scope.isActive) {
            delay(tickMs)
            refreshDevices()
            enforceTimeouts()
            pruneExpiredSessions()
        }
    }

    /**
     * 超时收敛：确认超时（4007）与「传输中长时间无进展」都统一落为 FAILED，
     * 任务随之从「进行中」移出（模块的单一状态出口，UI 不再自己判断）。
     *
     * 接收侧按会话收敛；发送侧按任务行收敛（见 [convergeStalledSendTasks]），口径同一个。
     */
    private fun enforceTimeouts() {
        val now = System.currentTimeMillis()
        sessions.values.toList().forEach { session ->
            when {
                // 确认超时保持任务 message = "failed"（协议线值），沿用既有口径
                session.state == TransferStatus.AWAITING_CONFIRM && now >= session.confirmDeadlineMs ->
                    failSession(session, TransferErrorCode.CONFIRM_TIMEOUT, "")

                session.state == TransferStatus.TRANSFERRING &&
                    now - session.lastProgressAtMs >= noProgressTimeoutMs ->
                    failSession(session, TransferErrorCode.CONFIRM_TIMEOUT, "长时间无进展")
            }
        }
        convergeStalledSendTasks(now)
    }

    /**
     * 发送侧超时收敛（与接收侧同口径、同错误码 4007）。
     *
     * 真机 Bug：「进行中」卡着一条发送任务两小时以上——发送会话没有超时路径，
     * 对端失联 / 拒收后不回话时任务永远停在 `OFFERED`（已提交清单）或 `TRANSFERRING`。
     */
    private fun convergeStalledSendTasks(now: Long) {
        _tasks.value
            .filter { it.direction == TransferDirection.SEND && !it.status.isTerminal }
            .forEach { task ->
                val reason = SendTaskTimeoutPolicy.reasonOf(
                    task = task,
                    nowMs = now,
                    confirmTimeoutMs = confirmTimeoutMs,
                    noProgressTimeoutMs = noProgressTimeoutMs,
                ) ?: return@forEach
                failSendSession(task.sessionId, reason)
            }
    }

    /**
     * 一条发送会话整体收敛（幂等）：同会话的活跃任务全部落 FAILED(4007)，
     * 摘掉 [outgoing] 条目（避免内存泄漏与「已收敛还在上传」）并收起等待输入的 PIN 弹层。
     */
    private fun failSendSession(sessionId: String, reason: SendStallReason) {
        val targets = _tasks.value.filter { it.sessionId == sessionId && !it.status.isTerminal }
        // 先摘条目：即便任务行已被别处收敛，残留的 outgoing 条目也要清掉
        val tracked = outgoing.remove(sessionId) != null
        if (targets.isEmpty()) return
        targets.forEach { failTask(it.sessionId, it.file.fileId, TransferErrorCode.CONFIRM_TIMEOUT, reason.message) }
        if (pendingSend?.sessionId == sessionId) {
            pendingSend = null
            if (_pinPrompt.value?.sessionId == sessionId) _pinPrompt.value = null
        }
        if (tracked) playSound(TransferSoundPlayer.Event.FAILURE)
        Log.i(
            TAG,
            "发送会话 $sessionId 超时收敛：${reason.message}（${TransferErrorCode.CONFIRM_TIMEOUT}）",
        )
    }

    /** 会话失败收敛（幂等）：改状态 + 写任务行 + 清 PIN 弹层。 */
    private fun failSession(session: IncomingSession, code: Int, message: String) {
        if (session.state.isTerminal) return
        session.state = TransferStatus.FAILED
        session.failureCode = code
        session.failureMessage = message
        session.pin = null
        // 先同步任务行再清 PIN 弹层：观察者看到弹层消失时任务状态已经是最终态
        refreshSessionTasks(session)
        if (_pinPrompt.value?.sessionId == session.sessionId) _pinPrompt.value = null
        Log.i(TAG, "会话 ${session.sessionId} 失败：${message.ifBlank { "确认超时" }}（$code）")
    }

    private fun refreshDevices() {
        val now = System.currentTimeMillis()
        val snapshot = registry.snapshot(now)
        val known = _devices.value.map { it.deviceId }.toSet()
        _devices.value = snapshot
        if (snapshot.any { it.deviceId !in known && it.online }) {
            playSound(TransferSoundPlayer.Event.NEW_DEVICE)
        }
    }

    // ─────────────────────────── 接收：TransferRoutes ───────────────────────────

    override suspend fun info(): ApiResponse = ApiResponse.ok(
        JSONObject().apply {
            put("deviceId", settings.deviceId)
            put("alias", settings.alias)
            put("deviceType", DeviceTypes.ANDROID)
            put("port", localPort)
            put("proto", "http")
            put("caps", JSONArray(listOf("send", "recv")))
            put("appVersion", appVersion())
        },
    )

    override suspend fun offer(body: JSONObject, pairKey: String?): ApiResponse {
        val senderDeviceId = body.optString("senderDeviceId", "")
        if (senderDeviceId.isBlank()) {
            return ApiResponse.error(ApiResponse.PROTOCOL_ERROR, "缺少 senderDeviceId")
        }
        // 别名优先取 offer 自身的 senderAlias，发现层表仅作兜底；两者都空才回落「未知设备」
        // （发现层在 alias 为空时会用 deviceId 占位，这种占位不算真实别名）
        val senderAlias = body.optString("senderAlias", "").trim()
            .ifBlank {
                registry.find(senderDeviceId)?.alias?.trim()
                    ?.takeIf { it.isNotBlank() && it != senderDeviceId }
                    .orEmpty()
            }
            .ifBlank { UNKNOWN_PEER_ALIAS }
        val filesJson = body.optJSONArray("files") ?: return ApiResponse.error(
            ApiResponse.PROTOCOL_ERROR,
            "缺少 files",
        )
        val files = parseFiles(filesJson) ?: return ApiResponse.error(
            ApiResponse.PROTOCOL_ERROR,
            "files 字段非法",
        )
        if (files.isEmpty()) return ApiResponse.error(ApiResponse.PROTOCOL_ERROR, "files 为空")
        val totalBytes = files.sumOf { it.size }
        if (freeBytes() < totalBytes + DISK_MARGIN_BYTES) {
            return ApiResponse.error(TransferErrorCode.DISK_FULL)
        }
        // 暂停接收（通知上的「暂停 5 分钟」）：不建会话、不弹 PIN，直接回 denied
        if (settings.isOffersPaused()) {
            return ApiResponse.ok(
                JSONObject().apply {
                    put("sessionId", "")
                    put("token", "")
                    put("state", TransferStatus.DENIED.wire)
                    put("accepted", JSONArray())
                    put("message", "接收端已暂停")
                },
            )
        }

        val requestedSessionId = body.optString("sessionId", "").takeIf { it.isNotBlank() }
        val session = requestedSessionId?.let { sessions[it] }?.takeIf { it.senderDeviceId == senderDeviceId }
            ?: sessions.values.firstOrNull {
                it.senderDeviceId == senderDeviceId &&
                    it.state != TransferStatus.COMPLETED && it.state != TransferStatus.CANCELLED &&
                    it.files.values.map { f -> f.file.sha256 }.sorted() == files.map { it.sha256 }.sorted()
            }
            ?: createIncomingSession(senderDeviceId, senderAlias, files)

        // 会话已批准（重复 offer 幂等返回）
        if (session.state == TransferStatus.TRANSFERRING) {
            return approvedResponse(session, null)
        }

        val pin = body.optString("pin", "").takeIf { it.isNotBlank() }
        val storedPairKey = settings.pairingKey(senderDeviceId)
        val pairKeyOk = !pairKey.isNullOrBlank() && storedPairKey != null && pairKey == storedPairKey

        if (pairKeyOk) {
            return approve(session, persistedPairKey = null)
        }
        // 免配对窗口 / 体积阈值（OR）：命中即免 PIN，顺带建立配对便于后续免确认
        if (settings.shouldAutoApprove(totalBytes)) {
            if (_pinPrompt.value?.sessionId == session.sessionId) _pinPrompt.value = null
            val newKey = TransferSettings.generatePairingKey()
            settings.savePairingKey(senderDeviceId, newKey)
            return approve(session, persistedPairKey = newKey)
        }
        if (pin != null) {
            if (session.state != TransferStatus.AWAITING_CONFIRM) {
                // 没有待确认会话时收到 PIN：复位为待确认再校验，避免时序竞态
                session.state = TransferStatus.AWAITING_CONFIRM
            }
            if (session.pin != null && session.pin == pin) {
                val newKey = TransferSettings.generatePairingKey()
                settings.savePairingKey(senderDeviceId, newKey)
                return approve(session, persistedPairKey = newKey)
            }
            return pendingResponse(session, "PIN 不正确")
        }

        // 未配对且没有 PIN：生成 PIN 并等待（协议 §4.1：60s 超时 → 4007）
        if (session.pin == null || System.currentTimeMillis() > session.confirmDeadlineMs) {
            session.pin = TransferSettings.generatePin()
            session.confirmDeadlineMs = System.currentTimeMillis() + confirmTimeoutMs
            scheduleConfirmTimeout(session)
        }
        session.state = TransferStatus.AWAITING_CONFIRM
        _pinPrompt.value = PinPrompt(
            sessionId = session.sessionId,
            peerAlias = senderAlias,
            direction = TransferDirection.RECV,
            pin = session.pin,
            fileName = files.first().name,
            expiresAtMs = session.confirmDeadlineMs,
        )
        refreshSessionTasks(session)
        return pendingResponse(session, "")
    }

    override suspend fun offerStatus(sessionId: String): ApiResponse {
        enforceTimeouts()
        val session = sessions[sessionId] ?: return ApiResponse.error(TransferErrorCode.SESSION_EXPIRED)
        return ApiResponse.ok(
            JSONObject().apply {
                put("sessionId", session.sessionId)
                put("state", session.state.wire)
                put("accepted", acceptedArray(session))
            },
        )
    }

    override suspend fun upload(
        sessionId: String,
        fileId: String,
        offset: Long,
        token: String?,
        body: InputStream,
        contentLength: Long,
    ): ApiResponse {
        val session = sessions[sessionId] ?: return ApiResponse.error(TransferErrorCode.SESSION_EXPIRED)
        if (token.isNullOrBlank() || token != session.token) {
            return ApiResponse.error(TransferErrorCode.TOKEN_MISMATCH)
        }
        if (session.state != TransferStatus.TRANSFERRING) {
            return ApiResponse.error(
                TransferErrorCode.SESSION_EXPIRED,
                "会话状态 ${session.state.wire} 不可上传",
            )
        }
        val incoming = session.files[fileId]
            ?: return ApiResponse.error(TransferErrorCode.SESSION_EXPIRED, "文件不在会话内")
        // 续传偏移以 `.part` 实际大小为准：进程被回收重启后内存值是 0，磁盘才是真相
        val expectedOffset = resumableOffset(incoming)
        incoming.received = expectedOffset
        if (offset != expectedOffset) {
            return ApiResponse.error(
                TransferErrorCode.OFFSET_MISMATCH,
                "offset=$offset 与已收 $expectedOffset 不一致",
            )
        }
        val remaining = incoming.file.size - offset
        if (remaining < 0L) return ApiResponse.error(TransferErrorCode.OFFSET_MISMATCH)
        if (contentLength > remaining) {
            return ApiResponse.error(
                TransferErrorCode.OFFSET_MISMATCH,
                "请求体 $contentLength 字节超出剩余 $remaining",
            )
        }
        if (freeBytes() < remaining + DISK_MARGIN_BYTES) {
            return ApiResponse.error(TransferErrorCode.DISK_FULL)
        }

        val part = StreamCopier.partFile(downloads.partDir(), incoming.file.fileId)
        incoming.part = part
        val append = part.exists() && part.length() == offset
        val targetOffset = if (append) offset else 0L
        val writeLength = if (append) remaining else incoming.file.size
        if (!append && part.exists()) part.delete()

        var lastPatched = targetOffset
        // 进度发射节流（150ms）：64KiB 一块就发射一次会把 UI 主线程打满；真实字节与
        // `lastProgressAtMs` 仍逐块更新，只有 StateFlow 发射被合并
        var lastEmitMs = ProgressThrottle.NO_EMIT
        val result = try {
            FileOutputStream(part, append).use { output ->
                StreamCopier.copy(
                    input = body,
                    output = output,
                    expectedTotal = writeLength,
                    onChunk = { copied ->
                        val received = targetOffset + copied
                        incoming.received = received
                        val now = System.currentTimeMillis()
                        session.lastProgressAtMs = now
                        if (ProgressThrottle.shouldEmit(lastEmitMs, now)) {
                            lastEmitMs = now
                            patchTask(sessionId, fileId) {
                                it.copy(
                                    receivedBytes = received,
                                    bytesPerSecond = speedFor(it, received, now),
                                    updatedAtMs = now,
                                )
                            }
                        }
                        lastPatched = received
                    },
                )
            }
        } catch (e: IOException) {
            Log.w(TAG, "写入 ${incoming.file.name} 失败: ${e.message}")
            return ApiResponse.error(TransferErrorCode.DISK_FULL, "写入失败：${e.message}")
        }
        incoming.received = targetOffset + result.bytesCopied
        if (lastPatched != incoming.received) {
            patchTask(sessionId, fileId) {
                it.copy(receivedBytes = incoming.received, updatedAtMs = System.currentTimeMillis())
            }
        }

        if (incoming.received < incoming.file.size) {
            // 分片上传：对端可继续从 offset 续传
            return ApiResponse.ok(
                JSONObject().apply {
                    put("fileId", fileId)
                    put("received", incoming.received)
                    put("sha256", "")
                },
            )
        }

        return finishFile(session, incoming, part)
    }

    override suspend fun cancel(sessionId: String): ApiResponse {
        val session = sessions[sessionId] ?: return ApiResponse.error(TransferErrorCode.SESSION_EXPIRED)
        session.state = TransferStatus.CANCELLED
        session.pin = null
        session.files.values.forEach { it.part?.let { p -> if (p.exists()) p.delete() } }
        if (_pinPrompt.value?.sessionId == sessionId) _pinPrompt.value = null
        refreshSessionTasks(session)
        return ApiResponse.ok(JSONObject().put("sessionId", sessionId))
    }

    private suspend fun finishFile(
        session: IncomingSession,
        incoming: IncomingFile,
        part: File,
    ): ApiResponse = withContext(Dispatchers.IO) {
        val actualSha = StreamCopier.sha256Of(part)
        if (!actualSha.equals(incoming.file.sha256, ignoreCase = true) && incoming.file.sha256.isNotBlank()) {
            part.delete()
            // 校验失败是终态：立刻落 FAILED/4005，而不是让任务挂在「进行中」等 60s 无进展超时
            session.state = TransferStatus.FAILED
            failTask(session.sessionId, incoming.file.fileId, TransferErrorCode.SHA256_MISMATCH, "文件校验失败")
            return@withContext ApiResponse.error(TransferErrorCode.SHA256_MISMATCH)
        }
        val displayName = StreamCopier.uniqueFileName(
            incoming.file.name,
            downloads.existingNames()::contains,
        )
        val uri = downloads.createPending(displayName, incoming.file.mime)
            ?: run {
                session.state = TransferStatus.FAILED
                failTask(session.sessionId, incoming.file.fileId, TransferErrorCode.DISK_FULL, "无法创建目标文件")
                return@withContext ApiResponse.error(TransferErrorCode.DISK_FULL, "无法创建目标文件")
            }
        val output = downloads.openOutput(uri)
            ?: run {
                session.state = TransferStatus.FAILED
                failTask(session.sessionId, incoming.file.fileId, TransferErrorCode.DISK_FULL, "无法打开目标文件")
                return@withContext ApiResponse.error(TransferErrorCode.DISK_FULL, "无法打开目标文件")
            }
        try {
            part.inputStream().use { input ->
                output.use { out -> StreamCopier.copy(input, out, incoming.file.size) }
            }
            downloads.publish(uri)
            part.delete()
            // 记录落盘产物：用户删除这条已完成任务时一并删除（发送任务不登记，源文件不删）
            publishedFiles[taskKey(session.sessionId, incoming.file.fileId)] = uri
        } catch (e: IOException) {
            downloads.delete(uri)
            Log.w(TAG, "落盘失败: ${e.message}")
            session.state = TransferStatus.FAILED
            failTask(session.sessionId, incoming.file.fileId, TransferErrorCode.DISK_FULL, "落盘失败")
            return@withContext ApiResponse.error(TransferErrorCode.DISK_FULL, "落盘失败：${e.message}")
        }

        if (session.files.values.all { it.received >= it.file.size }) {
            session.state = TransferStatus.COMPLETED
            if (_pinPrompt.value?.sessionId == session.sessionId) _pinPrompt.value = null
            playSound(TransferSoundPlayer.Event.COMPLETE)
        }
        refreshSessionTasks(session)
        ApiResponse.ok(
            JSONObject().apply {
                put("fileId", incoming.file.fileId)
                put("received", incoming.received)
                put("sha256", actualSha)
            },
        )
    }

    // ─────────────────────────── 发送 ───────────────────────────

    /** 把选中的 URI 发给 [peer]（后台执行，进度写回 [tasks]）。 */
    fun sendFiles(peer: PeerDevice, uris: List<Uri>) {
        if (uris.isEmpty()) return
        scope.launch { sendFilesInternal(peer, uris) }
    }

    private suspend fun sendFilesInternal(peer: PeerDevice, uris: List<Uri>) {
        val sources = uris.mapNotNull { readSource(it) }
        if (sources.isEmpty()) {
            Log.w(TAG, "没有可发送的文件")
            return
        }
        val sessionId = UUID.randomUUID().toString()
        sources.forEachIndexed { index, source ->
            upsertTask(
                TransferTask(
                    sessionId = sessionId,
                    peer = peer,
                    direction = TransferDirection.SEND,
                    file = TransferFile("f${index + 1}", source.name, source.size, source.sha256, source.mime),
                    status = TransferStatus.OFFERED,
                    updatedAtMs = System.currentTimeMillis(),
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
        }

        val offerBody = buildOfferJson(sources)
        val headerPairs = listOfNotNull(
            settings.pairingKey(peer.deviceId)?.let { Pair(PairKeyHeader, it) }
        )
        val response = runCatching {
            client.postJson(
                MiniHttpClient.buildUrl(peer.baseUrl, "offer"),
                offerBody,
                headerPairs.toMap(),
            )
        }.getOrElse { e ->
            failAll(sessionId, ApiResponse.PROTOCOL_ERROR, "提交清单失败：${e.message}")
            return
        }
        val api = response.api ?: run {
            failAll(sessionId, ApiResponse.PROTOCOL_ERROR, "对端响应非法（HTTP ${response.httpCode}）")
            return
        }
        if (!api.isOk) {
            failAll(sessionId, api.code, api.message)
            return
        }
        api.string("pairingKey").takeIf { it.isNotBlank() }?.let {
            settings.savePairingKey(peer.deviceId, it)
        }
        val remoteSessionId = api.string("sessionId", sessionId)
        val token = api.string("token")
        when (TransferStatus.fromWire(api.string("state"))) {
            TransferStatus.TRANSFERRING -> {
                // 任务行沿用本端会话 id（offered 时就按它落库 / 展示）：对端 sessionId 只用于 HTTP，
                // 否则进度回写找不到任务行，卡片会永远停在「已提交清单」。
                outgoing[sessionId] = OutgoingSession(peer, remoteSessionId, token, sources)
                uploadAll(sessionId, remoteSessionId, peer, sources, token, readAccepted(api))
            }

            TransferStatus.AWAITING_CONFIRM -> {
                pendingSend = PendingSend(peer, sources, remoteSessionId)
                renameTasks(sessionId, remoteSessionId)
                _pinPrompt.value = PinPrompt(
                    sessionId = remoteSessionId,
                    peerAlias = peer.alias.trim().ifBlank { UNKNOWN_PEER_ALIAS },
                    direction = TransferDirection.SEND,
                    pin = null,
                    fileName = sources.first().name,
                )
            }

            TransferStatus.DENIED -> failAll(remoteSessionId, TransferErrorCode.USER_DENIED, "对方拒绝接收")

            else -> failAll(
                remoteSessionId,
                ApiResponse.PROTOCOL_ERROR,
                "未知会话状态：${api.string("state")}",
            )
        }
    }

    /** 发送端输入 PIN 后继续（协议 §5）。 */
    fun submitPin(pin: String) {
        val pending = pendingSend ?: return
        if (pin.length != 4 || pin.any { !it.isDigit() }) return
        scope.launch {
            val body = buildOfferJson(pending.sources, pin = pin)
            val headerPairs = listOfNotNull(
                settings.pairingKey(pending.peer.deviceId)?.let { Pair(PairKeyHeader, it) },
            )
            val response = runCatching {
                client.postJson(
                    MiniHttpClient.buildUrl(pending.peer.baseUrl, "offer"),
                    body,
                    headerPairs.toMap(),
                )
            }.getOrElse { e ->
                failAll(pending.sessionId, ApiResponse.PROTOCOL_ERROR, "PIN 校验请求失败：${e.message}")
                pendingSend = null
                _pinPrompt.value = null
                return@launch
            }
            val api = response.api
            if (api == null) {
                failAll(pending.sessionId, ApiResponse.PROTOCOL_ERROR, "PIN 校验响应非法")
                pendingSend = null
                _pinPrompt.value = null
                return@launch
            }
            if (!api.isOk) {
                failAll(pending.sessionId, api.code, api.message)
                pendingSend = null
                _pinPrompt.value = null
                return@launch
            }
            api.string("pairingKey").takeIf { it.isNotBlank() }?.let {
                settings.savePairingKey(pending.peer.deviceId, it)
            }
            if (TransferStatus.fromWire(api.string("state")) != TransferStatus.TRANSFERRING) {
                // PIN 错误：保持弹层，提示重试
                _pinPrompt.value = _pinPrompt.value?.copy(message = "PIN 不正确，请重试")
                return@launch
            }
            pendingSend = null
            _pinPrompt.value = null
            val token = api.string("token")
            outgoing[pending.sessionId] =
                OutgoingSession(pending.peer, pending.sessionId, token, pending.sources)
            uploadAll(pending.sessionId, pending.sessionId, pending.peer, pending.sources, token, readAccepted(api))
        }
    }

    private suspend fun uploadAll(
        taskSessionId: String,
        remoteSessionId: String,
        peer: PeerDevice,
        sources: List<SendSource>,
        token: String,
        offsets: Map<String, Long>,
    ) {
        sources.forEachIndexed { index, source ->
            // 已被超时收敛（条目被摘）：不再继续上传后面的文件
            if (outgoing[taskSessionId] == null) return
            val fileId = "f${index + 1}"
            val offset = offsets[fileId] ?: 0L
            patchTask(taskSessionId, fileId) {
                it.copy(status = TransferStatus.TRANSFERRING, receivedBytes = offset)
            }
            val ok = uploadWithRetry(taskSessionId, remoteSessionId, peer, source, fileId, offset, token)
            if (!ok) {
                outgoing.remove(taskSessionId)
                return
            }
        }
        // 只有仍在跟踪的会话才播完成音：已被超时收敛的不再「假装成功」
        if (outgoing.remove(taskSessionId) != null) playSound(TransferSoundPlayer.Event.COMPLETE)
    }

    private suspend fun uploadWithRetry(
        taskSessionId: String,
        remoteSessionId: String,
        peer: PeerDevice,
        source: SendSource,
        fileId: String,
        offset: Long,
        token: String,
    ): Boolean {
        var attempt = 0
        // 进度发射节流（150ms，跨重试保持）：不节流时 100MB / 64KiB = 约 57 次发射每秒，
        // Compose 每秒全量重组几十次 → 传输中界面白屏。状态跃迁 / 终态不经过这里。
        var lastEmitMs = ProgressThrottle.NO_EMIT
        while (true) {
            val outcome = runCatching {
                val url = MiniHttpClient.buildUrl(
                    peer.baseUrl,
                    "upload",
                    listOf(
                        "session" to remoteSessionId,
                        "file" to fileId,
                        "offset" to offset.toString(),
                    ),
                )
                val input = appContext.contentResolver.openInputStream(source.uri)
                    ?: throw IOException("无法读取源文件")
                val sent = input.use { stream ->
                    skipFully(stream, offset)
                    client.putStream(
                        url = url,
                        input = stream,
                        contentLength = source.size - offset,
                        headers = mapOf(TokenHeader to token),
                        onProgress = { progress ->
                            val now = System.currentTimeMillis()
                            if (ProgressThrottle.shouldEmit(lastEmitMs, now)) {
                                lastEmitMs = now
                                patchTask(taskSessionId, fileId) {
                                    // 已收敛为终态的任务不再被进度回写唤醒
                                    if (it.status.isTerminal) {
                                        it
                                    } else {
                                        val received = offset + progress
                                        it.copy(
                                            receivedBytes = received,
                                            bytesPerSecond = speedFor(it, received, now),
                                            status = TransferStatus.TRANSFERRING,
                                            updatedAtMs = now,
                                        )
                                    }
                                }
                            }
                        },
                    )
                }
                parseUploadResponse(taskSessionId, fileId, source, sent)
            }
            val success = outcome.getOrElse { e ->
                Log.w(TAG, "上传 $fileId 失败: ${e.message}")
                false
            }
            if (success) {
                patchTask(taskSessionId, fileId) {
                    // 终态不可被回写翻转（已超时收敛的任务保持失败）
                    if (it.status.isTerminal) {
                        it
                    } else {
                        it.copy(
                            status = TransferStatus.COMPLETED,
                            receivedBytes = source.size,
                            bytesPerSecond = 0L,
                            updatedAtMs = System.currentTimeMillis(),
                        )
                    }
                }
                return true
            }
            if (attempt >= RETRY_DELAYS_MS.size) {
                failTask(taskSessionId, fileId, TransferErrorCode.SESSION_EXPIRED, "断网重试 3 次仍失败")
                playSound(TransferSoundPlayer.Event.FAILURE)
                return false
            }
            delay(RETRY_DELAYS_MS[attempt])
            attempt++
        }
    }

    private fun parseUploadResponse(
        taskSessionId: String,
        fileId: String,
        source: SendSource,
        response: MiniHttpClient.HttpResponse,
    ): Boolean {
        val api = response.api
        if (api == null || !api.isOk) {
            if (api != null && api.code == TransferErrorCode.SHA256_MISMATCH) {
                failTask(taskSessionId, fileId, api.code, api.message)
                return false
            }
            Log.w(TAG, "上传响应异常：HTTP ${response.httpCode} ${response.body.take(120)}")
            return false
        }
        val remoteSha = api.string("sha256")
        if (remoteSha.isNotBlank() && !remoteSha.equals(source.sha256, ignoreCase = true)) {
            failTask(taskSessionId, fileId, TransferErrorCode.SHA256_MISMATCH, "sha256 不一致")
            playSound(TransferSoundPlayer.Event.FAILURE)
            return false
        }
        return api.long("received", -1L) < 0L || api.long("received") >= source.size
    }

    // ─────────────────────────── 用户操作 ───────────────────────────

    /** 接收端拒绝一次待确认的传输。 */
    fun denyIncoming(sessionId: String) {
        val session = sessions[sessionId] ?: return
        session.state = TransferStatus.DENIED
        session.pin = null
        session.files.values.forEach { it.part?.let { p -> if (p.exists()) p.delete() } }
        if (_pinPrompt.value?.sessionId == sessionId) _pinPrompt.value = null
        refreshSessionTasks(session)
        playSound(TransferSoundPlayer.Event.FAILURE)
    }

    /** 关闭 PIN 弹层（发送端取消本次发送）。 */
    fun dismissPin() {
        val pending = pendingSend
        pendingSend = null
        _pinPrompt.value = null
        if (pending != null) {
            _tasks.update { list ->
                list.map {
                    if (it.sessionId == pending.sessionId) {
                        it.copy(status = TransferStatus.CANCELLED, updatedAtMs = System.currentTimeMillis())
                    } else {
                        it
                    }
                }
            }
        }
    }

    /** 取消一条任务（收：删 `.part`；发：尽力通知对端 `/cancel`）。 */
    fun cancelTask(sessionId: String) {
        sessions[sessionId]?.let { session ->
            session.state = TransferStatus.CANCELLED
            session.pin = null
            session.files.values.forEach { it.part?.let { p -> if (p.exists()) p.delete() } }
        }
        if (_pinPrompt.value?.sessionId == sessionId) _pinPrompt.value = null
        val outbound = outgoing.remove(sessionId)
        if (outbound != null) {
            scope.launch {
                runCatching {
                    client.postEmpty(
                        MiniHttpClient.buildUrl(
                            outbound.peer.baseUrl,
                            "cancel",
                            listOf("session" to outbound.remoteSessionId),
                        ),
                        mapOf(TokenHeader to outbound.token),
                    )
                }
            }
        }
        _tasks.update { list ->
            list.map {
                if (it.sessionId == sessionId && it.isActive) {
                    it.copy(status = TransferStatus.CANCELLED, updatedAtMs = System.currentTimeMillis())
                } else {
                    it
                }
            }
        }
    }

    /** 移除配对（设置里的「移除配对」）。 */
    fun removePairing(deviceId: String) {
        settings.removePairingKey(deviceId)
    }

    /** 已配对设备 id 列表（设置区展示）。 */
    fun pairedDeviceIds(): Set<String> = settings.pairingKeys().keys

    // ─────────────────────────── 删除 / 撤销 ───────────────────────────

    /**
     * 删除一条已终结任务（记录 + 已落盘接收文件），完成后经 [deletedTasks] 通知 UI 展示撤销窗口。
     *
     * 进行中任务在 [deleteTaskNow] 处被拒绝（UI 侧滑动回弹），不会产生事件。
     */
    fun deleteTask(sessionId: String, fileId: String) {
        scope.launch {
            deleteTaskNow(sessionId, fileId)?.let { _deletedTasks.emit(it) }
        }
    }

    /**
     * 删除的同步实现（IO 线程调用；测试可直接调用不走 Flow）。
     *
     * @return 删除快照；任务不存在或仍在进行中时返回 `null`，列表保持不变。
     */
    internal fun deleteTaskNow(sessionId: String, fileId: String): DeletedTask? {
        val key = taskKey(sessionId, fileId)
        val deleted = TransferTaskDeletion.delete(
            tasks = _tasks.value,
            sessionId = sessionId,
            fileId = fileId,
            deletionId = UUID.randomUUID().toString(),
            removeReceivedFile = {
                val uri = publishedFiles.remove(key) ?: return@delete false
                downloads.delete(uri)
                // 只要登记过落盘产物，撤销时就无法把文件恢复到 Downloads（可能已被系统清理）
                true
            },
        ) ?: return null
        _tasks.update { TransferTaskDeletion.without(it, deleted) }
        deletedSnapshots[deleted.deletionId] = deleted
        scope.launch {
            runCatching {
                taskStore.delete(deleted.task.sessionId, deleted.task.file.fileId)
            }.onFailure { Log.w(TAG, "任务历史删除失败: ${it.message}") }
        }
        return deleted
    }

    /**
     * 撤销删除：把任务按原下标放回列表。
     *
     * @return 删除快照；撤销窗口已结束 / 已撤销过时返回 `null`。
     *   快照的 [DeletedTask.fileDeleted] 为 true 时，UI 另提示「文件已删除，仅恢复记录」。
     */
    fun undoDelete(deletionId: String): DeletedTask? {
        val deleted = deletedSnapshots.remove(deletionId) ?: return null
        _tasks.update { TransferTaskDeletion.restore(it, deleted) }
        persistTask(deleted.task, publishedFiles[taskKey(deleted.task.sessionId, deleted.task.file.fileId)]?.toString())
        return deleted
    }

    /** 撤销窗口结束：丢弃快照，记录不再恢复。 */
    fun commitDelete(deletionId: String) {
        deletedSnapshots.remove(deletionId)
    }

    // ─────────────────────────── 内部工具 ───────────────────────────

    private fun createIncomingSession(
        senderDeviceId: String,
        senderAlias: String,
        files: List<TransferFile>,
    ): IncomingSession {
        val session = IncomingSession(
            sessionId = UUID.randomUUID().toString().replace("-", "").take(16),
            token = TransferSettings.generatePairingKey().take(32),
            senderDeviceId = senderDeviceId,
            senderAlias = senderAlias,
            files = files.associateTo(mutableMapOf()) { it.fileId to IncomingFile(it) },
            state = TransferStatus.OFFERED,
            createdAtMs = System.currentTimeMillis(),
        )
        sessions[session.sessionId] = session
        files.forEach {
            upsertTask(
                TransferTask(
                    sessionId = session.sessionId,
                    peer = PeerDevice(
                        deviceId = senderDeviceId,
                        alias = senderAlias,
                        deviceType = DeviceTypes.MAC,
                        hostAddress = "",
                        port = 0,
                    ),
                    direction = TransferDirection.RECV,
                    file = it,
                    status = TransferStatus.OFFERED,
                    updatedAtMs = System.currentTimeMillis(),
                    createdAtMs = System.currentTimeMillis(),
                ),
            )
        }
        return session
    }

    private fun approve(session: IncomingSession, persistedPairKey: String?): ApiResponse {
        // 续传：已存在的 `.part` 实际大小即已收偏移
        session.files.values.forEach { incoming ->
            incoming.received = resumableOffset(incoming)
        }
        session.state = TransferStatus.TRANSFERRING
        session.pin = null
        session.lastProgressAtMs = System.currentTimeMillis()
        if (_pinPrompt.value?.sessionId == session.sessionId) _pinPrompt.value = null
        refreshSessionTasks(session)
        return approvedResponse(session, persistedPairKey)
    }

    private fun approvedResponse(session: IncomingSession, pairingKey: String?): ApiResponse =
        ApiResponse.ok(
            JSONObject().apply {
                put("sessionId", session.sessionId)
                put("token", session.token)
                put("state", TransferStatus.TRANSFERRING.wire)
                put("accepted", acceptedArray(session))
                if (pairingKey != null) put("pairingKey", pairingKey)
            },
        )

    private fun pendingResponse(session: IncomingSession, message: String): ApiResponse =
        ApiResponse.ok(
            JSONObject().apply {
                put("sessionId", session.sessionId)
                put("token", session.token)
                put("state", TransferStatus.AWAITING_CONFIRM.wire)
                put("accepted", JSONArray())
                if (message.isNotBlank()) put("message", message)
            },
        )

    private fun acceptedArray(session: IncomingSession): JSONArray = JSONArray().apply {
        session.files.values.forEach { incoming ->
            put(
                JSONObject().apply {
                    put("fileId", incoming.file.fileId)
                    // 与对端（Mac UploadPlanner.resumableOffset）同源：一律取 `.part` 实际大小
                    put("offset", resumableOffset(incoming))
                },
            )
        }
    }

    /**
     * 速度估算：本次增量 / 时间差，再与上次值做 3:1 指数平滑（避免进度条数字乱跳）。
     *
     * 口径落在 [ProgressSpeed]（纯函数，可纯 JVM 单测）：进度被节流后，
     * 采样时间差随之变大，速度仍然是「真实字节差 / 真实时间差」，不会变 0 或跳变。
     */
    private fun speedFor(previous: TransferTask, received: Long, nowMs: Long): Long =
        ProgressSpeed.bytesPerSecond(
            previousReceived = previous.receivedBytes,
            previousBps = previous.bytesPerSecond,
            previousAtMs = previous.updatedAtMs,
            received = received,
            nowMs = nowMs,
        )

    /** 接收目录占用（`Downloads/局域网互传/` 的文件数与总大小）。 */
    fun receivedStats(): DownloadsSink.ReceivedStats = downloads.stats()

    /**
     * 清理接收目录并清空对应历史：删除 `Downloads/局域网互传/` 下的全部文件记录，
     * 同时移除「接收 + 已完成」的任务行（失败/取消记录保留，其 `.part` 仍在缓存目录）。
     *
     * @return 实际删除的文件条数。
     */
    fun clearReceivedHistory(): Int {
        val removed = downloads.clearAll()
        publishedFiles.clear()
        scope.launch {
            runCatching { taskStore.deleteReceived() }
                .onFailure { Log.w(TAG, "接收历史清空失败: ${it.message}") }
        }
        _tasks.update { list ->
            list.filterNot { it.direction == TransferDirection.RECV && it.status == TransferStatus.COMPLETED }
        }
        return removed
    }

    /**
     * 续传偏移：以 `.part` 实际大小为准（协议 §6 断点续传）。
     *
     * 接收端进程被系统回收后重启，会话内存里的 `received` 是 0，但磁盘 `.part` 已有部分字节；
     * 若按内存值应答 `offset=0`，发送端会从头传、随后被严格校验打成 4003，续传能力丢失。
     *
     * - `.part` 不存在 → 0；
     * - 长度在 `0..file.size` → 该长度；
     * - 长度超出声明大小 → 脏数据，删除后按 0；
     * - 已收满（`received >= size`）→ 返回 size（此时 `.part` 已落盘并删除，不能回落成 0）。
     */
    private fun resumableOffset(incoming: IncomingFile): Long {
        val size = incoming.file.size
        if (size > 0L && incoming.received >= size) return size
        val part = StreamCopier.partFile(downloads.partDir(), incoming.file.fileId)
        incoming.part = part
        if (!part.exists()) return 0L
        val length = part.length()
        if (length <= size) return length
        part.delete()
        return 0L
    }

    private fun scheduleConfirmTimeout(session: IncomingSession) {
        scope.launch {
            delay(confirmTimeoutMs)
            if (session.state == TransferStatus.AWAITING_CONFIRM &&
                System.currentTimeMillis() >= session.confirmDeadlineMs
            ) {
                session.state = TransferStatus.FAILED
                session.pin = null
                // 先落任务终态、再收起弹层：外部（UI / 测试）看到弹层消失时任务一定已经不在「进行中」，
                // 不会出现「弹层没了、卡片还显示等待确认」的中间态。
                // 超时只静默收起 PIN 弹层：不播失败音、不震（避免打扰）
                refreshSessionTasks(session, notifyFailure = false)
                if (_pinPrompt.value?.sessionId == session.sessionId) _pinPrompt.value = null
                Log.i(TAG, "会话 ${session.sessionId} 确认超时（${confirmTimeoutMs}ms）")
            }
        }
    }

    private fun pruneExpiredSessions() {
        val now = System.currentTimeMillis()
        val expired = sessions.values.filter {
            it.state.isTerminal && now - it.createdAtMs > SESSION_KEEP_MS
        }
        expired.forEach { sessions.remove(it.sessionId) }
    }

    private fun refreshSessionTasks(session: IncomingSession, notifyFailure: Boolean = true) {
        session.files.values.forEach { incoming ->
            patchTask(session.sessionId, incoming.file.fileId) {
                it.copy(
                    status = session.state,
                    receivedBytes = incoming.received,
                    errorCode = if (session.state == TransferStatus.FAILED) {
                        session.failureCode ?: TransferErrorCode.CONFIRM_TIMEOUT
                    } else {
                        null
                    },
                    message = if (session.state == TransferStatus.FAILED) {
                        session.failureMessage.ifBlank { session.state.wire }
                    } else {
                        session.state.wire
                    },
                    updatedAtMs = System.currentTimeMillis(),
                )
            }
        }
        if (notifyFailure &&
            (session.state == TransferStatus.FAILED || session.state == TransferStatus.DENIED)
        ) {
            playSound(TransferSoundPlayer.Event.FAILURE)
        }
    }

    private fun parseFiles(array: JSONArray): List<TransferFile>? {
        val files = mutableListOf<TransferFile>()
        for (i in 0 until array.length()) {
            val json = array.optJSONObject(i) ?: return null
            val fileId = json.optString("fileId", "")
            val name = json.optString("name", "")
            val size = json.optLong("size", -1L)
            val sha = json.optString("sha256", "")
            val mime = json.optString("mime", "application/octet-stream")
            if (fileId.isBlank() || name.isBlank() || size < 0L) return null
            files += TransferFile(fileId, name, size, sha, mime)
        }
        return files
    }

    private fun buildOfferJson(sources: List<SendSource>, pin: String? = null): String =
        JSONObject().apply {
            put("senderDeviceId", settings.deviceId)
            put("senderAlias", settings.alias)
            put(
                "files",
                JSONArray().apply {
                    sources.forEachIndexed { index, source ->
                        put(
                            JSONObject().apply {
                                put("fileId", "f${index + 1}")
                                put("name", source.name)
                                put("size", source.size)
                                put("sha256", source.sha256)
                                put("mime", source.mime)
                            },
                        )
                    }
                },
            )
            if (pin != null) put("pin", pin)
        }.toString()

    private suspend fun readSource(uri: Uri): SendSource? = withContext(Dispatchers.IO) {
        val resolver = appContext.contentResolver
        var name = ""
        var size = -1L
        runCatching {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (nameIndex >= 0) name = cursor.getString(nameIndex) ?: ""
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) size = cursor.getLong(sizeIndex)
                }
            }
        }.onFailure { Log.w(TAG, "查询文件信息失败: ${it.message}") }
        if (size < 0L) {
            runCatching {
                resolver.openFileDescriptor(uri, "r")?.use { size = it.statSize }
            }
        }
        if (size < 0L) return@withContext null
        val mime = runCatching { resolver.getType(uri) }.getOrNull()
            ?.takeIf { it.isNotBlank() } ?: "application/octet-stream"
        val sha = runCatching {
            resolver.openInputStream(uri)?.use { input -> StreamCopier.sha256Of(input) }
        }.getOrNull() ?: return@withContext null
        SendSource(
            uri = uri,
            name = name.ifBlank { uri.lastPathSegment ?: "未命名文件" },
            size = size,
            mime = mime,
            sha256 = sha,
        )
    }

    private fun readAccepted(api: ApiResponse): Map<String, Long> {
        val array = api.data.optJSONArray("accepted") ?: return emptyMap()
        val result = mutableMapOf<String, Long>()
        for (i in 0 until array.length()) {
            val json = array.optJSONObject(i) ?: continue
            val fileId = json.optString("fileId", "")
            if (fileId.isNotBlank()) result[fileId] = json.optLong("offset", 0L)
        }
        return result
    }

    private fun skipFully(input: InputStream, offset: Long) {
        var remaining = offset
        val scratch = ByteArray(8 * 1024)
        while (remaining > 0L) {
            val skipped = input.skip(remaining)
            if (skipped > 0L) {
                remaining -= skipped
                continue
            }
            val read = input.read(scratch, 0, minOf(scratch.size.toLong(), remaining).toInt())
            if (read <= 0) throw IOException("源文件比预期短，无法从 offset=$offset 续传")
            remaining -= read
        }
    }

    private fun freeBytes(): Long = runCatching {
        StatFs(appContext.cacheDir.absolutePath).availableBytes
    }.getOrDefault(Long.MAX_VALUE)

    private fun appVersion(): String = BuildConfig.VERSION_NAME

    private fun playSound(event: TransferSoundPlayer.Event) {
        TransferSoundPlayer.play(appContext, event, settings, hapticTarget?.get())
    }

    private fun upsertTask(task: TransferTask) {
        _tasks.update { current ->
            val index = current.indexOfFirst {
                it.sessionId == task.sessionId && it.file.fileId == task.file.fileId
            }
            if (index < 0) {
                maybePersist(previous = null, updated = task)
                listOf(task) + current
            } else {
                val previous = current[index]
                maybePersist(previous = previous, updated = task)
                current.toMutableList().also { it[index] = task }
            }
        }
    }

    private fun patchTask(
        sessionId: String,
        fileId: String,
        transform: (TransferTask) -> TransferTask,
    ) {
        _tasks.update { current ->
            val index = current.indexOfFirst {
                it.sessionId == sessionId && it.file.fileId == fileId
            }
            if (index < 0) {
                current
            } else {
                val previous = current[index]
                val updated = transform(previous)
                maybePersist(previous = previous, updated = updated)
                current.toMutableList().also { it[index] = updated }
            }
        }
    }

    /**
     * 任务历史落库：**首次出现**与**进入终态**各写一次，进度推进不写库
     * （64 KiB 一块的进度每块写一次 SQLite 不划算，重启后未完成的任务按失败处理）。
     */
    private fun maybePersist(previous: TransferTask?, updated: TransferTask) {
        val firstSeen = previous == null
        val becameTerminal = updated.status.isTerminal && (previous == null || !previous.status.isTerminal)
        if (!firstSeen && !becameTerminal) return
        val uri = publishedFiles[taskKey(updated.sessionId, updated.file.fileId)]?.toString()
        persistTask(updated, uri)
    }

    private fun persistTask(task: TransferTask, fileUri: String?) {
        scope.launch {
            runCatching { taskStore.upsert(TransferTaskStore.Entry(task = task, fileUri = fileUri)) }
                .onFailure { Log.w(TAG, "任务历史写库失败: ${it.message}") }
        }
    }

    /**
     * 启动时加载历史（终态保留；崩溃残留的非终态按失败展示）。
     *
     * 非终态里「按同一超时口径已经卡死」的发送任务直接落 FAILED(4007)，
     * 否则每次重启都会在「进行中」看到同一批陈旧僵尸（真机 Bug：卡了两小时以上）。
     */
    internal suspend fun loadPersistedHistory() {
        val entries = runCatching { withContext(Dispatchers.IO) { taskStore.loadRecent() } }
            .onFailure { Log.w(TAG, "任务历史读取失败: ${it.message}") }
            .getOrDefault(emptyList())
        if (entries.isEmpty()) return
        val now = System.currentTimeMillis()
        val restored = entries.map { entry ->
            val task = entry.task
            if (task.status.isTerminal) {
                task
            } else {
                val reason = SendTaskTimeoutPolicy.reasonOf(
                    task = task,
                    nowMs = now,
                    confirmTimeoutMs = confirmTimeoutMs,
                    noProgressTimeoutMs = noProgressTimeoutMs,
                )
                task.copy(
                    status = TransferStatus.FAILED,
                    errorCode = if (reason != null) {
                        TransferErrorCode.CONFIRM_TIMEOUT
                    } else {
                        TransferErrorCode.SESSION_EXPIRED
                    },
                    message = reason?.message ?: "上次未完成",
                    updatedAtMs = now,
                )
            }
        }
        _tasks.update { current ->
            val existing = current.map { it.sessionId to it.file.fileId }.toSet()
            restored.filterNot { (it.sessionId to it.file.fileId) in existing } + current
        }
        entries.forEach { entry ->
            val uri = entry.fileUri?.let { runCatching { it.toUri() }.getOrNull() }
            if (uri != null) publishedFiles[taskKey(entry.task.sessionId, entry.task.file.fileId)] = uri
        }
    }

    /**
     * 仅供测试：立即跑一次超时收敛（生产由 [tickerLoop] 每 tick 调用、`offerStatus` 顺带触发）。
     */
    internal fun enforceTimeoutsForTesting() {
        enforceTimeouts()
    }

    /**
     * 仅供测试：注入一条发送任务行，并按需登记对应的 [outgoing] 条目
     * （用于验证「超时收敛 → 任务落 FAILED 且条目被摘除」）。
     */
    internal fun injectSendTaskForTesting(task: TransferTask, trackOutgoing: Boolean = true) {
        upsertTask(task)
        if (trackOutgoing) {
            outgoing[task.sessionId] = OutgoingSession(
                peer = task.peer,
                remoteSessionId = "remote-${task.sessionId}",
                token = "test-token",
                sources = emptyList(),
            )
        }
    }

    /** 仅供测试：当前登记中的发送会话条目数。 */
    internal fun outgoingSessionCountForTesting(): Int = outgoing.size

    private fun renameTasks(fromSessionId: String, toSessionId: String) {
        _tasks.update { current ->
            current.map {
                if (it.sessionId == fromSessionId) it.copy(sessionId = toSessionId) else it
            }
        }
    }

    private fun failTask(sessionId: String, fileId: String, code: Int, message: String) {
        patchTask(sessionId, fileId) {
            it.copy(
                status = TransferStatus.FAILED,
                errorCode = code,
                message = message,
                updatedAtMs = System.currentTimeMillis(),
            )
        }
    }

    private fun failAll(sessionId: String, code: Int, message: String) {
        var failedAny = false
        _tasks.update { current ->
            current.map {
                if (it.sessionId == sessionId && it.isActive) {
                    failedAny = true
                    it.copy(
                        status = TransferStatus.FAILED,
                        errorCode = code,
                        message = message,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                } else {
                    it
                }
            }
        }
        if (failedAny) playSound(TransferSoundPlayer.Event.FAILURE)
    }

    // ─────────────────────────── 内部模型 ───────────────────────────

    private class IncomingSession(
        val sessionId: String,
        val token: String,
        val senderDeviceId: String,
        val senderAlias: String,
        val files: MutableMap<String, IncomingFile>,
        var state: TransferStatus,
        val createdAtMs: Long,
    ) {
        var pin: String? = null
        var confirmDeadlineMs: Long = System.currentTimeMillis() + CONFIRM_TIMEOUT_MS

        /** 最近一次字节进展时刻（「长时间无进展」判定）。 */
        var lastProgressAtMs: Long = createdAtMs

        /** 失败错误码 / 文案（供任务行展示）。 */
        var failureCode: Int? = null
        var failureMessage: String = ""
    }

    private class IncomingFile(val file: TransferFile) {
        var received: Long = 0L
        var part: File? = null
    }

    /**
     * 一次发送会话（登记在 [outgoing]，键为**本端任务行**的 `sessionId`）。
     *
     * [remoteSessionId] 是对端 `/offer` 返回的会话 id，只用于 HTTP（upload / cancel）；
     * 两者分开是为了让进度回写、超时收敛与任务行始终是同一个键。
     */
    private class OutgoingSession(
        val peer: PeerDevice,
        val remoteSessionId: String,
        val token: String,
        val sources: List<SendSource>,
    )

    private class PendingSend(
        val peer: PeerDevice,
        val sources: List<SendSource>,
        val sessionId: String,
    )

    private class SendSource(
        val uri: Uri,
        val name: String,
        val size: Long,
        val mime: String,
        val sha256: String,
    )

    /** PIN 交互（接收端展示 / 发送端输入）。 */
    data class PinPrompt(
        val sessionId: String,
        val peerAlias: String,
        val direction: TransferDirection,
        /** 接收端生成待核对的 4 位 PIN；发送端为 null（等待输入）。 */
        val pin: String?,
        val fileName: String,
        val message: String = "",
        /** 接收端确认截止时刻（会话 `confirmDeadlineMs`）；发送端为 null。 */
        val expiresAtMs: Long? = null,
    )

    private val sessions = ConcurrentHashMap<String, IncomingSession>()
    private val outgoing = ConcurrentHashMap<String, OutgoingSession>()

    /** 已落盘的接收文件：`sessionId#fileId` → MediaStore uri（用户删除记录时一并删）。 */
    private val publishedFiles = ConcurrentHashMap<String, Uri>()

    /** 撤销窗口内的删除快照：`deletionId` → 快照。 */
    private val deletedSnapshots = ConcurrentHashMap<String, DeletedTask>()

    @Volatile
    private var pendingSend: PendingSend? = null

    private fun taskKey(sessionId: String, fileId: String): String = "$sessionId#$fileId"

    companion object {
        private const val TAG = "TransferEngine"
        private const val TokenHeader = "X-Transfer-Token"
        private const val PairKeyHeader = "X-Pair-Key"
        private const val DEVICE_TICK_MS = 5_000L
        private const val SESSION_KEEP_MS = 5 * 60_000L
        private const val DISK_MARGIN_BYTES = 16L * 1024 * 1024

        /** 删除后的撤销窗口（UI Snackbar 计时，与引擎快照寿命一致）。 */
        const val UNDO_WINDOW_MS: Long = 5_000L

        /** 通知「暂停 5 分钟」的默认时长。 */
        const val PAUSE_MINUTES = 5

        private const val DELETED_EVENT_BUFFER = 16

        /** 「自动关闭接收」倒计时的检查间隔上限（到点判定误差 ≤ 1s）。 */
        private const val AUTO_CLOSE_TICK_MS = 1_000L

        /** 自动关闭事件的缓冲（宿主服务常驻收集，1 已足够不丢）。 */
        private const val AUTO_CLOSE_EVENT_BUFFER = 1

        @Volatile
        private var instance: TransferEngine? = null

        /** 进程内单例。 */
        fun getInstance(context: Context): TransferEngine =
            instance ?: synchronized(this) {
                instance ?: TransferEngine(context.applicationContext).also { instance = it }
            }

        /** 仅供测试：注入更短的确认超时验证 4007（默认走协议 60s）。 */
        internal fun createForTesting(
            context: Context,
            confirmTimeoutMs: Long = CONFIRM_TIMEOUT_MS,
            noProgressTimeoutMs: Long = NO_PROGRESS_TIMEOUT_MS,
            tickMs: Long = DEVICE_TICK_MS,
        ): TransferEngine =
            TransferEngine(context.applicationContext, confirmTimeoutMs, noProgressTimeoutMs, tickMs)
        /**
         * 单例复位（仅供测试）。
         *
         * Robolectric 每个用例会新建 Application，而单例持有的是首个用例的 context；
         * 用例间复用会读到已释放的缓存目录与设置。与 `FinanceDatabaseHelper.resetForTesting()` 同风格。
         */
        @Synchronized
        fun resetForTesting() {
            instance?.let { runCatching { it.stop() } }
            instance = null
        }
    }
}
