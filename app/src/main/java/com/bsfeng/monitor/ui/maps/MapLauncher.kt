package com.bsfeng.monitor.ui.maps

// 「在地图中查看」的 Android 侧装配：包是否已装、Intent 是否可解析、候选怎么排。
//
// ## 三层职责分开
// 1. [MapEntryPlan.plan] —— 纯函数：给定「已装哪些包」，算出候选顺序与兜底文案。可在 JVM 单测里
//    把「只装了高德」「都没装」「全装了」等组合一次跑完，不需要设备。
// 2. [MapLauncher.runnableTargets] —— 薄封装：问 PackageManager 谁装了、再用 resolveActivity 复核
//    Intent 真的能解析（装了 App 但没注册该 scheme 的情况确实存在）。
// 3. [MapLauncher.open] —— 真正 startActivity，全部 runCatching 包裹，失败静默降级返回 false。
//
// ## Android 11+ 的包可见性
// targetSdk 30+ 时 queryIntentActivities 只返回本 App「可见」的包。地图包的可见性由
// AndroidManifest 的 `<queries>` 声明提供（见 MapTarget.QUERY_PACKAGES）；不声明的话这里会
// 一律看不到已装地图，功能静默退化成「只有浏览器」。

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri

/** 候选计划的纯逻辑结果。[candidates] 为空表示一个可唤起目标都没有。 */
data class MapEntryPlan(val candidates: List<MapCandidate>) {

    /** 是否需要弹选择：0 个不弹（提示兜底）、1 个直接开、多个才问。 */
    val needsPicker: Boolean get() = candidates.size > 1

    /** 直接打开用的唯一候选；非「恰好一个」时为 null。 */
    val single: MapCandidate? get() = if (candidates.size == 1) candidates.first() else null

    /**
     * 唯一候选是不是浏览器兜底：是的话「直接打开」的同时要给一句
     * [NO_MAP_TEXT]（用户需要知道为什么打开的是网页而不是地图）。
     */
    val usingBrowserFallback: Boolean get() = single?.target == MapTarget.BROWSER

    companion object {
        /** 一个都没有时的固定文案（UI 与 toast 共用，避免两处写法不一致）。 */
        const val NO_MAP_TEXT = "未安装地图应用，已改用浏览器"

        /** 浏览器兜底也没能唤起时的文案。 */
        const val NO_BROWSER_TEXT = "没有可用的地图或浏览器应用"

        /**
         * 按「已装地图包集合」算出候选。
         *
         * **浏览器是兜底，不是并列选项**：只有在一个地图 App 都没装时才出现在候选里。
         * 把它并进候选会让「只装了高德」变成「两个候选、每次都要弹选择器」，用户多点一次却
         * 得不到任何信息；也会让 [needsPicker] 永远为 true。
         *
         * @param installedPackages 已安装且能解析地图 Intent 的包名集合
         * @param point 目标坐标（WGS84）
         * @param label 地点名
         * @param appPackage 本应用包名
         */
        fun build(
            installedPackages: Set<String>,
            point: GeoPoint,
            label: String?,
            appPackage: String,
        ): MapEntryPlan {
            if (!point.isValid) return MapEntryPlan(emptyList())
            val mapApps = MapTarget.MAP_APPS
                .filter { it.packageName in installedPackages }
                .mapNotNull { target ->
                    MapUri.uriFor(target, point, label, appPackage)?.let { MapCandidate(target, it) }
                }
            if (mapApps.isNotEmpty()) return MapEntryPlan(mapApps)
            val browser = MapUri.uriFor(MapTarget.BROWSER, point, label, appPackage)
                ?.let { MapCandidate(MapTarget.BROWSER, it) }
            return MapEntryPlan(listOfNotNull(browser))
        }
    }
}

object MapLauncher {

    /**
     * 已装且真的能解析地图 Intent 的目标（浏览器不在其中）。
     *
     * 两步都做：先看包是否已装（便宜），再 resolveActivity 复核（装了但没注册 scheme 的 App 存在）。
     * 任一步抛异常都视为「不可用」——包管理器在极端状态下会抛，不能让它把整屏拖崩。
     */
    fun installedMapPackages(context: Context, point: GeoPoint, label: String?): Set<String> {
        val pm = context.packageManager
        val appPackage = context.packageName
        return MapTarget.MAP_APPS.mapNotNullTo(mutableSetOf()) { target ->
            if (!isInstalled(pm, target.packageName)) return@mapNotNullTo null
            val uri = MapUri.uriFor(target, point, label, appPackage) ?: return@mapNotNullTo null
            if (canResolve(context, intentFor(target, uri))) target.packageName else null
        }
    }

    /**
     * 算出当前设备可用的候选（地图 App 优先，浏览器仅在没装任何地图时兜底）。
     *
     * 浏览器是否真的可用在这里复核：一个地图都没装、又没有任何能响应 https 的浏览器时
     * 返回空候选，由 UI 给「没有可用的地图或浏览器应用」，而不是打开一个空页面。
     */
    fun planFor(context: Context, point: GeoPoint, label: String?): MapEntryPlan {
        if (!point.isValid) return MapEntryPlan(emptyList())
        val installed = installedMapPackages(context, point, label)
        // 有地图就只要地图：不为兜底多查一次包管理器，也不会把网页混进选择器
        if (installed.isNotEmpty()) {
            return MapEntryPlan.build(installed, point, label, context.packageName)
        }
        val browserUri = MapUri.uriFor(MapTarget.BROWSER, point, label, context.packageName)
            ?: return MapEntryPlan(emptyList())
        return if (canResolve(context, intentFor(MapTarget.BROWSER, browserUri))) {
            MapEntryPlan(listOf(MapCandidate(MapTarget.BROWSER, browserUri)))
        } else {
            MapEntryPlan(emptyList())
        }
    }

    /**
     * 打开候选。[candidates] 里没有指向它的 App 时返回 false（不抛）。
     *
     * 全部 runCatching：地图 App 被冻结 / 权限被收 / 版本不认该 scheme 都表现为异常或
     * ActivityNotFoundException，这些都不该让行程页崩掉，返回 false 由调用方给文案。
     */
    fun open(context: Context, candidate: MapCandidate): Boolean = runCatching {
        val intent = intentFor(candidate.target, candidate.uri)
        // 显式指定包名只对地图 App 做；浏览器按 scheme 交给系统选（用户可能装了多个）
        context.startActivity(intent)
        true
    }.getOrDefault(false)

    /** 目标 + URI → Intent（地图 App 显式钉包名，避免被别的 App 抢答）。 */
    fun intentFor(target: MapTarget, uri: String): Intent {
        val intent = Intent(Intent.ACTION_VIEW, uri.toUri())
        if (target.packageName.isNotEmpty()) intent.setPackage(target.packageName)
        return intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** 包是否已装；查询抛异常时按「没装」处理（宁可少一个候选，不要崩）。 */
    private fun isInstalled(pm: PackageManager, packageName: String): Boolean = runCatching {
        pm.getPackageInfo(packageName, 0)
        true
    }.getOrDefault(false)

    /** Intent 是否有 App 能响应（Android 11+ 受 `<queries>` 包可见性约束）。 */
    private fun canResolve(context: Context, intent: Intent): Boolean = runCatching {
        context.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY) != null
    }.getOrDefault(false)
}
