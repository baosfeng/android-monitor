# ADB 调试指南 — macOS + 小米 14 (HyperOS)

> 执行任何 adb 操作前先读本文。

## 环境

| 项 | 值 |
|---|---|
| 开发机 | macOS (arm64) |
| 调试手机 | 小米 14（23127PN0CC / houji），HyperOS |
| ADB | `~/android-platform-tools/adb`（37.0.0，无 sudo 权限，解压到用户目录） |
| JDK | 25：`export JAVA_HOME=/opt/homebrew/opt/openjdk@25` |

未装时：下载 `platform-tools-latest-darwin.zip`（dl.google.com/android/repository），解压到 `~` 并把 `platform-tools` 改名 `android-platform-tools`。PATH 已写入 `~/.zshrc`；当前会话用全路径更省事。

## 手机端设置（首次连接）

1. 开发者选项：设置 → 我的设备 → 全部参数 → 连点「HyperOS 版本」7 次。
2. 开启 USB 调试：设置 → 更多设置 → 开发者选项 → USB 调试。
3. HyperOS 额外开关：**USB 安装**（必需）、**USB 调试（安全设置）**（推荐，模拟点击/输入需要）、**断崖式权限监控**（部分版本拦截 ADB，按需关闭）。
4. 连接后下拉通知栏 → USB 通知 → 选「文件传输 (MTP)」。必须用支持数据传输的线，纯充电线识别不到。

## 连接与状态

```bash
~/android-platform-tools/adb start-server
~/android-platform-tools/adb devices
# 正常：a1b2c3d4  device usb:1-1 product:houji model:23127PN0CC device:houji transport_id:2
```

| 状态 | 处理 |
|---|---|
| `device` | 可用 |
| `unauthorized` | 拔插重连，手机点「允许」，勾「一律允许使用此计算机进行调试」 |
| `offline` | `adb kill-server && adb start-server` |
| 空列表 | 查线 / USB 模式 / 开发者选项 / USB 调试 / USB 安装，再拔插、重启 adb、重启手机 |

始终不弹授权：关开一次 USB 调试 → 开发者选项「撤销 USB 调试授权」→ 拔插 → 勾「一律允许」。

## 安装 APK

```bash
~/android-platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk   # 覆盖安装，保留数据
~/android-platform-tools/adb install -r -d app/build/outputs/apk/debug/app-debug.apk # 降级
```

成功标志 `Performing Streamed Install` + `Success`。

| 错误 | 原因 | 解决 |
|---|---|---|
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` | 签名不一致 | `adb uninstall com.bsfeng.monitor` 后重装 |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | 版本号更低 | 加 `-d` |
| `INSTALL_PARSE_FAILED_NO_CERTIFICATES` | APK 未签名 | 用 `assembleDebug` 产物 |
| `FAILED (remote: 'Unknown failure')` | 手机端拦截 | 确认授权弹窗与「USB 安装」开关 |

## 🚨 不用 force-stop

`adb shell am force-stop com.bsfeng.monitor` 会让 MIUI/HyperOS **自动移除无障碍服务权限**，必须手动在手机上重开（见[踩坑](../踩坑/MIUI-force-stop导致无障碍服务被自动移除.md)）。替代做法：装新包直接用 `adb install -r`（进程自动切换）；唤醒用 `adb shell am start -n com.bsfeng.monitor/.MainActivity`。

每次改动后的全流程：

```bash
./gradlew test && ./gradlew assembleDebug \
  && ~/android-platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

连不上手机时跳过安装但不能跳过编译：`./gradlew test && ./gradlew assembleDebug`（APK 在 `app/build/outputs/apk/debug/`）。

## 日志

```bash
~/android-platform-tools/adb logcat -v time | grep -i "monitor\|bsfeng"   # 本应用
~/android-platform-tools/adb logcat -v time | grep MonitorTimeline       # 用户操作时间线
~/android-platform-tools/adb logcat -v time -s AndroidRuntime:E          # 崩溃
~/android-platform-tools/adb logcat -c                                   # 清缓冲
~/android-platform-tools/adb logcat -v time -d > ~/Downloads/debug_log_$(date +%Y%m%d_%H%M%S).txt
```

## 调试 CLI（debug 包专用）

debug 包内置 `DebugCommandReceiver`，用广播代替「点坐标」，release 构建里不存在这个类。

```bash
export ANDROID_SERIAL=<serial>
scripts/debug-cli.sh unlock        # 首次：解锁调试层
scripts/debug-cli.sh state         # 开关 / 定位权限 / 各类型当日计数
scripts/debug-cli.sh seed day      # 造数：day | stay | move | gap
scripts/debug-cli.sh open trip     # 直达行程页（不点 Tab）
scripts/debug-cli.sh reset all     # 全表清空；单类型写 reset PAGE
scripts/debug-cli.sh dump 20 PAGE  # 倒最近 20 条
```

结果在 logcat tag `DebugCmd`：`RESULT cmd=<n> ok=<bool> data=<json>`，脚本退出码 = `ok`；`Broadcast completed: result=0` 只代表投递成功。

## 取库数据（db 子命令）

**优先用它代替 `adb pull` 库文件**：真机库 8MB 级，USB 一断就拿到半个文件；库开着 WAL 时只拷主文件还会拷到下坏页。db 命令在进程内只读查询，结果按行分片回传。

```bash
scripts/debug-cli.sh db tables                  # 表名 + 行数 + user_version
scripts/debug-cli.sh db schema records          # 列定义（PRAGMA table_info）
scripts/debug-cli.sh db query "select type,count(*) c from records group by type"
scripts/debug-cli.sh db query "select * from records order by timestamp desc" 20
```

口径：`query` 默认 50 行、上限 200；单字段超 500 字截断并加 `…`（`truncatedFields` 报截了几处）；BLOB 只报长度。安全（端上 fail-closed，被拒时 `ok=false reason=bad_args`）：只允许**单条** `select`/`with`，中间再有分号、带注释、出现 attach/pragma/insert/update/delete/drop/alter/create/replace/vacuum 词元一律拒；`schema` 表名只收字母数字下划线；查询走只读连接（`mode=readonly`；WAL 机型退回 App 连接时标 `mode=app-connection`）。

## 常用命令

```bash
adb shell dumpsys package com.bsfeng.monitor | head -30
adb shell dumpsys package com.bsfeng.monitor | grep -A 100 "runtime permissions"
```

## FAQ

- `adb: command not found`：用全路径，或 `source ~/.zshrc`；用 `echo $PATH | grep platform-tools` 确认。
- 充电但 `adb devices` 为空：USB 模式改 MTP 或换原装线。
- 装完报签名错误：残留了其它签名的同包名应用，卸载重装。
- HyperOS 不弹授权：解锁亮屏、下拉看「USB 调试」通知、关「断崖式权限监控」、撤销授权重试。
