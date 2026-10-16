# uiautomator dump 会踢掉无障碍服务

> ⚠️ **何时阅读：** 调试无障碍功能时「日志说浮窗弹了、截图却看不到」，或验证过程中浮窗 / 跳过弹幕莫名消失时。

## 现象

验证自动记账浮窗时，日志明明显示浮窗已经挂上：

```
D AutoBook: 识别到账单 channel=alipay amount=81.0 ...
I WindowManager: wms.showSurfaceRobustly mWin:Window{a114e1d u0 com.bsfeng.monitor}
```

但 `adb shell screencap` 截出来的图上**根本没有浮窗**，页面干干净净。

## 根因

`adb shell uiautomator dump` 会启动 UiAutomation，它在建立连接时会**断开设备上其它正在运行的无障碍服务**：

```
I vendor.qti...: unbindService is called for service : com.bsfeng.monitor/.service.MonitorAccessibilityService
I vendor.qti...: destroyService is called for service : com.bsfeng.monitor/.service.MonitorAccessibilityService
```

服务被销毁 → `onDestroy` → `AutoBookEngine.release()` → `overlay.hide()`，浮窗自然就没了。**不是浮窗没弹，是被我们自己收掉了。**

同一批日志里 `uiautomator` 的启动行紧跟在服务销毁行之前，就是铁证：

```
D AndroidRuntime: Calling main entry com.android.commands.uiautomator.Launcher
I vendor.qti...: unbindService is called for service : com.bsfeng.monitor/.service.MonitorAccessibilityService
```

## 解决

1. **先截图，再 dump** —— 需要截图取证时，不要在同一次 shell 里先跑 `uiautomator dump`；
2. **用日志确认浮窗是否真的挂上**，别只看截图：

```bash
# 浮窗挂上时 WindowManager 会打出这一行（包名就是本 App）
~/android-platform-tools/adb logcat -d | grep 'showSurfaceRobustly.*com.bsfeng.monitor'
```

3. 需要长时间观察浮窗时用**截图**判断，dump 只留给「页面文本是什么」这一件事。

## 教训

调试验证工具本身会改变被验证对象的状态。凡是「日志成功、截图失败」的诡异现象，先查**取证动作有没有副作用**，
而不是先怀疑功能没生效。同类问题还有：[第三方悬浮窗顶掉无障碍根窗口](第三方悬浮窗顶掉无障碍根窗口.md)（取证环境本身干扰读取）。