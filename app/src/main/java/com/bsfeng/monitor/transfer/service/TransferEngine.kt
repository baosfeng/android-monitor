package com.bsfeng.monitor.transfer.service

import android.content.Context
import android.net.Uri
import android.os.StatFs
import android.provider.OpenableColumns
import android.util.Log
import android.view.View
import com.bsfeng.monitor.BuildConfig
import com.bsfeng.monitor.domain.transfer.CONFIRM_TIMEOUT_MS
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
import com.bsfeng.monitor.transfer.discovery.DeviceRegistry
import com.bsfeng.monitor.transfer.discovery.UdpDiscoveryService
import com.bsfeng.monitor.transfer.transport.ApiResponse
import com.bsfeng.monitor.transfer.transport.DownloadsSink
import com.bsfeng.monitor.transfer.transport.MiniHttpClient
import com.bsfeng.monitor.transfer.transport.MiniHttpServer
import com.bsfeng.monitor.transfer.transport.StreamCopier
import com.bsfeng.monitor.transfer.transport.TransferRoutes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
) : TransferRoutes {

    private val settings = TransferSettings(appContext)
    private val registry = DeviceRegistry()
    private val downloads = DownloadsSink(appContext)
    private val client = MiniHttpClient()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val started = AtomicBoolean(false)

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
                _running.value = true
                Log.i(TAG, "传送服务已启动，HTTP 端口 $boundPort")
            } else {
                started.set(false)
            }
        }
        return localPort
    }

    /** 停止发现与服务，清理在途会话（保留 `.part` 供下次续传）。 */
    fun stop() {
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
     * 「可被发现」开关：关闭后停止 UDP 公告与应答（对端列表随之清空），HTTP 端口继续常驻，
     * 已配对设备仍可直接按地址发送。
     */
    fun setDiscoverable(enabled: Boolean) {
        settings.discoverable = enabled
        _discoverable.value = enabled
        if (!started.get()) return
        val udp = discovery
        if (enabled &&
            ServiceModePolicy.shouldRunDiscovery(settings.backgroundReceive, appVisible, discoverable = true)
        ) {
            if (udp != null && !udp.isRunning()) udp.start(scope)
        } else {
            udp?.stop()
            discovery = null
            refreshDevices()
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
            delay(DEVICE_TICK_MS)
            refreshDevices()
            pruneExpiredSessions()
        }
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
        val result = try {
            FileOutputStream(part, append).use { output ->
                StreamCopier.copy(
                    input = body,
                    output = output,
                    expectedTotal = writeLength,
                    onChunk = { copied ->
                        val received = targetOffset + copied
                        incoming.received = received
                        patchTask(sessionId, fileId) {
                            val now = System.currentTimeMillis()
                            it.copy(
                                receivedBytes = received,
                                bytesPerSecond = speedFor(it, received, now),
                                updatedAtMs = now,
                            )
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
            return@withContext ApiResponse.error(TransferErrorCode.SHA256_MISMATCH)
        }
        val displayName = StreamCopier.uniqueFileName(
            incoming.file.name,
            downloads.existingNames()::contains,
        )
        val uri = downloads.createPending(displayName, incoming.file.mime)
            ?: return@withContext ApiResponse.error(TransferErrorCode.DISK_FULL, "无法创建目标文件")
        val output = downloads.openOutput(uri)
            ?: return@withContext ApiResponse.error(TransferErrorCode.DISK_FULL, "无法打开目标文件")
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
                outgoing[remoteSessionId] = OutgoingSession(peer, token, sources)
                uploadAll(remoteSessionId, peer, sources, token, readAccepted(api))
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
            outgoing[pending.sessionId] = OutgoingSession(pending.peer, token, pending.sources)
            uploadAll(pending.sessionId, pending.peer, pending.sources, token, readAccepted(api))
        }
    }

    private suspend fun uploadAll(
        sessionId: String,
        peer: PeerDevice,
        sources: List<SendSource>,
        token: String,
        offsets: Map<String, Long>,
    ) {
        sources.forEachIndexed { index, source ->
            val fileId = "f${index + 1}"
            val offset = offsets[fileId] ?: 0L
            patchTask(sessionId, fileId) {
                it.copy(status = TransferStatus.TRANSFERRING, receivedBytes = offset)
            }
            val ok = uploadWithRetry(sessionId, peer, source, fileId, offset, token)
            if (!ok) {
                outgoing.remove(sessionId)
                return
            }
        }
        playSound(TransferSoundPlayer.Event.COMPLETE)
        outgoing.remove(sessionId)
    }

    private suspend fun uploadWithRetry(
        sessionId: String,
        peer: PeerDevice,
        source: SendSource,
        fileId: String,
        offset: Long,
        token: String,
    ): Boolean {
        var attempt = 0
        while (true) {
            val outcome = runCatching {
                val url = MiniHttpClient.buildUrl(
                    peer.baseUrl,
                    "upload",
                    listOf("session" to sessionId, "file" to fileId, "offset" to offset.toString()),
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
                            patchTask(sessionId, fileId) {
                                val now = System.currentTimeMillis()
                                val received = offset + progress
                                it.copy(
                                    receivedBytes = received,
                                    bytesPerSecond = speedFor(it, received, now),
                                    status = TransferStatus.TRANSFERRING,
                                    updatedAtMs = now,
                                )
                            }
                        },
                    )
                }
                parseUploadResponse(sessionId, fileId, source, sent)
            }
            val success = outcome.getOrElse { e ->
                Log.w(TAG, "上传 $fileId 失败: ${e.message}")
                false
            }
            if (success) {
                patchTask(sessionId, fileId) {
                    it.copy(
                        status = TransferStatus.COMPLETED,
                        receivedBytes = source.size,
                        bytesPerSecond = 0L,
                        updatedAtMs = System.currentTimeMillis(),
                    )
                }
                return true
            }
            if (attempt >= RETRY_DELAYS_MS.size) {
                failTask(sessionId, fileId, TransferErrorCode.SESSION_EXPIRED, "断网重试 3 次仍失败")
                playSound(TransferSoundPlayer.Event.FAILURE)
                return false
            }
            delay(RETRY_DELAYS_MS[attempt])
            attempt++
        }
    }

    private fun parseUploadResponse(
        sessionId: String,
        fileId: String,
        source: SendSource,
        response: MiniHttpClient.HttpResponse,
    ): Boolean {
        val api = response.api
        if (api == null || !api.isOk) {
            if (api != null && api.code == TransferErrorCode.SHA256_MISMATCH) {
                failTask(sessionId, fileId, api.code, api.message)
                return false
            }
            Log.w(TAG, "上传响应异常：HTTP ${response.httpCode} ${response.body.take(120)}")
            return false
        }
        val remoteSha = api.string("sha256")
        if (remoteSha.isNotBlank() && !remoteSha.equals(source.sha256, ignoreCase = true)) {
            failTask(sessionId, fileId, TransferErrorCode.SHA256_MISMATCH, "sha256 不一致")
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
                            listOf("session" to sessionId),
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
     * 终态任务不推进度，UI 只显示总体积，因此这里不需要归零逻辑。
     */
    private fun speedFor(previous: TransferTask, received: Long, nowMs: Long): Long {
        val deltaTime = nowMs - previous.updatedAtMs
        if (deltaTime <= 0L) return previous.bytesPerSecond
        val deltaBytes = received - previous.receivedBytes
        if (deltaBytes <= 0L) return previous.bytesPerSecond
        val instant = deltaBytes * 1_000L / deltaTime
        return if (previous.bytesPerSecond <= 0L) instant else (previous.bytesPerSecond * 3 + instant) / 4
    }

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
                if (_pinPrompt.value?.sessionId == session.sessionId) _pinPrompt.value = null
                // 超时只静默收起 PIN 弹层：不播失败音、不震（避免打扰）
                refreshSessionTasks(session, notifyFailure = false)
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
                        TransferErrorCode.CONFIRM_TIMEOUT
                    } else {
                        null
                    },
                    message = session.state.wire,
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
                listOf(task) + current
            } else {
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
                current.toMutableList().also { it[index] = transform(it[index]) }
            }
        }
    }

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
    }

    private class IncomingFile(val file: TransferFile) {
        var received: Long = 0L
        var part: File? = null
    }

    private class OutgoingSession(
        val peer: PeerDevice,
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
        ): TransferEngine = TransferEngine(context.applicationContext, confirmTimeoutMs)

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
