package com.bsfeng.monitor.skip

import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo

interface AdInterceptor {
    fun intercept(ctx: AdContext): Boolean
}

class AdContext(
    val packageName: String,
    val className: String,
    val rootNode: AccessibilityNodeInfo,
    val isSplash: Boolean = false,
    val hasMatchingRuleGroup: Boolean = false,
    val canRunFullChain: Boolean = false,
    val ruleManager: SkipRuleManager? = null,
    val engine: SkipRuleEngine? = null,
    val actionCounters: MutableMap<String, Int> = mutableMapOf(),
    val actionCooldowns: MutableMap<String, Long> = mutableMapOf()
)

/**
 * 精确规则拦截器：使用规则管理器中定义的选择器规则匹配跳过按钮。
 */
class ExactRuleInterceptor : AdInterceptor {
    override fun intercept(ctx: AdContext): Boolean {
        if (!ctx.hasMatchingRuleGroup) return false
        val rule = ctx.ruleManager?.findEnabledRule(ctx.packageName) ?: return false
        val engine = ctx.engine ?: return false
        if (engine.executeExactRules(rule.id, rule, ctx.rootNode)) {
            Log.d(TAG, "ExactRule 命中: ${rule.name}")
            return true
        }
        return false
    }
}

/**
 * 通用 ID 拦截器：使用通用跳过按钮 View ID 选择器匹配。
 * 不依赖内置规则，通过广告 SDK 常见的 View ID 模式匹配。
 */
class GenericIdInterceptor : AdInterceptor {
    override fun intercept(ctx: AdContext): Boolean {
        val engine = ctx.engine ?: return false
        return engine.matchSelectors(ctx.packageName, ctx.rootNode, SkipRuleEngine.GENERIC_SELECTORS)
    }
}

/**
 * 文本匹配拦截器：使用文本选择器匹配"跳过""关闭"等按钮。
 * 仅当确认是开屏页(canRunFullChain=true)时使用，防止误触正常页面。
 */
class TextMatchInterceptor : AdInterceptor {
    override fun intercept(ctx: AdContext): Boolean {
        if (!ctx.canRunFullChain) return false
        val engine = ctx.engine ?: return false
        return engine.matchSelectors(ctx.packageName, ctx.rootNode, SkipRuleEngine.TEXT_SELECTORS)
    }
}

/**
 * 坐标点击拦截器：在屏幕右上角区域依次点击，处理 WebView/Flutter 等
 * 无障碍树不暴露内部元素的场景。
 * 仅当确认是开屏页时使用，每个 App 每个 session 只尝试一次。
 */
class CoordinateClickInterceptor : AdInterceptor {
    override fun intercept(ctx: AdContext): Boolean {
        if (!ctx.canRunFullChain) return false
        val engine = ctx.engine ?: return false
        return engine.tryCoordinateClick(ctx.rootNode)
    }
}

/**
 * 全局返回拦截器：终极兜底，模拟按下返回键尝试关闭广告弹窗。
 * 仅当确认是开屏页时使用。
 */
class GlobalBackInterceptor : AdInterceptor {
    override fun intercept(ctx: AdContext): Boolean {
        if (!ctx.canRunFullChain) return false
        return ActionExecutor.performGlobalBack()
    }
}

private const val TAG = "AdInterceptor"
