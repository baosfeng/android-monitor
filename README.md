# android-monitor

[![Android CI](https://github.com/baosfeng/android-monitor/actions/workflows/android-ci.yml/badge.svg)](https://github.com/baosfeng/android-monitor/actions/workflows/android-ci.yml)
![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)
![Android](https://img.shields.io/badge/Android-10%2B-3DDC84?logo=android&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-2.4-7F52FF?logo=kotlin&logoColor=white)
![Compose](https://img.shields.io/badge/Jetpack%20Compose-2.4-4285F4?logo=jetpackcompose&logoColor=white)
![PRs Welcome](https://img.shields.io/badge/PRs-welcome-brightgreen.svg)

> 一款运行在 Android 上的个人效率与行为记录应用：记录应用使用、跳过开屏广告、局域网互传文件、记账与自动记账。

项目以 **Kotlin + Jetpack Compose** 编写，遵循「零第三方业务依赖」原则——数据库、网络、序列化均使用 Android 系统内置 API 实现。

## 功能特性

| 模块 | 能力 |
|------|------|
| 记录系统 | 应用切换、使用时长、通知、来电等事件采集；本地合并与去重 |
| 开屏跳过 | 基于无障碍服务的 CSS 风格选择器规则引擎，内置规则库 + 订阅更新 + 坐标点击降级 |
| 局域网互传 | UDP 组播发现、HTTP 流式传输、sha256 校验、PIN 配对、断点续传 |
| 记账 | 分类 / 账户 / 交易 / 预算、统计图表、熊猫记账导入 |
| 自动记账 | 无障碍识别微信 / 支付宝账单页，弹确认浮窗，确认后幂等落库 |
| 界面 | Compose 四标签：实时事件流、广告跳过、记账、设置；支持 8 套主题 |

## 技术栈

- Kotlin 2.4、Jetpack Compose、Navigation Compose
- SQLiteOpenHelper、HttpURLConnection、org.json
- Coroutines + Flow
- 最低 Android 10（API 29），目标 API 34

## 构建

环境要求：

- JDK 21
- Android SDK Platform 36、Build-Tools

```bash
# 1. 配置 JDK（macOS）
export JAVA_HOME=/opt/homebrew/opt/openjdk@21

# 2. 可选：在 local.properties 指定后端地址（该文件不入库）
#    serverBaseUrl=http://<your-server>:<port>

# 3. 运行单元测试并打包 Debug APK
./gradlew test assembleDebug

# APK 输出
# app/build/outputs/apk/debug/app-debug.apk
```

> 仓库不包含签名文件。本地存在 `app/debug.keystore` 时沿用自定义签名，否则回退到 AGP 默认 debug keystore。

Release 构建需要签名配置（`local.properties` 或环境变量注入，详见 [发布指南](docs/开发指南/发布指南.md)）：

```bash
./gradlew assembleRelease
# app/build/outputs/apk/release/app-release.apk
```

## 更新与发版

- 应用内支持「检查更新 → 下载 → 安装」，版本清单为 `updates/version.json`；
- 更新源按顺序回退：私有构建的自建源 → jsDelivr → GitHub 镜像（ghfast.top / gh-proxy.com / ghproxy.net）→ GitHub 直连，适配国内网络；
- 发版：`scripts/release.sh <x.y.z>` 递增版本号并打 tag，GitHub Actions 自动构建签名 APK、创建 Release 并回写版本清单；
- 公开 Release 的 APK **不包含任何自建服务器地址**，避免泄露源站 IP；需要服务器加速时只在自己本地构建私有版本并注入地址。

细节见 [更新检测](docs/更新检测/概述.md) 与 [发布指南](docs/开发指南/发布指南.md)。

## 项目结构

```
app/      Android 应用：UI、无障碍服务、事件采集、网络、互传、记账、自动记账
domain/   与平台无关的领域模型与规则 DSL
docs/     中文文档：架构、协议、设计规范、踩坑记录
```

更多细节见 [文档索引](docs/索引.md)。

## 隐私与权限

本应用只在本机处理你的数据，除非你主动配置了后端地址。

| 权限 | 用途 |
|------|------|
| 无障碍服务 | 识别并点击开屏广告的「跳过」；识别微信 / 支付宝账单页用于自动记账 |
| 使用情况访问 | 统计应用使用与切换事件 |
| 通知访问 | 采集通知元信息；通知正文可在设置中关闭 |
| 来电状态（`READ_PHONE_STATE`） | 记录来电事件 |
| 前台服务 | 保持采集与传输在后台运行 |
| 网络 | 局域网文件互传；可选的事件上报与心跳 |

- 数据默认存储在本地 SQLite，卸载即删除；
- 事件上报需要自行在 `local.properties` 配置 `serverBaseUrl`，仓库默认占位地址不会向任何服务器发送数据；
- 检查更新只读取公开的版本清单并下载 APK，不采集任何设备信息；
- 不采集通讯录、短信正文、相册、位置与剪贴板内容。

## 贡献

欢迎 Issue 与 PR，流程见 [贡献指南](CONTRIBUTING.md) 与 [提交规范](docs/开发指南/提交规范.md)。

## 许可证

[MIT](LICENSE)
