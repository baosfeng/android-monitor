package com.bsfeng.monitor.record

// 从 LocationRules 拆出：按 provider 分级的精度判定 + 「低置信点不许覆盖更优位置」的质量闸。
// 纯逻辑，无 Android 依赖，可在纯 JVM 单测里逐条验证。

/**
 * 一个定位点的置信级别。
 *
 * 不是「对不对」，而是「够不够精确到能当轨迹主点用」：GPS 几十米算高置信，室内 NETWORK
 * 几百米只能算低置信（能记、不许覆盖更好的位置）。
 */
enum class FixConfidence {
    /** 精度 <= [LocationQuality.HIGH_ACCURACY_METERS] 的 GPS 点：可当轨迹主点。 */
    HIGH,

    /** 偏差但仍在容差内（NETWORK / PASSIVE 几百米）：可落库，但不许覆盖更优位置。 */
    LOW,

    /** 精度差到不能用：丢弃，不猜位置。 */
    REJECTED,
}

/**
 * 精度分级与点位质量闸（纯函数）。
 *
 * 旧实现是一刀切 100m：GPS 首点常见 30-80m 能过，室内 NETWORK 300-2000m 全被丢掉
 * （用户感知为「掉线」）；反过来缓存里的 80m NETWORK 粗点又被当成有效点位落库（画出来漂移）。
 * 这里把「能不能记」与「能不能当主点」拆开：分级 + 覆盖闸。
 */
object LocationQuality {

    /** 高置信门槛：不超过这个精度才允许当轨迹主点。 */
    const val HIGH_ACCURACY_METERS = 100f

    /** 可信 provider（gps / fused / passive）的可用上限：到 500m 基本说明信号极差，点不可信。 */
    const val GPS_LOW_LIMIT_METERS = 500f

    /**
     * NETWORK 的低置信上限（500m）：室内 300-500m 还能说明「在这一片」，
     * 超过就没有信息量（2000m 的点足以跨一个街区，落库只会画出漂移）。
     */
    const val NETWORK_LOW_LIMIT_METERS = 500f

    /** 低置信点要「真的移动过」才允许替代已有位置（米）。 */
    const val LOW_CONFIDENCE_MOVE_METERS = 150.0

    /**
     * 静止模式下忽略的位移半径（米）：50 米以内的位置变化视为同一点。
     *
     * 定位点本身有几十米抖动（室内 passive / fused 精度 26–175m），人没动时这些点
     * 不该被当成新位置写进轨迹。用户口径：50m 以内都应该忽略。
     */
    const val STILL_IGNORE_RADIUS_METERS = 50.0

    /** 低置信点精度改善这么多才值得覆盖（米）。 */
    const val ACCURACY_GAIN_METERS = 20f

    /**
     * 按 provider 与精度给出置信级别；accuracy <= 0（系统未给精度）一律不可用。
     *
     * **passive 与 gps / fused 同口径**：passive 点是「别的 App 刚定位到的点」，系统给的
     * accuracyMeters 就是它真实的精度，与谁产出无关。曾经把 passive 塞进 else 分支按
     * 「永远不高置信」处理，而 [LocationPolicy.PROVIDER_PRIORITY] 又把 passive 排第一 ——
     * 于是系统缓存里那条 5 米的 passive 点永远判 LOW，模式判定永远拿不到双高置信点、
     * 永远停在 STILL（轨迹稀疏 + 漂移）。**精度分级只看精度，不看 provider 名**。
     *
     * 唯一例外是 network：它靠基站 / WiFi 测距，几百米是常态，**永远不高置信**。
     */
    fun confidenceOf(provider: String, accuracyMeters: Float): FixConfidence {
        if (accuracyMeters <= 0f) return FixConfidence.REJECTED
        return when (provider) {
            "gps", "fused", "passive" -> when {
                accuracyMeters <= HIGH_ACCURACY_METERS -> FixConfidence.HIGH
                accuracyMeters < GPS_LOW_LIMIT_METERS -> FixConfidence.LOW
                else -> FixConfidence.REJECTED
            }
            // network / 未知 provider：都不算高置信，室内几百米的点只能当低置信
            else -> if (accuracyMeters < NETWORK_LOW_LIMIT_METERS) FixConfidence.LOW else FixConfidence.REJECTED
        }
    }

    /** 静止模式下与上一点的位移是否小到可以忽略（< [STILL_IGNORE_RADIUS_METERS]）。 */
    fun withinStillIgnoreRadius(previous: LocationSample, candidate: LocationSample): Boolean =
        LocationPace.distanceMeters(
            previous.latitude, previous.longitude, candidate.latitude, candidate.longitude,
        ) < STILL_IGNORE_RADIUS_METERS

    /** 这个点是否值得落库（精度层面）：false 表示直接丢弃。 */
    fun isAcceptable(provider: String, accuracyMeters: Float): Boolean =
        confidenceOf(provider, accuracyMeters) != FixConfidence.REJECTED

    /**
     * 质量闸：新点能不能替代 [previous]（上一次被接受的点）。
     *
     * 规则（对应「低置信点不许覆盖更优位置」）：
     * - 不可用精度 -> false；
     * - 没有已有点位 / 已有点位 stale（>30 分钟）-> true，先把轨迹接上；
     * - 高置信点 -> true（30 秒节奏的轨迹点不能因为「没怎么动」被丢掉）；
     * - 已有高置信点位时，低置信点只有位移 > [LOW_CONFIDENCE_MOVE_METERS] 才认可（真走远了）；
     * - 两个低置信点之间：精度改善 >= [ACCURACY_GAIN_METERS]，或位移 > [LOW_CONFIDENCE_MOVE_METERS]，
     *   才落库 —— 避免室内 300m 误差圈里原地乱跳刷出一堆漂移点。
     *
     * 上面「高置信」一律按 [confidenceOf] 判定，因此 passive 高精度点走的也是高置信分支
     * （它常是系统缓存里最新、最准的那条，被当成 LOW 会让轨迹整段被节流掉）。
     */
    fun shouldAccept(candidate: LocationSample, previous: LocationSample?, nowMs: Long): Boolean {
        val candidateConfidence = confidenceOf(candidate.provider, candidate.accuracyMeters)
        if (candidateConfidence == FixConfidence.REJECTED) return false
        if (previous == null) return true
        if (!LocationPolicy.isFresh(previous.timeMs, nowMs)) return true
        val previousConfidence = confidenceOf(previous.provider, previous.accuracyMeters)
        if (candidateConfidence == FixConfidence.HIGH) return true
        val distance = LocationPace.distanceMeters(
            previous.latitude, previous.longitude, candidate.latitude, candidate.longitude,
        )
        if (previousConfidence == FixConfidence.HIGH) return distance > LOW_CONFIDENCE_MOVE_METERS
        val accuracyGain = previous.accuracyMeters - candidate.accuracyMeters
        return accuracyGain >= ACCURACY_GAIN_METERS || distance > LOW_CONFIDENCE_MOVE_METERS
    }
}
