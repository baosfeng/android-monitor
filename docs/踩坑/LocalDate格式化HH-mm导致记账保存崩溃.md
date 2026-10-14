# 踩坑：`LocalDate.format("HH:mm")` 让记账保存崩溃，表现却像「点保存跳到了别的 App」

## 问题描述

在记账「新增交易」页填好金额、点右上角「保存」后，**App 没有保存，前台却切到了支付宝**（或其它后台任务）。
连续复现 3 次，一度被误判为「系统侧边栏 / 手势误触」。

logcat 里的真实原因：

```
E/AndroidRuntime: FATAL EXCEPTION: main
  Process: com.bsfeng.monitor, PID: 24326
  java.time.temporal.UnsupportedTemporalTypeException: Unsupported field: HourOfDay
      at java.time.LocalDate.get0(LocalDate.java:704)
      at java.time.format.DateTimeFormatter.format(DateTimeFormatter.java:1821)
      at java.time.LocalDate.format(LocalDate.java:1810)
      at com.bsfeng.monitor.finance.ui.AddTransactionScreenKt
           .AddTransactionScreen$buildTransaction(AddTransactionScreen.kt:97)
```

## 根本原因

两个问题叠加：

1. **代码错误**：`LocalDate` 只有年月日，没有时刻字段；用 `DateTimeFormatter.ofPattern("HH:mm")` 去格式化它
   必然抛 `UnsupportedTemporalTypeException`。
   （原始写法：`"$dateText ${LocalDate.now().format(DateTimeFormatter.ofPattern("HH:mm"))}"`）
2. **现象误导**：崩溃后 Activity 被销毁，系统把焦点还给任务栈里的上一个任务（此前误开过的支付宝），
   从用户视角看就像「点了保存结果跳到了支付宝」。

logcat 里的关键证据是 **`ActivityRecord.finishIfPossible` → `moveTaskToFront` 别人**：

```
I/ActivityTaskManager: moveTaskToFront: Task{... A=10338:com.eg.android.AlipayGphone}
  caller trace: ... ActivityRecord.finishIfPossible:4288 ... moveToFront:6298
```

如果是**我们的 Activity 被 finish**（而不是别人被启动），日志形态就是上面这样。

## 解决方案

时刻必须取自 `LocalDateTime` / `LocalTime`，并抽成**可测的纯函数**：

```kotlin
// app/src/main/java/com/bsfeng/monitor/finance/model/Transaction.kt
companion object {
    fun buildDateTime(date: String, now: LocalDateTime = LocalDateTime.now()): String =
        "$date ${now.format(DateTimeFormatter.ofPattern("HH:mm"))}"
}

// AddTransactionScreen.kt（UI 只负责调用）
val dateTime = Transaction.buildDateTime(dateText)
```

## 验证方法与回归测试

- 回归测试：`app/src/test/java/com/bsfeng/monitor/finance/FinanceModelTest.kt` 中「交易时间戳」4 个用例
  （正常拼接、00:00 / 23:59 边界、跨日组合、默认取当前时刻）；
- 反向验证：把实现临时改回 `now.toLocalDate().format(...)`，4 个用例全部以
  `UnsupportedTemporalTypeException` 失败 —— 证明测试真的能抓住这个 bug。

## 排查经验（重要）

**「点击后跳到了别的 App」不要先怀疑系统手势/侧边栏，先查自己的崩溃日志：**

```bash
A=~/android-platform-tools/adb
# 1. 先看有没有崩溃
$A logcat -d | grep -iE "FATAL|AndroidRuntime"
# 2. 复现时抓「谁发起的跳转」
$A logcat -c; $A shell input tap <x> <y>; $A logcat -d | grep -E "START u0|moveTaskToFront|finishIfPossible"
```

## 相关文件

- 时间戳函数与回归测试：`app/src/main/java/com/bsfeng/monitor/finance/model/Transaction.kt`、`app/src/test/java/com/bsfeng/monitor/finance/FinanceModelTest.kt`
- 调用方：`app/src/main/java/com/bsfeng/monitor/finance/ui/AddTransactionScreen.kt`（`buildTransaction()`）
- 相邻踩坑：[记账首页 FAB 被底部导航栏遮住](记账FAB被底部导航栏遮住导致无法新增交易.md)
