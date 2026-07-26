package com.bsfeng.monitor.skip

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

class ExactRuleInterceptor : AdInterceptor {
    override fun intercept(ctx: AdContext): Boolean = false
}

class GenericIdInterceptor : AdInterceptor {
    override fun intercept(ctx: AdContext): Boolean = false
}

class TextMatchInterceptor : AdInterceptor {
    override fun intercept(ctx: AdContext): Boolean = false
}

class CoordinateClickInterceptor : AdInterceptor {
    override fun intercept(ctx: AdContext): Boolean = false
}

class GlobalBackInterceptor : AdInterceptor {
    override fun intercept(ctx: AdContext): Boolean = false
}
