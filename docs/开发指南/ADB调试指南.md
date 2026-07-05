# ADB 调试指南 — macOS + 小米 14 (HyperOS)

> ⚠️ **先读本文档，再执行 adb 相关操作。** 涉及手机连接、APK 安装、日志抓取等调试任务时，必须先阅读本文档。

## 环境说明

| 项目 | 当前配置 |
|------|---------|
| 开发机 | macOS (Darwin 25.5.0, arm64) |
| 调试手机 | 小米 14 (型号: 23127PN0CC / 代号: houji) |
| 手机系统 | HyperOS (基于 Android) |
| ADB 路径 | `~/Downloads/platform-tools/adb` |
| ADB 版本 | 37.0.0 |
| JDK | 21（`export JAVA_HOME=/opt/homebrew/opt/openjdk@21`） |

## ADB 安装与配置

### 安装方式

由于当前环境**无 sudo 权限**，ADB 通过直接下载 Google 官方 Platform Tools 并解压到用户目录的方式安装：

```bash
# 下载 Platform Tools（如未安装）
curl -L -o ~/Downloads/platform-tools-latest-darwin.zip \
  "https://dl.google.com/android/repository/platform-tools-latest-darwin.zip"

# 解压
unzip -o ~/Downloads/platform-tools-latest-darwin.zip -d ~/Downloads/
```

### PATH 配置

ADB 路径已写入 `~/.zshrc`，内容如下：

```bash
export PATH="$HOME/Downloads/platform-tools:$PATH"
```

**新终端**直接使用 `adb` 命令即可；**当前会话**请使用全路径 `~/Downloads/platform-tools/adb`。

### 验证安装

```bash
# 验证版本
~/Downloads/platform-tools/adb --version
# 输出: Android Debug Bridge version 1.0.41
#       Version 37.0.0-14910828
```

## 手机端设置（小米 14 / HyperOS）

首次连接小米 14 前，必须在手机上完成以下步骤：

### 1. 开启开发者选项

```
设置 → 我的设备 → 全部参数 → 连续点击「HyperOS 版本」7 次
```
直到看到提示「您现在处于开发者模式」。

### 2. 开启 USB 调试

```
设置 → 更多设置 → 开发者选项 → 开启「USB 调试」
```

### 3. 开启额外授权（HyperOS 必需）

在开发者选项中，额外开启以下开关：

| 开关名称 | 用途 | 必需性 |
|---------|------|--------|
| **USB 安装** | 允许通过 ADB 安装应用 | ★ 必需 |
| **USB 调试（安全设置）** | 允许模拟点击、输入等高级操作 | ★ 推荐 |
| **断崖式权限监控** → 关闭 | 部分 HyperOS 版本会拦截 ADB 操作 | ☆ 按需 |

### 4. 连接后切换 USB 模式

```
用 USB 数据线连接 Mac → 下拉通知栏 → 
点击「正在通过 USB 充电」→ 选择「文件传输 (MTP)」
```

> ⚠️ **必须使用支持数据传输的 USB 线。** 部分充电线不支持数据传输，会导致设备无法识别。

## ADB 连接流程

### 标准连接步骤

```bash
# 1. 确认 ADB 守护进程运行中
~/Downloads/platform-tools/adb start-server

# 2. 查看已连接设备
~/Downloads/platform-tools/adb devices
```

### 正常输出

```
List of devices attached
a1b2c3d4               device usb:1-1 product:houji model:23127PN0CC device:houji transport_id:2
```

### 设备状态说明

| 状态 | 含义 | 处理方式 |
|------|------|---------|
| `device` | 已授权并可正常使用 | ✅ 可直接进行操作 |
| `unauthorized` | 手机未授权 RSA 指纹 | 🔧 拔掉重连，在手机上点「允许/确定」 |
| `offline` | 连接断开 | 🔧 `adb kill-server && adb start-server` 后重试 |
| `no device` | 列表为空 | 🔧 检查 USB 线、USB 模式、驱动 |

### 第一步必须做：README 检查表

每次需要 adb 操作时，按照以下顺序检查：

```
检查顺序: 
  ① ADB 已安装 → ② PATH 已配置 → ③ 手机已 USB 连接 → 
  ④ USB 模式为 MTP → ⑤ 手机上已授权 → ⑥ adb devices 显示 device
```

## 安装 APK

### 安装命令

```bash
# 安装（首次安装）
~/Downloads/platform-tools/adb install ~/Downloads/app-debug.apk

# 覆盖安装（保留应用数据，推荐日常使用）
~/Downloads/platform-tools/adb install -r ~/Downloads/app-debug.apk
```

### 安装成功标志

```
Performing Streamed Install
Success
```

### 安装失败处理

| 错误信息 | 原因 | 解决方法 |
|---------|------|---------|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名不一致 | 先卸载旧版：`adb uninstall com.bsfeng.monitor` |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | 版本号低于已安装版本 | 使用 `-r -d` 参数允许降级安装 |
| `INSTALL_PARSE_FAILED_NO_CERTIFICATES` | APK 未签名 | 确认使用 `assembleDebug` 编译，不是未签名产物 |
| `error: device offline` | 设备连接异常 | 见上节「设备状态说明」 |
| `error: no devices/emulators found` | 未检测到设备 | 参见「未连接手机处理流程」 |
| `FAILED (remote: 'Unknown failure')` | 手机端拦截 | 检查手机是否弹出授权弹窗，确认 USB 安装已开启 |

### 降级安装

```bash
# 当需要安装版本号更低的 APK 时（-d 允许降级）
~/Downloads/platform-tools/adb install -r -d ~/Downloads/app-debug.apk
```

## 全流程自动化命令

> 每次代码改动后执行，一行完成：测试 → 编译 → 复制到 Downloads → 安装到手机。

```bash
# 标准全流程（手机已连接正常）
./gradlew test && ./gradlew assembleDebug \
  && cp app/build/outputs/apk/debug/app-debug.apk ~/Downloads/ \
  && ~/Downloads/platform-tools/adb install -r ~/Downloads/app-debug.apk
```

## 未连接手机处理流程

当 `adb devices` 返回空列表时，按以下顺序排查：

### 检查清单

```
□ 检查 USB 线是否支持数据传输（而非仅充电）
  → 换线测试，优先使用手机原装线

□ 检查手机 USB 模式是否设为「文件传输 (MTP)」
  → 下拉通知栏 → 点 USB 通知 → 选 MTP

□ 检查开发者选项是否开启
  → 设置 → 更多设置 → 开发者选项 → 确认开关为绿色

□ 检查 USB 调试是否开启
  → 开发者选项 → USB 调试 → 确认已开启

□ 检查 USB 安装是否开启
  → 开发者选项 → USB 安装 → 确认已开启

□ 拔掉 USB 线后重新连接
  → 连接后注意手机是否有授权弹窗

□ 重启 ADB 服务
  → ~/Downloads/platform-tools/adb kill-server
  → ~/Downloads/platform-tools/adb start-server
  → ~/Downloads/platform-tools/adb devices

□ 重启手机
  → 长按电源键 → 重启
```

### 临时跳过方案

如果**无法连接手机**，APK 安装步骤应**跳过**，不要阻塞编译流程：

```bash
# 仅执行测试和编译，跳过 adb 安装
./gradlew test && ./gradlew assembleDebug \
  && cp app/build/outputs/apk/debug/app-debug.apk ~/Downloads/
```

> ⚠️ **即使跳过安装，也必须完成编译和复制 APK 到 Downloads。**

## 未授权处理流程

当设备显示 `unauthorized` 状态时：

### 首次连接授权

```
① 拔掉 USB 线
② 重新插上
③ 观察手机屏幕是否有 RSA 密钥指纹确认弹窗
④ 弹窗中勾选「一律允许使用此计算机进行调试」
⑤ 点击「确定」
⑥ 在终端重新运行 adb devices 验证
```

### 如果始终不弹窗

```
① 关闭 USB 调试 → 重新开启
② 进入开发者选项 → 点击「撤销 USB 调试授权」→ 确认撤销
③ 拔掉 USB 线 → 重新连接
④ 手机应弹出授权弹窗
⑤ 勾选「一律允许」→ 点击「确定」
⑥ 验证: adb devices → 显示 device
```

## 日志抓取

### 查看应用日志

```bash
# 实时查看本应用所有日志（推荐）
~/Downloads/platform-tools/adb logcat -v time | grep -i "monitor\|bsfeng"

# 查看应用崩溃日志
~/Downloads/platform-tools/adb logcat -v time -s AndroidRuntime:E

# 查看具体日志级别
~/Downloads/platform-tools/adb logcat -v time *:V     # 所有日志
~/Downloads/platform-tools/adb logcat -v time *:W     # 警告及以上
~/Downloads/platform-tools/adb logcat -v time *:E     # 仅错误
```

### 清空日志缓冲区

```bash
~/Downloads/platform-tools/adb logcat -c
```

### 保存日志到文件

```bash
# 抓取日志并保存到文件
~/Downloads/platform-tools/adb logcat -v time -d > ~/Downloads/debug_log_$(date +%Y%m%d_%H%M%S).txt
```

> 注意：`adb logcat` 会持续输出日志，需按 `Ctrl+C` 停止。使用 `-d` 参数可在 dump 日志后自动退出。

## 其他常用 ADB 命令

```bash
# 查看设备详细信息
~/Downloads/platform-tools/adb devices -l

# 卸载应用
~/Downloads/platform-tools/adb uninstall com.bsfeng.monitor

# 重启 ADB 服务
~/Downloads/platform-tools/adb kill-server
~/Downloads/platform-tools/adb start-server

# 重启手机
~/Downloads/platform-tools/adb reboot

# 查看应用包信息
~/Downloads/platform-tools/adb shell dumpsys package com.bsfeng.monitor | head -30

# 查看应用权限
~/Downloads/platform-tools/adb shell dumpsys package com.bsfeng.monitor | grep -A 100 "runtime permissions"

# 模拟输入（需要「USB 调试（安全设置）」开启）
~/Downloads/platform-tools/adb shell input tap x y           # 模拟点击
~/Downloads/platform-tools/adb shell input text "hello"      # 模拟输入
~/Downloads/platform-tools/adb shell input swipe x1 y1 x2 y2 # 模拟滑动
```

## 常见问题 FAQ

### Q: `adb` 命令找不到（command not found）

**原因：** PATH 未包含 ADB 目录。

**解决：**
```bash
# 方式一：使用全路径（推荐）
~/Downloads/platform-tools/adb devices

# 方式二：重新加载 .zshrc
source ~/.zshrc
adb devices
```

### Q: 每次打开新终端都要全路径？

**原因：** `~/.zshrc` 没自动执行。

**解决：** 确认 PATH 设置已生效：
```bash
echo $PATH | grep platform-tools
```
如有输出则正常；如没有，手动执行 `source ~/.zshrc`。

### Q: 手机连接后充电但 `adb devices` 找不到？

**原因：** USB 模式为「仅充电」或 USB 线不支持数据传输。

**解决：** 下拉通知栏切换 USB 模式为「文件传输 (MTP)」，或换原装数据线。

### Q: 安装了新版本 APK 后报签名错误？

**原因：** 系统上残留了其他签名的同包名应用。

**解决：** 卸载后重装：
```bash
~/Downloads/platform-tools/adb uninstall com.bsfeng.monitor
~/Downloads/platform-tools/adb install ~/Downloads/app-debug.apk
```

### Q: HyperOS 通知拦截了 ADB 授权弹窗？

**原因：** HyperOS 的权限管理较严格，可能隐藏了授权弹窗。

**解决：**
1. 确保手机解锁且亮屏
2. 下拉通知栏查看是否有「USB 调试」通知
3. 进入「开发者选项」关闭「断崖式权限监控」（如存在）
4. 撤销 USB 调试授权后重新尝试
