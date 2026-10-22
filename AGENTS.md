# android-monitor — Android 端行为监控 App

> ⚠️ **先读文档，再动代码**：改代码→[代码规范](docs/开发指南/代码规范.md)｜改文档→[文档规范](docs/开发指南/文档规范.md)｜提交→[提交规范](docs/开发指南/提交规范.md)｜构建测试→[构建与测试](docs/开发指南/构建与测试.md)｜改 UI→[设计规范](docs/UI设计/设计规范.md)+[审查清单](docs/UI设计/审查清单.md)+[动效与触觉基建](docs/UI设计/动效与触觉基建.md)｜改互传→[互传协议](docs/网络通信/局域网互传协议.md)｜改开屏跳过→[设计](docs/开屏跳过/设计.md)｜改自动记账→[自动记账](docs/记账/自动记账.md)｜改原型→[原型维护](docs/UI设计/原型维护.md)｜调试→[ADB 指南](docs/开发指南/ADB调试指南.md)｜找文件→[代码快速定位](docs/开发指南/代码快速定位.md)｜全量导航→[文档索引](docs/索引.md)

## 隐私与数据安全（红线）

> 🔒 真机截图、屏幕原文、真实账目与个人身份信息默认留在本地，不进入仓库（代码、文档、测试、示例数据、提交信息、Issue/PR 都算）。配图先遮挡金额/商户/账号/头像/聊天/通知正文并征得同意；dump 只留属性名不留文本值；真实值用占位符；取证产物放 `.open-design/impl-notes/`（已 gitignore）；提交前 `git status` + `git diff --cached` 自查。详见 [SECURITY.md](SECURITY.md)。

## 项目速览

- Kotlin + Jetpack Compose，6 个 Tab：概览 / 实时 / 广告 / 记账 / 传送 / 设置。模块见[代码快速定位](docs/开发指南/代码快速定位.md)，分层见[架构总览](docs/概览/架构总览.md)。
- 零第三方业务依赖（Compose UI 除外）：数据库 / 网络 / 序列化用系统 API；JDK 25：`export JAVA_HOME=/opt/homebrew/opt/openjdk@25`。

## 构建与安装（每次改动都要出 APK）

```bash
./gradlew test && ./gradlew assembleDebug \
  && ~/android-platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> adb 统一用全路径 `~/android-platform-tools/adb`；🚨 不用 `adb shell am force-stop`（会摘掉无障碍权限，见[踩坑](docs/踩坑/MIUI-force-stop导致无障碍服务被自动移除.md)）；手机未连接见 [ADB 指南](docs/开发指南/ADB调试指南.md)。

## 动手前必看的坑

> 改记账首页布局→[FAB 被底部导航栏遮挡](docs/踩坑/记账FAB被底部导航栏遮住导致无法新增交易.md)｜取证顺序「先截图、再 dump」→[uiautomator 踢掉无障碍服务](docs/踩坑/uiautomator踢掉无障碍服务.md)｜`rootInActiveWindow` 未必是目标窗口→[第三方悬浮窗](docs/踩坑/第三方悬浮窗顶掉无障碍根窗口.md)｜完整清单→[踩坑记录](docs/踩坑/README.md)

## 文档规则

- 单文档 ≤ 80 行（参考类 ≤ 120 行），超限先删内容、再拆分。
- 一屏读完，一句说清；每节第一句是结论。
- 只写读者读不到的判断：为什么这样、何时用、边界在哪。
- 同一信息只出现一次；背景、动机、演进不写。
- 保留阈值数字、命令、反例、失败后果；规则用「推荐 / 默认 / 优先」正向表述。

新增文档挂进 [docs/索引.md](docs/索引.md)，不留孤立文档；文档改动只碰 `.md`。
