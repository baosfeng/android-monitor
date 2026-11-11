package com.bsfeng.monitor.record

// 「默认网络还是不是原来那个」这件事的纯逻辑：指纹比对 + 静止证据计时。
// 服务层只负责把 ConnectivityManager 的 transport 与 WifiManager 的 SSID 搬进来，
// 判据全部在这里，因此「同一 WiFi 不延长」「换 WiFi 立刻回 30 分钟」「读不到 SSID 不算变化」
// 都能在纯 JVM 单测里逐条钉死（见 NetworkEnvironmentTest）。

/** 默认网络的承载类型：只关心「换没换环境」，不关心信号强度、运营商或具体 AP。 */
enum class NetworkTransport {
    WIFI,
    CELLULAR,
    OTHER,
    ;

    companion object {
        /** 从 capability 的两个 transport 位推承载类型；都不是就是 OTHER（以太网 / VPN / 当前无默认网络）。 */
        fun of(wifi: Boolean, cellular: Boolean): NetworkTransport = when {
            wifi -> WIFI
            cellular -> CELLULAR
            else -> OTHER
        }
    }
}

/**
 * 网络环境指纹：默认网络的 [NetworkTransport] + 可读时的 SSID。
 *
 * **边界（WiFi 只是负向证据）**：同一 SSID 不等于同一地点 —— 公司 / 商场的 AP 覆盖几百米，
 * 同一栋楼里换三层也不换 SSID。所以它只能**放宽**兜底频率，永远和「无步数增量」并列判定
 * （见 [NetworkStillnessTracker]）：出现步数增量或步态，静止证据立刻作废。
 *
 * 隐私：[ssid] 只参与比对，不进日志、不进库。SSID 是用户位置的一部分（家 / 公司的名字），
 * 打出来就等于把「用户这会儿在哪类地方」写成可读文本。
 */
data class NetworkFingerprint(val transport: NetworkTransport, val ssid: String? = null) {

    /** 可比较的 SSID：null / 空 / 系统占位（`<unknown ssid>`、脱敏的 `0x`）一律按「读不到」。 */
    val readableSsid: String?
        get() {
            val raw = ssid?.trim()?.trim('"')?.trim().orEmpty()
            return if (raw.isEmpty() || raw == UNKNOWN_SSID || raw == REDACTED_SSID) null else raw
        }

    /**
     * 是否同一个网络环境。
     *
     * 判据：transport 必须相同；SSID 只在**两边都可读**时比较 —— 读不到 SSID（没给定位权限、
     * 系统脱敏）不是「换了 WiFi」的证据，此时按 transport 判同环境。先读不到、后来读得到同样不算变化，
     * 否则刚授权定位的那一刻会凭空清一次静止证据。
     */
    fun sameEnvironmentAs(other: NetworkFingerprint?): Boolean {
        if (other == null) return false
        if (transport != other.transport) return false
        val mine = readableSsid
        val theirs = other.readableSsid
        return mine == null || theirs == null || mine == theirs
    }
}

/**
 * 网络环境的**静止证据**（纯逻辑，无 Android 依赖、不读时钟）。
 *
 * 证据成立的条件：指纹一直没变（连上 / 断开 / 换 SSID / 换 transport 都算变），且期间没有
 * 步数增量 / 步态。任一条件被打破就清零，SLEEPING 兜底立刻回
 * [LocationTickerPolicy.SLEEPING_FALLBACK_INTERVAL_MS]（30 分钟）。
 *
 * 为什么证据要熬过一轮 30 分钟才放宽：进程刚起、刚连上 WiFi 时「指纹没变」只说明「这一秒没变」。
 * 先按旧兜底验一轮，确认这一轮里既没换网络也没走路，才允许放宽到
 * [LocationTickerPolicy.SLEEPING_STILL_FALLBACK_INTERVAL_MS]（60 分钟）。
 *
 * **transport 未变对 CELLULAR 是弱证据**：系统不告诉我们换了哪个基站，坐车穿过半个城市也是
 * 「CELLULAR 未变」。所以这条证据只用于**降频**，不构成「人没动」的结论 —— 兜底探点、步数增量与
 * 步态仍是唯一能把它打回 30 分钟的证据源。
 *
 * 省电体现在「不发起定位请求」，**不伪造点位**：没有新坐标就没有新 LOCATION 记录，
 * 时间线上那段空白由 [CoverageTimeline] 的「省电待机」表达。
 *
 * 线程：所有时刻由调用方传入，服务层统一在主线程喂（与 [MotionGate] 同一线程模型），因此本类不加锁。
 */
class NetworkStillnessTracker {

    /** 最近一次观测到的指纹；null = 还没有基线。读不到 SSID 的观测不覆盖已知 SSID。 */
    var fingerprint: NetworkFingerprint? = null
        private set

    /**
     * 静止证据的起点（毫秒）；0 = 当前没有静止证据。
     *
     * [LocationTickerPolicy.sleepingFallbackIntervalMs] 读它决定兜底用 30 分钟还是 60 分钟。
     */
    var stillSinceMs: Long = 0L
        private set

    /** 最近一次指纹变化的时刻（诊断 / 日志用）。 */
    var lastChangedAtMs: Long = 0L
        private set

    /** 最近一次运动证据（步数增量 / 步态 / 位移过噪声地板）的时刻（诊断用）。 */
    var lastMotionEvidenceAtMs: Long = 0L
        private set

    /**
     * 喂一次默认网络的观测。
     *
     * @return true = 与基线相比换了环境（此时 [stillSinceMs] 已清零，兜底立刻回 30 分钟）
     */
    fun observeNetwork(fingerprint: NetworkFingerprint, nowMs: Long): Boolean {
        val previous = this.fingerprint
        // 读不到 SSID 时沿用已知 SSID：一次读失败不该把「已知是家里」退化成「不知道在哪儿」，
        // 否则「家 → 读不到 → 公司」会被判成同环境，公司里那趟等于没发生。
        val effective = if (previous != null &&
            previous.transport == fingerprint.transport &&
            fingerprint.readableSsid == null
        ) {
            fingerprint.copy(ssid = previous.ssid)
        } else {
            fingerprint
        }
        this.fingerprint = effective
        if (previous == null) {
            // 第一条观测只建基线：没有历史就谈不上「变了」，直接开始攒静止证据
            stillSinceMs = nowMs
            return false
        }
        if (effective.sameEnvironmentAs(previous)) {
            // 证据被「换网 / 断网 / 步数」清零后必须能重建，否则 60 分钟档这辈子只用得上一次：
            // 同环境持续观测（ticker 每拍 ≤5 分钟喂一次）就从这一刻重新计时，熬满一轮 30 分钟再放宽
            if (stillSinceMs == 0L) stillSinceMs = nowMs
            return false
        }
        lastChangedAtMs = nowMs
        stillSinceMs = 0L
        return true
    }

    /** 运动证据（步数增量 / 步态 / 位移过噪声地板）：静止证据立刻作废，兜底回 30 分钟。 */
    fun onMotionEvidence(nowMs: Long) {
        lastMotionEvidenceAtMs = nowMs
        stillSinceMs = 0L
    }

    /** 服务重建：指纹与证据一起清空，下一次观测重新建基线。 */
    fun reset() {
        fingerprint = null
        stillSinceMs = 0L
        lastChangedAtMs = 0L
        lastMotionEvidenceAtMs = 0L
    }
}

/** 系统在拿不到 SSID 时给的占位串（WifiInfo 口径）。 */
private const val UNKNOWN_SSID = "<unknown ssid>"

/** 未授予定位权限时系统脱敏后的 SSID 形态。 */
private const val REDACTED_SSID = "0x"
