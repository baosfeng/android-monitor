package com.bsfeng.monitor.click

import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.bsfeng.monitor.util.DebugConfig

/**
 * 微信（com.tencent.mm）专用点击文本处理器。
 *
 * 在微信聊天列表（LauncherUI）中，点击某聊天项时 AccessibilityEvent 的
 * event.text 可能只包含时间戳（如"21:16"），而非群聊名称。
 *
 * 解决思路：从被点击视图沿祖先链往上爬，找到有多个子节点且含长文本的
 * 聊天行容器，从中提取群聊/联系人名称。
 */
class WeChatClickHandler : AppClickHandler {

    override val packageName = "com.tencent.mm"

    override fun resolveClickDisplayText(
        event: AccessibilityEvent,
        clickText: String?
    ): String? {
        // 默认文本已有意义（长度 > 5 通常不是时间戳）→ 不需要处理器介入
        if (clickText != null && clickText.length > 5) return null

        return try {
            val source = event.source ?: return null
            DebugConfig.log("WeChatClickHandler: source class=${source.className}, clickText=$clickText")

            // 沿祖先链往上爬，最多 10 层
            val texts = mutableListOf<String>()
            var node: AccessibilityNodeInfo? = source
            var level = 0
            while (node != null && level < 10 && texts.isEmpty()) {
                val parent = node.parent
                if (parent == null) {
                    DebugConfig.log("WeChatClickHandler: level=$level, parent=null, stop")
                    break
                }

                val childCount = parent.childCount
                DebugConfig.log("WeChatClickHandler: level=$level, parent class=${parent.className}, childCount=$childCount")

                for (i in 0 until childCount) {
                    val child = try { parent.getChild(i) } catch (_: Exception) { null } ?: continue
                    val childText = child.text?.toString()
                    val childDesc = child.contentDescription?.toString()
                    val childCls = child.className?.toString() ?: ""

                    // 只在文本节点（TextView）中搜索，跳过布局容器
                    if (childCls.contains("TextView") || childCls.contains("EditText")) {
                        DebugConfig.log("WeChatClickHandler:   textView[$i] text=$childText, desc=$childDesc")
                        childText?.takeIf {
                            it.isNotBlank() && it.length >= 4 && it != clickText
                        }?.let { texts.add(it.trim()) }
                    } else {
                        DebugConfig.log("WeChatClickHandler:   layout[$i] class=${childCls.takeLast(20)}, text=$childText, desc=$childDesc")
                        // 非 TextView 但直接有长文本也可以
                        childText?.takeIf {
                            it.isNotBlank() && it.length >= 4 && it != clickText
                        }?.let { texts.add(it.trim()) }
                    }
                    child.recycle()
                }
                node = parent
                level++
            }
            source.recycle()

            val result = texts.maxByOrNull { it.length }
            DebugConfig.log("WeChatClickHandler: climbed $level levels, found ${texts.size} texts: $texts -> resolved='$result'")
            if (result != null) {
                Log.d("WeChatClickHandler", "resolved: '$clickText' -> '$result'")
            }
            result
        } catch (e: Exception) {
            DebugConfig.log("WeChatClickHandler: exception=${e.message}")
            null
        }
    }
}
