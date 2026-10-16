package com.google.android.accessibility.selecttospeak

import com.bsfeng.monitor.service.MonitorAccessibilityService

/**
 * 微信无障碍白名单身份（伪装壳）。
 *
 * ## 为什么要有这个类
 *
 * 微信（实测 8.0.79）对不在它白名单里的第三方无障碍服务**隐藏整棵页面节点树**：
 * 用 `AccessibilityService.getWindows()` 拿到微信窗口后 `root.childCount == 0`，
 * 而同一时刻读 systemui 窗口完全正常 —— 不是我们的采集器问题（真机窗口诊断日志为证）。
 * 已实测证伪的路径：给服务加 `android:isAccessibilityTool="true"`（系统确实读到了该属性，
 * 服务重连日志打印 true，微信仍不放行）。
 *
 * 微信的白名单**只比对无障碍服务 ID 的后半段类名、不校验包名**（出处：AutoJs6 issue #463，
 * 作者 SuperMonster003）。因此业界标准做法是把服务类名撞成系统读屏服务的名字：
 * GKD（`gkd-app/src/androidMain/kotlin/com/google/android/accessibility/selecttospeak/SelectToSpeakService.kt`，
 * 其项目文档把「SelectToSpeakService 的包名、路径和注册不变」写成长期设计）、
 * AutoAccounting、熊猫记账都采用这条通路。
 *
 * ## 这个类做什么
 *
 * **什么都不做**：不重写任何回调，只是一个注册身份，让
 * [com.bsfeng.monitor.service.MonitorAccessibilityService] 以白名单身份出现在系统的
 * 「已启用的无障碍服务」列表里。全部逻辑仍在父类。
 *
 * ## 改这个类之前先读这里
 *
 * - **消耗品**：微信一旦加上包名校验，这条通路立即失效（AutoJs6 作者正是因此拒绝做同类适配）；
 * - **违反平台意图**：服务名与真实用途不符，**仅供侧载自用，不要用它上架**；
 * - 完整证据与真机验证结论：docs/记账/微信支付页识别调研.md
 */
class SelectToSpeakService : MonitorAccessibilityService()
