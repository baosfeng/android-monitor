package com.bsfeng.monitor.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.TriggerEvent
import android.hardware.TriggerEventListener
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.bsfeng.monitor.MainActivity
import com.bsfeng.monitor.core.di.serviceContainer
import com.bsfeng.monitor.feature.Feature
import com.bsfeng.monitor.feature.FeatureFlags
import com.bsfeng.monitor.record.FixBudget
import com.bsfeng.monitor.record.LocationCollector
import com.bsfeng.monitor.record.LocationGateState
import com.bsfeng.monitor.record.LocationPace
import com.bsfeng.monitor.record.LocationSample
import com.bsfeng.monitor.record.LocationTickerPolicy
import com.bsfeng.monitor.record.LocationTickerScheduler
import com.bsfeng.monitor.record.MotionDetector
import com.bsfeng.monitor.record.MotionGate
import com.bsfeng.monitor.record.NetworkFingerprint
import com.bsfeng.monitor.record.NetworkStillnessTracker
import com.bsfeng.monitor.record.NetworkTransport
import com.bsfeng.monitor.record.TickerHost
import kotlinx.coroutines.launch

/**
 * 定位采集的前台服务（`foregroundServiceType="location"`）。
 *
 * 为什么必须要有它：真机实测（HyperOS / Android 16）一天有 43 次 >6 分钟的断档，
 * 全天每小时都在漏拍 —— 进程在 App Standby / Doze 下被降频，**不是采不到点，是不给醒**。
 * Google Maps / Strava / Traccar / OwnTracks 全都用 location 前台服务承载持续定位，
 * 这块牌子换来的是进程不被冻结；普通后台进程拿不到同等优先级。
 *
 * 三条平台约束（改之前先读）：
 * - **未获 ACCESS_BACKGROUND_LOCATION 时，后台创建 location 前台服务会抛 SecurityException**
 *   （Android 14+）。这种机型前台服务救不了，[enterForeground] 失败即退出，心跳兜底仍在；
 * - targetSdk ≥ 34 必须声明 `FOREGROUND_SERVICE_LOCATION` 权限，否则 `startForeground` 直接抛；
 * - **只能从可见状态启动**（Android 12+ 后台启动 FGS 受限），所以入口是 [MainActivity.onResume]，
 *   不能从 [AlarmReceiver] 的心跳里拉它。
 *
 * 定位采集的职责在这里而不在 [MonitorAccessibilityService]：两处都挂 10 秒 ticker 会互相
 * 抢节流窗口，实测同一点被落库两条（历史库里 2176 条有 1446 条重复）。
 *
 * **零功耗门禁**（见 [MotionGate]）：订阅只由「确认远行」这一件事打开。
 * - SLEEPING / PROBING 一律 `stopSubscription()`：没有确认远行就绝不建订阅，
 *   否则定位芯片会跟着「拿起手机」一直不休眠 —— 用户抱怨的耗电来源；
 * - 传感器按能力降级：显著运动（一次性、硬件 hub）常驻；没有它才退化为加速度计常开 + 30s batching；
 *   加速度计只在观察窗内以 SENSOR_DELAY_UI 注册（显著运动 / 抖动提示后 3 分钟），
 *   SLEEPING 平时不注册 —— 提示只买一个「判出 GAIT 的机会」，不是活动证据。
 * - **网络环境静止证据**（见 [NetworkStillnessTracker]）：默认网络的 transport + SSID 未变、且没有
 *   步数增量时，SLEEPING 兜底从 30 分钟放宽到 60 分钟；换网络 / 断网 / 有步数立刻回 30 分钟。
 *   省电体现在「不发起定位请求」——没有新坐标就没有新记录，**不用旧坐标伪造点位**。
 */
class LocationForegroundService : Service() {

    private val handler = Handler(Looper.getMainLooper())

    /** 点位落库协程宿主：onDestroy 统一取消，避免服务停掉后仍在写库。 */
    private val ioWork = IoWorkScope()

    private val sensorManager: SensorManager? by lazy {
        runCatching { getSystemService(Context.SENSOR_SERVICE) as? SensorManager }.getOrNull()
    }

    private val accelerometer: Sensor? by lazy {
        runCatching { sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER) }.getOrNull()
    }

    /** 显著运动：一次性硬件触发，功耗≈0；有它就不用加速度计常开。 */
    private val significantMotionSensor: Sensor? by lazy {
        runCatching { sensorManager?.getDefaultSensor(Sensor.TYPE_SIGNIFICANT_MOTION) }.getOrNull()
    }

    /** 硬件累计步数：远行证据最便宜、最准的一项。 */
    private val stepCounterSensor: Sensor? by lazy {
        runCatching { sensorManager?.getDefaultSensor(Sensor.TYPE_STEP_COUNTER) }.getOrNull()
    }

    /**
     * 硬件运动检测（API 29，一次性触发）：设备从静止变为移动。
     *
     * 跑在 sensor hub 上、功耗≈0，只在**状态变化**时触发 —— 用它替代「每 180 秒定时探测」
     * 是这轮省电改造的核心（见 MotionGate.onMotionDetect 的 KDoc）。
     */
    private val motionDetectSensor: Sensor? by lazy {
        runCatching { sensorManager?.getDefaultSensor(Sensor.TYPE_MOTION_DETECT) }.getOrNull()
    }

    /** 硬件静止检测（API 29，一次性触发）：设备从移动变为静止，触发后门禁直接回 SLEEPING。 */
    private val stationaryDetectSensor: Sensor? by lazy {
        runCatching { sensorManager?.getDefaultSensor(Sensor.TYPE_STATIONARY_DETECT) }.getOrNull()
    }

    /** 运动判定：只作为门禁的「静止 / 步态」证据与诊断（见 [MotionDetector] 的 KDoc）。 */
    private val motion = MotionDetector()

    /** 定位功耗门禁：三态 + 升级/降级判据（见 [MotionGate] 的 KDoc）。 */
    private val gate = MotionGate()

    /** 三种加速度计注册形态：不注册 / 观察窗（16Hz）/ 退化常开（batching）。 */
    private enum class AccelMode { NONE, WINDOW, BATCHED }

    private var accelMode = AccelMode.NONE
    private var stepCounterRegistered = false
    private var significantRegistered = false
    private var motionDetectRegistered = false
    private var stationaryDetectRegistered = false

    /** 最近一次处理「运动 / 静止」硬件事件的时间：30 秒内的重复事件只认一次。 */
    private var lastMotionEventAtMs = 0L

    /** 运动 / 静止检测不可用的日志只打一次（syncSensors 每 30 秒跑一次）。 */
    private var motionDetectFailureLogged = false
    private var stationaryDetectFailureLogged = false

    /** 显著运动失败日志只打一次：syncSensors 每 30 秒跑一次，重复打会刷屏。 */
    private var significantFailureLogged = false

    /** 步数计失败日志同样只打一次。 */
    private var stepCounterFailureLogged = false

    /** 已经应用到采集器的门禁态；用于去重，避免每条传感器样本都重配订阅。 */
    @Volatile
    private var appliedGateState: MotionGate.State = MotionGate.State.SLEEPING

    /** 已经写给采集器的探测间隔（0 = 当前态不看这个值），用于「同态但退避档变了」的补同步。 */
    private var appliedProbeIntervalMs = 0L

    /**
     * 网络环境的静止证据：默认网络的 transport + SSID 未变、且无步数增量时，
     * SLEEPING 兜底从 30 分钟放宽到 60 分钟（见 [NetworkStillnessTracker]）。
     */
    private val networkStillness = NetworkStillnessTracker()

    /** 已经同步给 [networkStillness] 的活动证据时刻（去重：同一次步数只喂一次）。 */
    private var appliedActivityAtMs = 0L

    /** 是否充电中：充电时不必省电，SLEEPING 兜底回 30 分钟；断电立刻恢复 60 分钟。 */
    private var isCharging = false

    /**
     * 充电状态监听：断电瞬间就把兜底拉回 60 分钟，不用等下一拍或下一次兜底。
     *
     * 用广播而不是轮询 BatteryManager：插拔是事件，轮询要额外唤醒；只有启动时读一次初值。
     */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val charging = intent?.action == Intent.ACTION_POWER_CONNECTED
            if (charging == isCharging) return
            isCharging = charging
            Log.i(
                TAG,
                "充电状态变化：" + if (charging) "已接电源，兜底回 30 分钟" else "已断电，恢复 60 分钟兜底",
            )
            tickerScheduler.onStateChanged()
        }
    }

    private val connectivityManager: ConnectivityManager? by lazy {
        runCatching { getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager }.getOrNull()
    }

    /**
     * 默认网络回调：只在「当前是什么网络环境」可能变了时重新读一次，比对交给纯逻辑。
     *
     * 不在回调里直接算差异：onCapabilitiesChanged 会因带宽估计之类的原因频繁触发，
     * 真正的「换没换环境」由 [NetworkStillnessTracker.observeNetwork] 判定（未变则什么都不做）。
     */
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refreshNetworkFingerprint()

        override fun onLost(network: Network) = refreshNetworkFingerprint()

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
            refreshNetworkFingerprint(caps)
    }

    /** 观察窗到期时刻（wall clock）；0 = 没开窗。 */
    private var observingUntilMs = 0L

    /** 已经处理过的提示型唤醒时刻（显著运动 / 抖动）：同一次提示不重复延长观察窗。 */
    private var appliedHintAtMs = 0L

    /** 最近一次 SLEEPING 兜底探测的时刻。 */
    private var lastFallbackAtMs = 0L

    /** 最近一次 PROBING 单点探测的时刻。 */
    private var lastProbeAtMs = 0L

    /** 运动诊断日志的节流时刻（每 5 秒一条）。 */
    private var lastMotionLogAt = 0L

    /** 门禁证据日志的节流时刻（每 5 分钟一条）。 */
    private var lastEvidenceLogAt = 0L

    private val accelerometerListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
            runCatching { onAccelerometerSample(event) }
                .onFailure { Log.w(TAG, "运动判定异常", it) }
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    private val stepCounterListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            if (event.sensor.type != Sensor.TYPE_STEP_COUNTER) return
            val total = event.values.getOrElse(0) { 0f }.toLong()
            val now = System.currentTimeMillis()
            // 硬件步数优先：先标记，门禁会立刻关掉软件步数，避免同一次走路被两份计数各算一遍
            gate.markHardwareStepsAvailable()
            val state = gate.onStepCounter(total, now)
            applyGateState(state)
            syncSensors(now)
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    /**
     * 显著运动是 **trigger 类型**传感器：只能用 requestTriggerSensor，registerListener 会被框架拒绝
     * （真机日志：E/SensorManager: Trigger Sensors should use the requestTriggerSensor）。
     * 一次性触发后系统自动摘除，必须重新 request 才能再次唤醒 —— 所以回调末尾要重注册。
     */
    private val significantMotionTriggerListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            if (event.sensor.type != Sensor.TYPE_SIGNIFICANT_MOTION) return
            significantRegistered = false
            val now = System.currentTimeMillis()
            // 显著运动只开观察窗（16Hz 加速度计），**不改门禁态**：拿起手机就会触发，
            // 把它当活动证据会让「无步数无位移 5 分钟降级」永远不到点（见 MotionGate 的 KDoc）
            val state = gate.onSignificantMotion(now)
            applyObservationHint(now)
            applyGateState(state)
            syncSensors(now)
            Log.i(TAG, "门禁传感器：显著运动触发，开 " + (OBSERVE_WINDOW_MS / 1000) + "s 观察窗")
            // 重新待命：不重注册的话，这个人再走一整天也不会再有下一次唤醒
            requestSignificantMotion()
        }
    }

    /**
     * 硬件运动 / 静止检测（一次性触发，API 29）：触发即代表「状态换了」，门禁据此启停定时探测。
     *
     * 与显著运动的分工：显著运动只说明「手机被拿起来了」，不改门禁态；这两条是真正的
     * 「开始移动 / 已经静止」，可以直接当门禁证据。
     */
    private val motionDetectListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            if (event.sensor.type != Sensor.TYPE_MOTION_DETECT) return
            motionDetectRegistered = false
            val now = System.currentTimeMillis()
            // 30 秒节流：传感器的误触发会让门禁在 PROBING/SLEEPING 之间来回切，
            // 每次切换都要重排 ticker、重配传感器。真状态变化不会 30 秒内来回两次。
            if (now - lastMotionEventAtMs < MOTION_EVENT_MIN_GAP_MS) {
                syncTriggerSensors()
                return
            }
            lastMotionEventAtMs = now
            val state = gate.onMotionDetect(now)
            applyGateState(state)
            syncSensors(now)
            Log.i(TAG, "门禁传感器：硬件运动检测 → " + state + "（定时探测恢复）")
            // 不在这里直接重注册：由 syncTriggerSensors 按新态决定「该等哪一个」，
            // 否则静止态下会 5 秒一轮自触发（真机实测过这个循环）。
            syncTriggerSensors()
        }
    }

    private val stationaryDetectListener = object : TriggerEventListener() {
        override fun onTrigger(event: TriggerEvent) {
            if (event.sensor.type != Sensor.TYPE_STATIONARY_DETECT) return
            stationaryDetectRegistered = false
            val now = System.currentTimeMillis()
            if (now - lastMotionEventAtMs < MOTION_EVENT_MIN_GAP_MS) {
                syncTriggerSensors()
                return
            }
            lastMotionEventAtMs = now
            val state = gate.onStationaryDetect(now)
            applyGateState(state)
            syncSensors(now)
            Log.i(TAG, "门禁传感器：硬件静止检测 → " + state + "（定时探测停摆，只剩兜底与被动点）")
            syncTriggerSensors()
        }
    }

    private val ticker = object : Runnable {
        override fun run() {
            val versionAtTickStart = tickerScheduler.version
            runCatching { maybeSampleLocation() }
                .onFailure { Log.w(TAG, "定位补采异常", it) }
            // 本拍期间门禁若迁移过，业务里已经重排过一拍（代次变了），这里不能重复排，
            // 否则会多出一条 tick 链、唤醒次数翻倍。
            tickerScheduler.afterTick(versionAtTickStart)
        }
    }

    /** ticker 排程宿主：排一拍前先摘掉上一拍，保证任何时刻只有一拍在排。 */
    private val tickerHost: TickerHost = object : TickerHost {
        override fun schedule(delayMs: Long) {
            handler.removeCallbacks(ticker)
            handler.postDelayed(ticker, delayMs)
        }

        override fun cancel() {
            handler.removeCallbacks(ticker)
        }
    }

    /**
     * ticker 拍子：MOVING / PROBING 30 秒；SLEEPING 最长 5 分钟（那时唯一有意义的定时事件是
     * 30 分钟～60 分钟的兜底）。
     *
     * 拍子的时序（含「状态迁移立刻重排」）在 [LocationTickerScheduler] 里，纯逻辑可单测；
     * 这里只提供 Handler 宿主与三态 / 兜底时刻两个只读来源。
     */
    private val tickerScheduler: LocationTickerScheduler = LocationTickerScheduler(
        host = tickerHost,
        clock = { System.currentTimeMillis() },
        stateOf = { appliedGateState },
        lastFallbackAtMsOf = { lastFallbackAtMs },
        // 兜底间隔与「这一拍要不要补点」同源：两处各读一份，迟早出现「按 60 分钟排拍子、按 30 分钟采点」
        fallbackIntervalMsOf = {
            LocationTickerPolicy.sleepingFallbackIntervalMs(
                networkStillness.stillSinceMs,
                System.currentTimeMillis(),
                isCharging,
            )
        },
        log = { message -> Log.d(TAG, message) },
    )

    override fun onCreate() {
        super.onCreate()
        createChannel()
        // 门禁位移证据的唯一出口：心跳 / 断档自愈 / 订阅 / 探测拿到的点都从 LocationGateState 进来。
        // 回调线程不固定（传感器、广播 goAsync、定位回调），统一 post 到主线程再动 MotionGate，
        // 这样 MotionGate 保持纯逻辑、不需要锁。
        LocationGateState.registerLocationListener { sample, fromProbe ->
            handler.post { onGateEvidence(sample, fromProbe) }
        }
        // 网络环境监听不依赖前台身份，onCreate 就能注册（真机在 onStartCommand 之后注册会丢掉
        // 「启动时已经连着 WiFi」这条基线，第一次兜底就白白按 30 分钟走）
        registerNetworkCallback()
        // 充电状态：插电时不必省电（兜底回 30 分钟），断电立刻恢复 60 分钟
        registerPowerReceiver()
        // 传感器注册不在这里：onCreate 时还没 startForeground，进程没有前台优先级，
        // 部分 ROM（实测 HyperOS）会直接拒绝后台应用的传感器注册且不报错 ——
        // 表现为 sensorservice 的连接列表里根本没有本进程，运动识别永远收不到数据。
        // 注册放到 enterForeground() 成功之后（见 onStartCommand）。
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!FeatureFlags.get(this).isEnabled(Feature.LOCATION)) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (!enterForeground()) return START_NOT_STICKY
        // 已进入前台，此时注册传感器才不会被系统拒绝
        val now = System.currentTimeMillis()
        syncSensors(now)
        startTicker()
        // 首次进入必须强制应用一次门禁态：默认 SLEEPING 是「不定位」，
        // 不强制应用的话订阅会按采集器默认的 30s 一直跑（玩手机照样烧定位）
        applyGateState(gate.onTick(now), force = true)
        // 被动定位常驻：搭别的 App（导航 / 地图 / 健康）的便车，不点亮定位芯片。
        // 放在 enterForeground 之后：注册要过 location 权限与前台身份这两道。
        startPassiveTap()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopTicker()
        unregisterNetworkCallback()
        unregisterPowerReceiver()
        runCatching { LocationCollector.get(this).stopPassiveTap() }
            .onFailure { Log.w(TAG, "释放被动定位失败", it) }
        // 先取消落库协程：服务已经不在，再往库里写就是「幽灵写入」（还原 / 备份期间还会撞锁）
        ioWork.cancel()
        releaseSensors()
        runCatching { LocationCollector.get(this).stopSubscription() }
            .onFailure { Log.w(TAG, "释放位置订阅失败", it) }
        // 服务停了就没有门禁判断了：写回 null 让心跳恢复原行为，避免服务没跑时又漏采
        LocationGateState.update(null)
        LocationGateState.registerLocationListener(null)
        running = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    // ===== 传感器编排（按能力降级）=====

    /**
     * 按当前门禁态同步传感器注册：观察窗内开加速度计（16Hz）+ 步数计；
     * 否则优先只留显著运动（一次性硬件触发）；没有显著运动才退化为加速度计常开 + batching。
     *
     * 为什么必须在「无显著运动传感器」时 batching：加速度计常开是本设计里唯一无法避免的唤醒源，
     * `maxReportLatencyUs=30s` 让传感器 hub 攒样本、不再每条样本唤醒 CPU。
     */
    private fun syncSensors(nowMs: Long) {
        val moving = appliedGateState == MotionGate.State.MOVING
        val probing = appliedGateState == MotionGate.State.PROBING
        if (moving || probing) {
            // PROBING 也必须保持 16Hz 观察窗：步态判定要求 ≥16Hz（5Hz 判不出步态，
            // 软件步数永远为 0，升级只能靠位移）。功耗论证：16Hz 加速度计约 1–3mW，
            // 比 GPS 小两个数量级；PROBING 本身不建订阅，省电大头已经省掉。
            // MOVING 续窗是为了让「5 分钟无活动 → 降级」始终有证据可判。
            observingUntilMs = nowMs + OBSERVE_WINDOW_MS
        }
        val windowActive = moving || probing || observingUntilMs > nowMs
        if (!windowActive) observingUntilMs = 0L

        val significantReady = if (!windowActive) ensureSignificantMotion() else false
        val desiredAccel = when {
            windowActive -> AccelMode.WINDOW
            significantReady -> AccelMode.NONE
            else -> AccelMode.BATCHED
        }
        setAccelMode(desiredAccel)
        // 步数计在 PROBING 期间保持注册（口径：PROBING 每 30s 读一次增量）：
        // 只在观察窗里读的话，正常步行 3 分钟才 250m 左右，永远够不到 500m 的升级线；
        // 步数计是硬件累计值 + 30s batching，功耗远低于定位，PROBING 期间值得开着。
        setStepCounterActive(windowActive || appliedGateState == MotionGate.State.PROBING)
        syncTriggerSensors()
    }

    /**
     * 运动 / 静止检测**互斥待命**：静止态只等「开始移动」，移动态只等「停下来」。
     *
     * 为什么必须互斥：两者都是一次性触发，触发条件在整个状态期间一直成立。
     * 同时注册会 5 秒一轮自触发（真机实测：静止检测触发 → 立刻重注册 → 5 秒后再触发，
     * 每次都要唤醒 CPU，比原来的定时探测更糟）。互斥后，每一次触发都必然对应一次真实状态变化。
     */
    private fun syncTriggerSensors() {
        val moving = appliedGateState != MotionGate.State.SLEEPING
        if (moving) {
            cancelMotionDetect()
            requestStationaryDetect()
        } else {
            cancelStationaryDetect()
            requestMotionDetect()
        }
    }

    private fun cancelMotionDetect() {
        if (!motionDetectRegistered) return
        motionDetectRegistered = false
        val sensor = motionDetectSensor ?: return
        runCatching { sensorManager?.cancelTriggerSensor(motionDetectListener, sensor) }
    }

    private fun cancelStationaryDetect() {
        if (!stationaryDetectRegistered) return
        stationaryDetectRegistered = false
        val sensor = stationaryDetectSensor ?: return
        runCatching { sensorManager?.cancelTriggerSensor(stationaryDetectListener, sensor) }
    }

    private fun ensureSignificantMotion(): Boolean {
        if (significantRegistered) return true
        return requestSignificantMotion()
    }

    /**
     * 注册显著运动 trigger 传感器。
     *
     * 为什么不用 registerListener：TYPE_SIGNIFICANT_MOTION 是 trigger 类，平台只接受
     * requestTriggerSensor；用 registerListener 会静默失败（真机打 E/SensorManager 后返回 false）。
     * 失败原因必须写日志 —— 这次就是静默失败让模拟器验收全绿、真机才发现。
     */
    private fun requestSignificantMotion(): Boolean {
        val manager = sensorManager
        val sensor = significantMotionSensor
        if (manager == null || sensor == null) {
            logSignificantFailureOnce("本机没有显著运动传感器（模拟器常见）")
            return false
        }
        val ok = runCatching {
            manager.requestTriggerSensor(significantMotionTriggerListener, sensor)
        }.onFailure {
            Log.w(
                TAG,
                "门禁传感器：显著运动注册异常（" + it.javaClass.simpleName + "：" + it.message + "）",
            )
        }.getOrDefault(false)
        val wasRegistered = significantRegistered
        significantRegistered = ok
        if (ok) {
            if (!wasRegistered) {
                Log.i(TAG, "门禁传感器：显著运动已注册（" + sensor.name + "，一次性硬件触发）")
            }
        } else {
            logSignificantFailureOnce("注册返回 false（" + sensor.name + "）")
        }
        return ok
    }

    /**
     * 硬件运动检测常驻待命。
     *
     * 失败（ROM 裁剪 / 无该传感器）只打一次日志并退回原有定时探测：这只是省电增强，
     * 不是功能前提。重复调用是幂等的（已注册直接返回）。
     */
    private fun requestMotionDetect(): Boolean {
        if (motionDetectRegistered) return true
        val manager = sensorManager ?: return false
        val sensor = motionDetectSensor ?: return false
        val ok = runCatching { manager.requestTriggerSensor(motionDetectListener, sensor) }
            .onFailure { Log.w(TAG, "门禁传感器：运动检测注册异常（" + it.javaClass.simpleName + "）") }
            .getOrDefault(false)
        motionDetectRegistered = ok
        if (ok) {
            Log.i(TAG, "门禁传感器：运动检测已注册（" + sensor.name + "，一次性硬件触发）")
        } else if (!motionDetectFailureLogged) {
            motionDetectFailureLogged = true
            Log.w(TAG, "门禁传感器：运动检测不可用，退回 180 秒定时探测")
        }
        return ok
    }

    /** 硬件静止检测常驻待命：触发即代表「人停下来了」，门禁直接回 SLEEPING。 */
    private fun requestStationaryDetect(): Boolean {
        if (stationaryDetectRegistered) return true
        val manager = sensorManager ?: return false
        val sensor = stationaryDetectSensor ?: return false
        val ok = runCatching { manager.requestTriggerSensor(stationaryDetectListener, sensor) }
            .onFailure { Log.w(TAG, "门禁传感器：静止检测注册异常（" + it.javaClass.simpleName + "）") }
            .getOrDefault(false)
        stationaryDetectRegistered = ok
        if (ok) {
            Log.i(TAG, "门禁传感器：静止检测已注册（" + sensor.name + "，一次性硬件触发）")
        } else if (!stationaryDetectFailureLogged) {
            stationaryDetectFailureLogged = true
            Log.w(TAG, "门禁传感器：静止检测不可用，静止判定退回加速度计 5 分钟降级")
        }
        return ok
    }

    private fun logSignificantFailureOnce(detail: String) {
        if (significantFailureLogged) return
        significantFailureLogged = true
        Log.w(
            TAG,
            "门禁传感器：显著运动不可用（" + detail + "），退化为加速度计常开（30s batching）",
        )
    }

    private fun setAccelMode(mode: AccelMode) {
        if (accelMode == mode) return
        val manager = sensorManager
        if (accelMode != AccelMode.NONE) {
            runCatching { manager?.unregisterListener(accelerometerListener) }
        }
        accelMode = AccelMode.NONE
        if (mode == AccelMode.NONE || manager == null) return
        val sensor = accelerometer ?: return
        val ok = runCatching {
            if (mode == AccelMode.WINDOW) {
                manager.registerListener(
                    accelerometerListener,
                    sensor,
                    SensorManager.SENSOR_DELAY_UI,
                    handler,
                )
            } else {
                manager.registerListener(
                    accelerometerListener,
                    sensor,
                    SensorManager.SENSOR_DELAY_NORMAL,
                    BATCH_LATENCY_US,
                    handler,
                )
            }
        }.getOrDefault(false)
        if (!ok) {
            Log.w(TAG, "门禁传感器：加速度计注册失败（门禁保持当前状态）")
            return
        }
        accelMode = mode
        if (mode == AccelMode.WINDOW) {
            // 观察窗重新打开：清掉上一次留存的静止/步态计时。观察窗之间可能隔着几十分钟，
            // 旧计时会让第一帧样本就命中「持续静止 60 秒」，刚出门就被误判静止。
            motion.reset()
            Log.i(TAG, "门禁传感器：加速度计观察窗已开（约 16Hz）")
        } else {
            Log.i(TAG, "门禁传感器：加速度计常开 batched（30s 上报）")
        }
    }

    private fun setStepCounterActive(active: Boolean) {
        val manager = sensorManager ?: return
        if (!active) {
            if (stepCounterRegistered) {
                stepCounterRegistered = false
                runCatching { manager.unregisterListener(stepCounterListener) }
            }
            return
        }
        if (stepCounterRegistered) return
        val sensor = stepCounterSensor ?: return
        val ok = runCatching {
            // 步数计是累计值，30s 一次上报就够（口径：PROBING 期间每 30s 读一次），
            // 不需要每条事件唤醒 CPU
            manager.registerListener(
                stepCounterListener,
                sensor,
                SensorManager.SENSOR_DELAY_NORMAL,
                BATCH_LATENCY_US,
                handler,
            )
        }.onFailure {
            Log.w(
                TAG,
                "门禁传感器：步数计注册异常（" + it.javaClass.simpleName + "：" + it.message + "）",
            )
        }.getOrDefault(false)
        stepCounterRegistered = ok
        if (ok) {
            gate.markHardwareStepsAvailable()
        } else if (!stepCounterFailureLogged) {
            // 23127PN0CC 实测：没有 ACTIVITY_RECOGNITION 权限时 step_counter 注册会失败。
            // 不加新权限（用户没同意运动权限），失败就把原因写清楚，软件步数兜底。
            stepCounterFailureLogged = true
            Log.w(
                TAG,
                "门禁传感器：步数计不可用（缺少 ACTIVITY_RECOGNITION 或硬件不支持），" +
                    "改用加速度峰值软件步数",
            )
        }
    }

    private fun releaseSensors() {
        val manager = sensorManager
        runCatching { manager?.unregisterListener(accelerometerListener) }
        runCatching { manager?.unregisterListener(stepCounterListener) }
        // trigger 传感器必须用 cancelTriggerSensor 摘除（与 requestTriggerSensor 成对）
        val triggerSensor = significantMotionSensor
        if (significantRegistered && manager != null && triggerSensor != null) {
            runCatching { manager.cancelTriggerSensor(significantMotionTriggerListener, triggerSensor) }
        }
        // 运动 / 静止检测同样是一次性 trigger：摘除也要成对，否则服务重启后会重复注册
        motionDetectSensor?.let { sensor ->
            if (motionDetectRegistered) {
                runCatching { manager?.cancelTriggerSensor(motionDetectListener, sensor) }
            }
        }
        stationaryDetectSensor?.let { sensor ->
            if (stationaryDetectRegistered) {
                runCatching { manager?.cancelTriggerSensor(stationaryDetectListener, sensor) }
            }
        }
        accelMode = AccelMode.NONE
        stepCounterRegistered = false
        significantRegistered = false
        motionDetectRegistered = false
        stationaryDetectRegistered = false
        observingUntilMs = 0L
    }

    private fun onAccelerometerSample(event: SensorEvent) {
        val elapsed = SystemClock.elapsedRealtime()
        val wall = System.currentTimeMillis()
        val x = event.values.getOrElse(0) { 0f }
        val y = event.values.getOrElse(1) { 0f }
        val z = event.values.getOrElse(2) { 0f }
        val motionState = motion.onAccelerometer(x, y, z, elapsed)
        // 诊断：每 5 秒输出一次实时标准差，区分「传感器没数据」与「判定不过」
        if (wall - lastMotionLogAt >= MOTION_DIAG_INTERVAL_MS) {
            lastMotionLogAt = wall
            Log.d(
                TAG,
                "运动诊断：stddev=" + String.format("%.2f", motion.lastStdDev) +
                    " 样本数=" + motion.windowSize + " 状态=" + motionState,
            )
        }
        // 退化模式（BATCHED，5Hz）：MotionDetector 判不出步态（≥16Hz 才行）。
        // 抖动只是「可能有人在动」的提示：开 16Hz 观察窗让步态判据有机会跑起来，
        // 门禁是否升级仍只认 GAIT / 步数 / 位移。样本时刻取 event.timestamp：
        // batching 一次投递一批样本，调用时刻的墙钟不变，用它量「持续 5 秒」会永远不满足。
        if (accelMode == AccelMode.BATCHED) {
            gate.onShake(motion.lastStdDev, event.timestamp / 1_000_000L, wall)
            applyObservationHint(wall)
        }
        // 运动判定只当门禁的证据：手持抖动不刷新活动时间，玩手机 5 分钟无步数无位移就回 SLEEPING
        gate.onMotionState(motionState, wall)
        val state = gate.onAccelerometer(x, y, z, wall)
        applyGateState(state)
        // 观察窗到期立刻关（不等 ticker 的慢拍子）：SLEEPING 不该留着 16Hz 加速度计
        if (accelMode == AccelMode.WINDOW && observingUntilMs in 1 until wall &&
            appliedGateState == MotionGate.State.SLEEPING
        ) {
            syncSensors(wall)
        }
    }

    /**
     * 提示型唤醒（显著运动 / 抖动）只开观察窗，不改门禁态。
     *
     * 提示只说明「可能有人在动」：16Hz 加速度计（1–3mW）比 GPS（几十 mW）便宜两个数量级，
     * 用它换「判出 GAIT 的机会」是划算的；没判出就如实什么都不做（见 [MotionGate] 的 KDoc）。
     */
    private fun applyObservationHint(nowMs: Long) {
        val hint = gate.observationHintAtMs
        if (hint <= appliedHintAtMs) return
        appliedHintAtMs = hint
        observingUntilMs = maxOf(observingUntilMs, nowMs + OBSERVE_WINDOW_MS)
    }

    // ===== 充电状态（充电时不省电）=====

    /** 注册充电状态监听：启动时读一次初值（插着电开机收不到「已连接」广播）。 */
    private fun registerPowerReceiver() {
        isCharging = runCatching {
            (getSystemService(Context.BATTERY_SERVICE) as? BatteryManager)?.isCharging == true
        }.getOrDefault(false)
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_POWER_CONNECTED)
            addAction(Intent.ACTION_POWER_DISCONNECTED)
        }
        runCatching {
            ContextCompat.registerReceiver(this, powerReceiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        }.onFailure { Log.w(TAG, "充电状态监听注册失败（兜底保持 60 分钟）", it) }
    }

    private fun unregisterPowerReceiver() {
        runCatching { unregisterReceiver(powerReceiver) }
    }

    // ===== 网络环境（静止负向证据）=====

    /** 注册默认网络监听；失败只能是「拿不到网络信息」，兜底保持 30 分钟（旧行为）。 */
    private fun registerNetworkCallback() {
        val manager = connectivityManager ?: return
        runCatching { manager.registerDefaultNetworkCallback(networkCallback) }
            .onFailure { Log.w(TAG, "网络环境监听注册失败（兜底保持 30 分钟）", it) }
    }

    private fun unregisterNetworkCallback() {
        val manager = connectivityManager ?: return
        runCatching { manager.unregisterNetworkCallback(networkCallback) }
    }

    /** 回调线程只负责取指纹，比对与副作用统一 post 到主线程（与 [MotionGate] 同一线程模型）。 */
    private fun refreshNetworkFingerprint(caps: NetworkCapabilities? = null) {
        val fingerprint = fingerprintOf(caps)
        handler.post { applyNetworkFingerprint(fingerprint) }
    }

    /**
     * 当前网络环境指纹。
     *
     * 回调给了 capability 就直接用（省一次 binder 调用）；没给（onAvailable / onLost）读一次
     * 当前默认网络 —— 丢失的可能是旧网络，读「现在是什么」才对。
     */
    private fun fingerprintOf(caps: NetworkCapabilities?): NetworkFingerprint {
        val effective = caps ?: activeNetworkCapabilities()
        if (effective == null) return NetworkFingerprint(NetworkTransport.OTHER, null)
        val transport = NetworkTransport.of(
            effective.hasTransport(NetworkCapabilities.TRANSPORT_WIFI),
            effective.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR),
        )
        val ssid = if (transport == NetworkTransport.WIFI) wifiSsid(effective) else null
        return NetworkFingerprint(transport, ssid)
    }

    private fun activeNetworkCapabilities(): NetworkCapabilities? {
        val manager = connectivityManager ?: return null
        val network = manager.activeNetwork ?: return null
        return runCatching { manager.getNetworkCapabilities(network) }.getOrNull()
    }

    /**
     * 取 SSID：优先用 capability 自带的 transportInfo（不额外 binder），读不到再退回 WifiManager。
     *
     * 读不到（没给定位权限 / 系统脱敏）返回 null，由 [NetworkFingerprint.sameEnvironmentAs]
     * 按 transport 判同环境 —— 「未知 SSID」不是「换了 WiFi」的证据。
     */
    private fun wifiSsid(caps: NetworkCapabilities): String? {
        val fromCaps = (caps.transportInfo as? WifiInfo)?.ssid
        if (fromCaps != null) return fromCaps
        return runCatching {
            (getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.connectionInfo?.ssid
        }.getOrNull()
    }

    /**
     * 应用一次网络观测：换了环境就清掉静止证据，并**立刻**按 30 分钟兜底重排。
     *
     * 重排不能省：放宽后的一拍最迟也只有 5 分钟（拍子上界），重排只为少等这 5 分钟
     * 才会有一次探测，环境变化等于没被看见。
     */
    private fun applyNetworkFingerprint(fingerprint: NetworkFingerprint) {
        val changed = networkStillness.observeNetwork(fingerprint, System.currentTimeMillis())
        if (!changed) return
        // 只打 transport：SSID 是用户位置的一部分，不写进日志（见 NetworkFingerprint 的 KDoc）
        Log.i(
            TAG,
            "网络环境变化：transport=" + fingerprint.transport + "，静止证据清零，兜底恢复 " +
                (LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS / 60_000L) + " 分钟",
        )
        // 换网络 / 断网 = 可能离开原地：探测退避阶梯清零，别让「在家坐了 10 分钟」的 600 秒
        // 退避把刚上车那一段饿死（见 MotionGate.onEnvironmentChanged）
        gate.onEnvironmentChanged()
        tickerScheduler.onStateChanged()
    }

    /**
     * 把门禁记到的「真实活动」转给静止证据：有步数增量 / 步态就不许延长兜底。
     *
     * 必须在 [applyGateState] 的同态早退之前调用：PROBING → PROBING 这类同态调用同样可能带着
     * 新步数（走两步又停下），漏掉它就会让「已经走过路」的现场继续享受 60 分钟兜底。
     */
    private fun syncStillnessEvidence() {
        val activityAtMs = gate.lastActivityAtMs
        if (activityAtMs <= appliedActivityAtMs) return
        appliedActivityAtMs = activityAtMs
        networkStillness.onMotionEvidence(activityAtMs)
    }

    // ===== 门禁态 → 定位行为 =====

    /**
     * 应用门禁态：**订阅开关只由这里决定**。
     *
     * SLEEPING / PROBING 都显式 [stopSubscription]：未确认远行时任何残留订阅都会让
     * 定位芯片跟着「拿起手机」一直不休眠；只有 MOVING 才 [startSubscription]。
     *
     * 同态也要进来：PROBING → PROBING 时**探测间隔**会随退避阶梯变化（180 → 300 → 600 秒），
     * 状态没变就早退会让退避只落在服务 tick 上、不落到采集器的 probeIntervalOverride 上。
     */
    private fun applyGateState(state: MotionGate.State, force: Boolean = false) {
        // 先同步活动证据（步数 / 步态）再判早退：同态调用也可能带着新步数
        syncStillnessEvidence()
        val probeIntervalMs = if (state == MotionGate.State.PROBING) gate.probeIntervalMs else 0L
        if (!force && state == appliedGateState) {
            syncProbeInterval(probeIntervalMs)
            return
        }
        appliedGateState = state
        appliedProbeIntervalMs = probeIntervalMs
        // 同步给 1 分钟心跳链路（AlarmReceiver）：它拿不到 Service 实例，只能读这个快照。
        // 不写的话心跳会绕过门禁每分钟发起一次高精度单点，「静止不定位」形同虚设。
        LocationGateState.update(state)
        val collector = LocationCollector.get(this)
        when (state) {
            MotionGate.State.SLEEPING -> {
                // 离开 PROBING/MOVING 立刻关观察窗：SLEEPING 不该再留着 16Hz 加速度计
                observingUntilMs = 0L
                collector.setProbeIntervalOverride(null)
                collector.setCaptureIntervalOverride(LocationPace.STILL_INTERVAL_MS)
                runCatching { collector.stopSubscription() }
                    .onFailure { Log.w(TAG, "静止时释放订阅失败", it) }
                Log.i(TAG, "门禁：SLEEPING（不定位）")
            }
            MotionGate.State.PROBING -> {
                // 低频单点探测：覆盖坐车/骑行这类传感器平稳但人在位移的场景，
                // 又不会为玩手机持续烧定位。间隔由门禁的退避阶梯给（180 / 300 / 600 秒）
                collector.setProbeIntervalOverride(probeIntervalMs)
                // 服务重建后本字段归零：不置初值的话首拍恒判「间隔已到」，立刻多发一次探测
                if (lastProbeAtMs == 0L) lastProbeAtMs = System.currentTimeMillis()
                collector.setCaptureIntervalOverride(LocationPace.STILL_INTERVAL_MS)
                runCatching { collector.stopSubscription() }
                    .onFailure { Log.w(TAG, "探测态释放订阅失败", it) }
                Log.i(
                    TAG,
                    "门禁：PROBING（未确认远行，不建订阅，探测间隔 " + (probeIntervalMs / 1000) + "s）",
                )
            }
            MotionGate.State.MOVING -> {
                collector.setProbeIntervalOverride(null)
                collector.setCaptureIntervalOverride(LocationPace.MOVING_INTERVAL_MS)
                startSubscription()
                Log.i(TAG, "门禁：MOVING（已确认远行，30s 订阅）")
                // 升级瞬间补一个点，避免出门第一段轨迹缺失
                probeOnce()
            }
        }
        logEvidence()
        // 状态迁移必须**立刻**生效，不能等（可能已被排到 5 分钟后的）ticker：
        // 1) syncSensors 真正反注册 / 打开 16Hz 加速度计 —— SLEEPING 分支只把 observingUntilMs 清零，
        //    不重配的话 16Hz 观察窗会跟着慢拍子多跑最多 5 分钟（SLEEPING 不该有 16Hz 加速度计）；
        // 2) ticker 立刻按新态重排 —— PROBING / MOVING 的 30 秒探测不能被 SLEEPING 的慢拍子拖后。
        // 本方法是门禁态的唯一应用出口（传感器 / 探测证据都会 post 到主线程再进来），同步执行即可。
        syncSensors(System.currentTimeMillis())
        tickerScheduler.onStateChanged()
    }

    /**
     * 同态下的间隔补同步：退避档变了就把新间隔写给采集器。
     *
     * 只处理非 0 值：SLEEPING / MOVING 的覆盖分别是 null 与 30 秒，由 [applyGateState] 的状态分支负责，
     * 这里再写一次会把 MOVING 的 30 秒订阅覆盖回 180 秒。
     */
    private fun syncProbeInterval(intervalMs: Long) {
        if (intervalMs <= 0L || intervalMs == appliedProbeIntervalMs) return
        appliedProbeIntervalMs = intervalMs
        LocationCollector.get(this).setProbeIntervalOverride(intervalMs)
        Log.i(
            TAG,
            "门禁：PROBING 探测退避 → " + (intervalMs / 1000) + "s（连续 " +
                gate.quietProbeCount + " 次无位移证据）",
        )
    }

    private fun logEvidence() {
        Log.i(TAG, "门禁证据：步数=" + gate.stepsSinceAnchor + " 距离=" + Math.round(gate.movementDistanceM) + "m")
        Log.i(TAG, "门禁原因：" + gate.reason)
    }

    private fun logEvidenceIfDue(nowMs: Long) {
        if (nowMs - lastEvidenceLogAt < EVIDENCE_LOG_INTERVAL_MS) return
        lastEvidenceLogAt = nowMs
        logEvidence()
    }

    // ===== ticker =====

    private fun maybeSampleLocation() {
        if (!FeatureFlags.get().isEnabled(Feature.LOCATION)) return
        val now = System.currentTimeMillis()
        val state = gate.onTick(now)
        applyGateState(state)
        syncSensors(now)
        when (state) {
            MotionGate.State.SLEEPING -> {
                // 兜底频率：手机放在车上/传送带上时加速度可能一直平稳（误判静止），没有兜底就会
                // 一整天零点。放在这一拍的末尾而不是只靠回调 —— Doze 下网络回调可能被压住，
                // 每拍（最长 5 分钟）自己读一次，兜底间隔才不会停在过期的判断上。
                applyNetworkFingerprint(fingerprintOf(null))
                val fallbackIntervalMs = LocationTickerPolicy.sleepingFallbackIntervalMs(
                    networkStillness.stillSinceMs,
                    now,
                    isCharging,
                )
                // 调试自检：这一拍用的是 30 / 60 分钟哪一档（不参与控制流）
                lastFallbackIntervalMs = fallbackIntervalMs
                // 启动后先等满一个窗口，不在 SLEEPING 立刻采点
                if (lastFallbackAtMs == 0L) {
                    lastFallbackAtMs = now
                } else if (now - lastFallbackAtMs >= fallbackIntervalMs) {
                    lastFallbackAtMs = now
                    // 被动通道最近还在投点（别的 App 在定位）→ 这一轮兜底完全没必要发请求：
                    // 别人的点已经证明「还在这一片」，我们主动探测只是白烧射频。
                    val passiveFresh = now - LocationCollector.get(this).lastPassiveSampleAtMs() <
                        fallbackIntervalMs
                    if (passiveFresh) {
                        Log.i(TAG, "兜底跳过：被动通道最近仍在投点（零射频）")
                    } else {
                        // 兜底探测只求「大致还在原地」，覆盖级预算够用（满预算等于每轮白烧 20 秒射频）
                        probeOnce(FixBudget.PROBE)
                    }
                }
            }
            MotionGate.State.PROBING -> {
                // 间隔读门禁的退避阶梯（180 / 300 / 600 秒），与 collector 的 probeIntervalOverride 同源
                if (now - lastProbeAtMs >= gate.probeIntervalMs) {
                    // 被动点比探测点更准也更便宜：最近有别人定位到的点，这一轮探测跳过。
                    // 不为它更新 lastProbeAtMs —— 被动点一断，下一拍立刻按原间隔补上。
                    val passiveFresh = now - LocationCollector.get(this).lastPassiveSampleAtMs() <
                        gate.probeIntervalMs
                    if (passiveFresh) {
                        Log.i(TAG, "探测跳过：被动通道最近仍在投点（零射频）")
                    } else {
                        lastProbeAtMs = now
                        probeOnce(FixBudget.PROBE)
                    }
                }
            }
            MotionGate.State.MOVING -> {
                val collector = LocationCollector.get(this)
                if (!collector.isSubscriptionActive()) startSubscription()
                val silent = (collector.health(now).subscriptionSilentMs ?: Long.MAX_VALUE) >
                    LocationPace.MOVING_INTERVAL_MS * 2
                if (silent) probeOnce()
            }
        }
        logEvidenceIfDue(now)
    }

    /**
     * 采一个探测点：单点定位，拿到即释放（不建订阅）。
     *
     * 必须 force：门禁探测不能被采集节流（STILL 180s 窗口）挡掉，否则 PROBING 拿不到
     * 「现在在哪」，5 分钟无证据直接回 SLEEPING（模拟器实测的坐车不升级）。
     * 但不为探测烧满射频：≤90 秒的系统缓存点可以直接顶替（见 [LocationProbePolicy.canUseCached]）。
     * 点位证据由采集器内部统一 offer（见 [LocationGateState]），这里只负责落库。
     *
     * @param budget 预算档位：PROBING 探测与 SLEEPING 兜底传 [FixBudget.PROBE]（4s + 8s）；
     *   MOVING 的静默补采与升级瞬间补点保持满预算（5s + 15s）—— 那条路径必须真的拿到点。
     */
    private fun probeOnce(budget: FixBudget = FixBudget.FULL) {
        runCatching {
            LocationCollector.get(this).collectThrottledAsync(force = true, budget = budget) { sample ->
                if (sample == null) return@collectThrottledAsync
                ioWork.scope.launch {
                    runCatching { serviceContainer.recordCollector.onLocation(sample) }
                        .onFailure { Log.w(TAG, "探测点位落库失败", it) }
                }
            }
        }.onFailure { Log.w(TAG, "门禁探测采样失败", it) }
    }

    /**
     * 注册被动定位通道（常驻，零射频）。
     *
     * 只搭别的 App 已经测出来的点，不会让定位芯片启动；点位经采集器的质量闸、落库节流与
     * 门禁位移证据链（[LocationCollector.startPassiveTap]），这里只负责落库。
     * 失败（未授权 / provider 不可用）只记日志，链路退化回原有的探测与订阅。
     */
    private fun startPassiveTap() {
        runCatching {
            LocationCollector.get(this).startPassiveTap { sample ->
                ioWork.scope.launch {
                    runCatching { serviceContainer.recordCollector.onLocation(sample) }
                        .onFailure { Log.w(TAG, "被动定位点位落库失败", it) }
                }
            }
        }.onFailure { Log.w(TAG, "注册被动定位失败", it) }
    }

    /** 建立持续订阅（幂等）：采样回调直接落库，落库放 IO 协程。只在门禁 MOVING 时被调用。 */
    private fun startSubscription() {
        runCatching {
            LocationCollector.get(this).startSubscription { sample ->
                // 订阅点位的门禁证据由采集器内部 offer（onSubscriptionSample），这里只落库
                ioWork.scope.launch {
                    runCatching { serviceContainer.recordCollector.onLocation(sample) }
                        .onFailure { Log.w(TAG, "订阅点位落库失败", it) }
                }
            }
        }.onFailure { Log.w(TAG, "建立位置订阅失败", it) }
    }

    /**
     * 任意链路拿到的点位 → 门禁位移证据。
     *
     * 只在本服务的主线程 handler 里执行（传感器 / 广播 / 定位回调都会 post 到这里），
     * 所以 [MotionGate] 保持无锁纯逻辑；升级/降级后的重配也在这里顺势完成。
     */
    private fun onGateEvidence(sample: LocationSample, fromProbe: Boolean = true) {
        val state = gate.onProbeSample(sample, fromProbe)
        applyGateState(state)
        syncSensors(System.currentTimeMillis())
    }

    // ===== 前台服务样板（通知 / 通道 / 生命周期）=====

    /**
     * 进前台。
     *
     * @return 是否成功；失败时调用方必须立刻退出（没有前台身份就不该继续跑定位）
     */
    private fun enterForeground(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIFICATION_ID, buildNotification())
        }
        running = true
        true
    }.getOrElse { error ->
        // 缺后台定位权限 / 系统拒绝后台建 FGS：都是「前台服务救不了」，如实退出不重试
        Log.w(TAG, "定位前台服务启动失败：" + error.javaClass.simpleName)
        running = false
        stopSelf()
        false
    }

    /** 启动 ticker（幂等）：第一拍按当前门禁态排（SLEEPING 直接 5 分钟，不再 30 秒空转）。 */
    private fun startTicker() = tickerScheduler.start()

    private fun stopTicker() = tickerScheduler.stop()

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or
                Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID_LOCATION)
            .setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle("定位采集中")
            .setContentText("确认远行后约 30 秒一点；未移动时不持续采集")
            // PRIORITY_MIN：不弹、不响、不出状态栏图标，只在通知栏底部折叠
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setOngoing(true)
            .setSilent(true)
            .setShowWhen(false)
            // 通知里不出现任何坐标/地址：常驻通知是最容易被旁人看到的一屏
            .setVisibility(NotificationCompat.VISIBILITY_SECRET)
            .setContentIntent(contentIntent)
            .build()
    }

    private fun createChannel() {
        // IMPORTANCE_MIN：通知栏最底部折叠、无声音震动、不显示图标。
        // 前台服务在 Android 上「必须有通知」，但可以做到几乎看不见；
        // 用户仍可在系统设置里单独关闭该渠道（服务不受影响）。
        val channel = NotificationChannel(
            CHANNEL_ID_LOCATION,
            "定位采集",
            NotificationManager.IMPORTANCE_MIN,
        ).apply {
            description = "后台定位采集的常驻状态（无内容预览）"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_SECRET
        }
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.createNotificationChannel(channel)
    }

    companion object {
        private const val TAG = "LocationForegroundService"
        const val CHANNEL_ID_LOCATION = "location_tracking_channel"
        private const val NOTIFICATION_ID = 5400

        /**
         * 显著运动 / 抖动提示后的观察窗：这段时间里用 16Hz 加速度计 + 步数计取证。
         *
         * 取 3 分钟而不是 90 秒：步态判据要 5 秒稳定步频才成立，而「拿起手机→放下→出门走路」
         * 往往隔着一两分钟；90 秒窗会在人刚开始走时刚好关掉，步行 3 分钟内必判出 GAIT。
         * 16Hz 加速度计约 1–3mW，比 GPS 小两个数量级，放宽窗口的代价可以忽略。
         */
        private const val OBSERVE_WINDOW_MS = 180_000L

        /** 无显著运动传感器时加速度计的 batching 延迟（微秒）：30 秒。 */
        private const val BATCH_LATENCY_US = 30_000_000

        /** 运动诊断日志间隔。 */
        private const val MOTION_DIAG_INTERVAL_MS = 5_000L

        /** 运动 / 静止硬件事件的最小间隔：30 秒内的重复事件只认一次（防传感器误触发来回切态）。 */
        private const val MOTION_EVENT_MIN_GAP_MS = 30_000L

        /** 门禁证据日志间隔（5 分钟一条，避免刷屏）。 */
        private const val EVIDENCE_LOG_INTERVAL_MS = 5 * 60_000L

        /** 最近一次算出的 SLEEPING 兜底间隔（毫秒）：只给调试自检读，不参与控制流。 */
        @Volatile
        var lastFallbackIntervalMs: Long = 0L
            private set

        /** 进程内「前台服务在跑」标志：设置页与调试入口据此判断是否需要重新拉起。 */
        @Volatile
        var running: Boolean = false
            private set

        /**
         * 启动服务（幂等，只在定位功能开启时）。
         *
         * 调用点必须是有可见界面的时机（[MainActivity.onResume]）：Android 12+ 从后台
         * 启动前台服务会抛 ForegroundServiceStartNotAllowedException。
         */
        fun start(context: Context) {
            if (!FeatureFlags.get(context).isEnabled(Feature.LOCATION)) return
            val intent = Intent(context, LocationForegroundService::class.java)
            runCatching { ContextCompat.startForegroundService(context, intent) }
                .onFailure { Log.w(TAG, "启动定位前台服务失败", it) }
        }

        /** 停止服务（关闭定位功能 / 用户撤销权限后）。 */
        fun stop(context: Context) {
            running = false
            runCatching { context.stopService(Intent(context, LocationForegroundService::class.java)) }
        }
    }
}
