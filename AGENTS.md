# android-monitor — Android 端行为监控 App

> ⚠️ **强制规则：先读文档，再动代码**
>
> 这是**硬性要求**，不是建议。本项目的所有代码变更（新增、修改、删除、重构）、问题排查、构建部署等操作，**必须先阅读对应的文档**再采取行动。跳过此步骤将导致文档与代码不一致、破坏已有规范、重复已知踩坑，最终合规检查失败。
>
> | 当你需要... | 必须先阅读 |
> |------------|-----------|
> | 修改/新增代码文件 | → **[代码规范](docs/开发指南/代码规范.md)** — 编码风格、命名约定、推荐语法特性 |
> | 创建/修改/删除文档 | → **[文档规范](docs/开发指南/文档规范.md)** — 文档结构、命名、渐进式加载等规则 |
> | 提交代码到 Git | → **[提交规范](docs/开发指南/提交规范.md)** — 提交信息格式、提交策略 |
> | 处理异常或定义新异常 | → **[异常规范](docs/开发指南/异常规范.md)** — 异常处理规则和最佳实践（如存在） |
> | 修改版本号或发布版本 | → **[版本号管理](docs/开发指南/版本号管理.md)** — 版本号变更规则和操作流程（如存在） |
> | 构建项目或运行测试 | → **[构建与测试](docs/开发指南/构建与测试.md)** — 构建命令、测试命令、提交策略、APK 打包 |
> | 编译通过后打包 APK 并安装到手机 | → **[构建与测试 → 每次改动必须打包 APK](docs/开发指南/构建与测试.md#每次改动必须打包-apk)** — `assembleDebug` + `adb install -r` |
> | 使用 ADB 调试、连接手机、安装 APK | → **[ADB 调试指南](docs/开发指南/ADB调试指南.md)** — macOS + 小米 14 HyperOS 调试完整流程 |
> | 了解项目整体架构 | → **[架构总览](docs/概览/架构总览.md)** — 系统分层、模块依赖 |
> | 修改编译配置或依赖 | → **[编译配置](docs/开发指南/编译配置.md)** — 构建变体、依赖配置、混淆规则 |
> | 打包签名或发布 | → **[发布指南](docs/开发指南/发布指南.md)** — 打包签名、渠道分发 |
> | 排查已知编译错误或运行时问题 | → **[踩坑记录](docs/踩坑/)** — 已知问题和解决方案 |
| 调研 Android / 澎湃 OS AI 能力 | → **[AI 能力调研](docs/开发指南/AI能力调研.md)** — Android 16 + 澎湃 OS 3 AI 相关 API |

## 项目简介
- **版本:** 1.0.0
- **语言:** Kotlin
- **类型:** Android 应用
- **JDK:** 21（`export JAVA_HOME=/opt/homebrew/opt/openjdk@21`）
- **技术栈:** Kotlin, Jetpack Compose, Navigation Compose, SQLiteOpenHelper, HttpURLConnection, org.json, Coroutines + Flow

> ⚠️ 本项目遵循 **零第三方业务依赖** 原则（Compose UI 框架除外）——数据库、网络、序列化均使用 Android 系统内置 API 实现。详见 [编译配置 → 核心依赖](docs/开发指南/编译配置.md#核心依赖)。

→ [项目简介](docs/概览/项目简介.md)
→ [架构总览](docs/概览/架构总览.md)
→ [快速上手](docs/概览/快速上手.md)

## 构建与安装速查

> 每次代码改动后，必须生成 APK 供用户侧载安装。
>
> ⚠️ **ADB 路径：** 当前环境 ADB 安装在 `~/Downloads/platform-tools/adb`，后续所有 adb 命令必须使用此全路径（详见 [ADB 调试指南](docs/开发指南/ADB调试指南.md)）。
>
> 🚨 **MIUI/HyperOS 警告：禁止使用 `adb shell am force-stop`！** 该操作会移除无障碍服务权限，需手动重新开启。详情见 [踩坑记录](docs/踩坑/MIUI-force-stop导致无障碍服务被自动移除.md)。

```bash
# 一行完成：测试 → 编译 Debug APK → 复制到 Downloads → 安装到已连接手机
./gradlew test && ./gradlew assembleDebug \
  && cp app/build/outputs/apk/debug/app-debug.apk ~/Downloads/ \
  && ~/Downloads/platform-tools/adb install -r ~/Downloads/app-debug.apk
```

| 命令 | 说明 | APK 输出路径 |
|------|------|-------------|
| `./gradlew assembleDebug` | 编译 Debug APK | `app/build/outputs/apk/debug/app-debug.apk` |
| `~/Downloads/platform-tools/adb install -r <apk>` | 覆盖安装到手机（使用全路径） | 保留原有数据 |
| `~/Downloads/platform-tools/adb devices` | 查看已连接设备（使用全路径） | — |
| 手机未连接时跳过安装 | → [ADB 调试指南 → 未连接手机处理流程](docs/开发指南/ADB调试指南.md#未连接手机处理流程) | — |

## 功能模块
| 模块 | 说明 | 源码路径 |
|------|------|---------|
| 事件采集 | 定义所有事件类型、数据模型、收集器和屏幕内容分类器 → [文档](docs/事件采集/概述.md) | `com.bsfeng.monitor.event` |
| 三层管道 | 采集层(RawEventCollector)→管道层(EventPipeline)→执行层(Handler) → [文档](docs/事件采集/概述.md) | `com.bsfeng.monitor.pipeline` |
| 网络通信 | HttpURLConnection + org.json 事件批量上报引擎、设备注册与心跳 → [文档](docs/网络通信/概述.md) | `com.bsfeng.monitor.network` |
| 数据持久化 | SQLiteOpenHelper 本地数据库，事件 CRUD 和离线缓存管理 → [文档](docs/数据持久化/概述.md) | `com.bsfeng.monitor.data` |
| BroadcastReceiver 监听器 | 系统广播监听：屏幕、充电、WiFi、蓝牙、耳机、应用安装等 → [文档](docs/BroadcastReceiver监听器/概述.md) | `com.bsfeng.monitor.receiver` |
| 后台服务 | 无障碍服务、广播注册器、服务健康检查与保活 → [文档](docs/后台服务/概述.md) | `com.bsfeng.monitor.service` |
| UI 界面 | Jetpack Compose 界面：实时事件流、统计面板、设置页面 → [文档](docs/UI界面/概述.md) | `com.bsfeng.monitor.ui` |
| 工具与配置 | 应用配置、设备 ID、Token 持久化等工具类 → [文档](docs/工具与配置/概述.md) | `com.bsfeng.monitor.util` |

## 代码快速定位

> 以下映射表帮助你快速定位目标文件。当你想修改某个功能时，直接打开对应的文件，无需 grep 搜索。

| 操作意图 | 对应文件 |
|---------|---------|
| 修改事件类型定义 | `app/src/main/java/com/bsfeng/monitor/event/EventType.kt` → [文档](docs/事件采集/概述.md) |
| 修改事件数据模型 | `app/src/main/java/com/bsfeng/monitor/event/EventModels.kt` → [文档](docs/事件采集/概述.md) |
| 修改事件收集(新) | `app/src/main/java/com/bsfeng/monitor/pipeline/RawEventCollector.kt` → [文档](docs/事件采集/概述.md) |
| 修改管道扇出逻辑 | `app/src/main/java/com/bsfeng/monitor/pipeline/EventPipelineImpl.kt` → [文档](docs/事件采集/概述.md) |
| 修改导航上下文追踪 | `app/src/main/java/com/bsfeng/monitor/pipeline/NavigationContextTracker.kt` → [文档](docs/事件采集/概述.md) |
| 修改事件源接口 | `app/src/main/java/com/bsfeng/monitor/pipeline/EventSource.kt` → [文档](docs/事件采集/概述.md) |
| 修改数据库存储 Handler | `app/src/main/java/com/bsfeng/monitor/pipeline/handler/DatabaseHandler.kt` → [文档](docs/事件采集/概述.md) |
| 修改上传 Handler | `app/src/main/java/com/bsfeng/monitor/pipeline/handler/UploadHandler.kt` → [文档](docs/事件采集/概述.md) |
| 修改日志 Handler | `app/src/main/java/com/bsfeng/monitor/pipeline/handler/LogHandler.kt` → [文档](docs/事件采集/概述.md) |
| 修改屏幕内容分类器 | `app/src/main/java/com/bsfeng/monitor/event/ScreenClassifier.kt` → [文档](docs/事件采集/概述.md) |
| 修改 API 接口定义 | `app/src/main/java/com/bsfeng/monitor/network/ApiService.kt` → [文档](docs/网络通信/概述.md) |
| 修改事件上报引擎 | `app/src/main/java/com/bsfeng/monitor/network/EventReporter.kt` → [文档](docs/网络通信/概述.md) |
| 修改数据库定义 | `app/src/main/java/com/bsfeng/monitor/data/DatabaseHelper.kt` → [文档](docs/数据持久化/概述.md) |
| 修改事件 DAO | `app/src/main/java/com/bsfeng/monitor/data/DatabaseHelper.kt` → [文档](docs/数据持久化/概述.md) |
| 修改事件实体 | `app/src/main/java/com/bsfeng/monitor/data/EventEntity.kt` → [文档](docs/数据持久化/概述.md) |
| 修改无障碍服务 | `app/src/main/java/com/bsfeng/monitor/service/MonitorAccessibilityService.kt` → [文档](docs/后台服务/概述.md) |
| 修改广播注册逻辑 | `app/src/main/java/com/bsfeng/monitor/service/ReceiverRegistrar.kt` → [文档](docs/后台服务/概述.md) |
| 修改健康检查 | `app/src/main/java/com/bsfeng/monitor/service/HealthChecker.kt` → [文档](docs/后台服务/概述.md) |
| 修改主界面 | `app/src/main/java/com/bsfeng/monitor/ui/MainScreen.kt` → [文档](docs/UI界面/概述.md) |
| 修改主页面逻辑 | `app/src/main/java/com/bsfeng/monitor/ui/MainViewModel.kt` → [文档](docs/UI界面/概述.md) |
| 修改设置页面 | `app/src/main/java/com/bsfeng/monitor/ui/SettingsScreen.kt` → [文档](docs/UI界面/概述.md) |
| 修改统计页面 | `app/src/main/java/com/bsfeng/monitor/ui/StatsScreen.kt` → [文档](docs/UI界面/概述.md) |
| 修改应用配置 | `app/src/main/java/com/bsfeng/monitor/AppConfig.kt` → [文档](docs/工具与配置/概述.md) |
| 修改工具类 | `app/src/main/java/com/bsfeng/monitor/util/AppUtils.kt` → [文档](docs/工具与配置/概述.md) |
| 修改 Application 入口 | `app/src/main/java/com/bsfeng/monitor/MonitorApp.kt` → [文档](docs/工具与配置/概述.md) |
| 修改 MainActivity | `app/src/main/java/com/bsfeng/monitor/MainActivity.kt` → [文档](docs/UI界面/概述.md) |

## 开发指南
→ [代码规范](docs/开发指南/代码规范.md)
→ [文档规范](docs/开发指南/文档规范.md)
→ [提交规范](docs/开发指南/提交规范.md)
→ [构建与测试](docs/开发指南/构建与测试.md)
→ [ADB 调试指南](docs/开发指南/ADB调试指南.md) — macOS + 小米 14 HyperOS USB 调试、APK 安装、故障排查
→ [编译配置](docs/开发指南/编译配置.md)
→ [发布指南](docs/开发指南/发布指南.md)
→ [Android 多版本适配](docs/开发指南/Android适配指南.md) — Android 10~16 行为变更清单与项目合规检查
→ [AI 能力调研](docs/开发指南/AI能力调研.md) — Android 16 + 澎湃 OS 3 AI 相关 API 与开放能力

## 踩坑记录
→ [踩坑记录](docs/踩坑/)
  - [MIUI force-stop 导致无障碍服务被自动移除](docs/踩坑/MIUI-force-stop导致无障碍服务被自动移除.md) — ⚠️ 禁止 force-stop
