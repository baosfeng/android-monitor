# 贡献指南

感谢你愿意为 android-monitor 提交改进。请先阅读以下约定，能让协作更顺畅。

## 开发环境

- JDK 25（JDK 21 仍可回退）
- Android SDK Platform 37、Build-Tools
- 建议使用 Android Studio 最新稳定版

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@25
./gradlew test assembleDebug
```

## 开发流程

1. **文档先行**：新增或调整功能前，先更新 `docs/` 下对应模块文档；
2. **再写代码**：遵循 [代码规范](docs/开发指南/代码规范.md)；
3. **补测试**：新增类 / 方法必须附带单元测试，覆盖正常路径、边界与异常；
4. **跑验证**：提交前执行 `./gradlew test assembleDebug`，全部通过；
5. **提交**：按 [提交规范](docs/开发指南/提交规范.md)，格式为 `type: 中文描述`。

## 提交 PR

- 从 `main` 拉特性分支，一个 PR 只做一件事；
- 描述清楚「改了什么、为什么、如何验证」；
- 涉及 UI 的改动请附截图；
- 不要在 PR 中提交 `local.properties`、签名文件、真实设备信息或任何个人数据。

## 报告问题

- 功能缺陷请使用 [Bug 报告](.github/ISSUE_TEMPLATE/bug_report.yml) 模板；
- 安全或隐私问题请勿公开提交 Issue，按 [安全策略](SECURITY.md) 私下报告。
