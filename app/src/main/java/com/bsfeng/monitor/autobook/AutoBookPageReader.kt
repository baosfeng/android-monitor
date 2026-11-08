package com.bsfeng.monitor.autobook

// 从 autobook/AutoBookEngine.kt 拆出：从无障碍窗口读取页面文本，以及读不到时的诊断日志。
// 读法要点：不直接用 rootInActiveWindow（会被第三方悬浮窗顶掉），也不用「第一个包名匹配的窗口」
// （微信会挂多个窗口，栈底常是空壳）—— 逐个候选窗口试读，取内容最丰富的那个。

import android.util.Log
import android.view.accessibility.AccessibilityWindowInfo
import com.bsfeng.monitor.util.DebugConfig

/**
 * 取目标 App 的页面文本。
 *
 * ⚠️ **不能用 `rootInActiveWindow` 直接读**：它返回的是「活动窗口」，只要有第三方悬浮窗盖在
 * 支付页上（真机实测：熊猫记账的记账确认窗一直在支付宝页面上），读到的就是那个悬浮窗的文本，
 * 支付页一个字段都拿不到。
 *
 * ⚠️ **也不能只取「第一个包名匹配的窗口」**：微信会同时挂好几个窗口（真机实测
 * `LauncherUI` / `MallIndexUIv2` / `UIPageFragmentActivity` / `MMWebViewUI`），
 * 栈底那个 `LauncherUI` 往往是个读不出内容的空壳 —— 于是出现「明明停在支付成功页/转账详情页，
 * 却一直提示未收集到页面文本」。这里逐个候选窗口试读，**取内容最丰富的那个**。
 *
 * 依赖无障碍配置里的 `flagRetrieveInteractiveWindows`（本项目已开启）。
 */
internal fun AutoBookEngine.collectPageLines(packageName: String): List<String> {
    val windows = try {
        service.windows
    } catch (e: Exception) {
        Log.w(AutoBookEngine.TAG, "读取窗口列表失败: ${e.message}")
        null
    }

    if (windows.isNullOrEmpty()) {
        // 兜底：活动窗口恰好就是目标 App（没有第三方悬浮窗时走这里）
        val active = try {
            service.rootInActiveWindow
        } catch (e: Exception) {
            null
        } ?: return emptyList()
        return try {
            if (active.packageName?.toString() == packageName) {
                NodeTextCollector.collectLines(active)
            } else {
                emptyList()
            }
        } finally {
            runCatching { active.recycle() }
        }
    }

    // 活动窗口优先试，其余保持系统给的顺序
    val ordered = windows.sortedByDescending { window ->
        (if (window.isActive) 2 else 0) + (if (window.isFocused) 1 else 0)
    }

    var best: List<String> = emptyList()
    val tried = mutableListOf<String>()
    for (window in ordered) {
        val root = try {
            window.root
        } catch (e: Exception) {
            null
        } ?: continue
        val lines = try {
            if (root.packageName?.toString() == packageName) {
                NodeTextCollector.collectLines(root)
            } else {
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(AutoBookEngine.TAG, "收集页面文本失败: ${e.message}")
            emptyList()
        } finally {
            runCatching { root.recycle() }
        }
        if (lines.isNotEmpty()) tried += "type=${window.type}:${lines.size}行"
        if (lines.size > best.size) best = lines
    }

    if (best.isEmpty()) {
        Log.d(AutoBookEngine.TAG, "未取到 $packageName 的有内容窗口，候选=[${tried.joinToString(", ")}]")
        // 光凭「候选=[]」分不清「窗口不在清单里」和「窗口在但树是空的」，这两者应对完全不同
        logWindowDiagnostics(packageName, windows)
    }
    return best
}

/**
 * 诊断：页面文本为空时，再看一眼**节点是不是被 App 标记成不可见**了。
 *
 * 微信会按「无障碍服务身份」把支付页 / 聊天页 / WebView 页的节点标记为不可见并把 bounds 归零，
 * 于是正常收集为空。此时忽略坐标再读一遍，结果能区分两种成因：
 * - **能读到文本** → 节点树还在，只是被标记不可见 → 改采集策略还有救；
 * - **仍然是空** → 节点树被系统裁掉了 → 只能换通道（通知 / 截图 OCR / 伪装服务）。
 *
 * 详见 docs/记账/微信支付页识别调研.md 的「待真机验证的实验清单」。
 */
internal fun AutoBookEngine.logInvisibleNodesIfAny(packageName: String) {
    val windows = try {
        service.windows
    } catch (e: Exception) {
        null
    } ?: return

    for (window in windows) {
        val root = try {
            window.root
        } catch (e: Exception) {
            null
        } ?: continue
        try {
            if (root.packageName?.toString() != packageName) continue
            val invisible = NodeTextCollector.collect(root, ignoreBounds = true)
            if (invisible.isNotEmpty()) {
                // 结构性结论照常打：这条决定了「改采集策略还有没有救」
                Log.d(
                    AutoBookEngine.TAG,
                    "诊断：$packageName 有 ${invisible.size} 个文本节点被标记为不可见（bounds 归零）",
                )
                // 节点样本是屏幕原文（金额 / 商户 / 聊天内容）：只在调试闸门后出现
                val sample = invisible.take(12).joinToString(" | ") { it.text }
                DebugConfig.log("AutoBook 不可见节点样本: [$sample]")
            }
        } catch (e: Exception) {
            // 诊断失败绝不能影响主流程
        } finally {
            runCatching { root.recycle() }
        }
    }
}

/**
 * 诊断：把「无障碍交互窗口清单」原样打进日志。
 *
 * 读不到页面时，只靠 `候选=[]` 无法区分两种成因，而它们的应对完全不同：
 * - **清单里根本没有目标 App 的窗口** → 窗口枚举被系统或第三方悬浮窗影响
 *   （真机踩过：熊猫记账的悬浮窗会顶掉根窗口，见 docs/踩坑/第三方悬浮窗顶掉无障碍根窗口.md）；
 * - **窗口在，但 `childCount=0` / 文本数为 0** → App 端把节点树藏起来了（微信就是这种）。
 *
 * 真机实测（小米 14 / HyperOS，2026-10-16）微信「支付成功」页与微信首页都落在第二种，
 * 完整证据与可行通道见 docs/记账/微信支付页识别调研.md。
 */
internal fun AutoBookEngine.logWindowDiagnostics(packageName: String, windows: List<AccessibilityWindowInfo>) {
    val parts = windows.map { window ->
        val root = try {
            window.root
        } catch (e: Exception) {
            null
        }
        try {
            val pkg = root?.packageName?.toString() ?: "null"
            val childCount = root?.childCount ?: -1
            val textCount = if (root != null) NodeTextCollector.collect(root).size else -1
            "type=${window.type},pkg=$pkg,childCount=$childCount,文本=$textCount"
        } catch (e: Exception) {
            "type=${window.type},读取异常=${e.message}"
        } finally {
            runCatching { root?.recycle() }
        }
    }
    Log.d(
        AutoBookEngine.TAG,
        "窗口诊断: 目标=$packageName, 交互窗口共 ${windows.size} 个 -> [${parts.joinToString("; ")}]"
    )
}
