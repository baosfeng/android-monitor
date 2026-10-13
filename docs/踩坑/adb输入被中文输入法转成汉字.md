# 踩坑：`adb shell input text` 输入网址被中文输入法转成汉字

## 问题描述

用 `adb shell input text 'https://registry.npmmirror.com/...'` 往输入框里填订阅地址，
输入框里出现的是 **中文**：

```
https：、、is突然意难平秘密让人哦日吃哦没、＠欧了人、刚看到—苏办事处日抛提哦弄、latest、files、第三天、
```

即 `input text` 的 ASCII 字符被输入法当成**拼音**转换成了汉字。

## 影响范围

- **设备：** 只有中文输入法（如搜狗输入法）的机器（小米 14 实测：`ime list -s` 只有 `com.sohu.inputmethod.sogou*`）
- **场景：** 用 ADB 自动化填写 URL / 英文文本（调试订阅地址、配置项）

## 根本原因

`input text` 注入的按键经过输入法处理；搜狗拼音模式会把连续字母当作拼音候选并上屏汉字。
输入框本身用默认文本键盘（`KeyboardType.Text`）时更容易被接管。

## 解决方案

### 方案一：切英文模式（推荐，无需改系统设置）

```bash
A=~/android-platform-tools/adb
$A shell input keycombination KEYCODE_SHIFT_LEFT KEYCODE_SPACE   # 搜狗中英切换
$A shell input text 'https://example.com/gkd.json5'
$A shell input keycombination KEYCODE_SHIFT_LEFT KEYCODE_SPACE   # 切回中文
```

⚠️ 该切换是**有状态**的：连按两次会切回来。自动化时先输入一段 `abc` 探针，dump 确认落到输入框
的是 ASCII 再输入正文：

```bash
$A shell uiautomator dump /sdcard/probe.xml
$A shell cat /sdcard/probe.xml | tr '<' '\n<' | grep -E 'EditText' | grep -oE 'text="[^"]*"'
```

### 方案二：产品侧修好输入框键盘类型（已修复）

订阅地址输入框改用 URI 键盘，减少被输入法接管的概率：

```kotlin
OutlinedTextField(
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
    ...
)
```

（`app/src/main/java/com/bsfeng/monitor/ui/SkipRuleScreen.kt`。注意：只靠键盘类型**不能**完全避免
输入法接管，实测搜狗拼音仍会把字母转汉字，所以自动化仍需方案一。）

## 其它注意

- 输入框聚焦后再输入：先 `input tap <输入框中心坐标>`，否则文本会落到别处；
- 长按/滑动等手势会误触返回手势区（底部边缘）导致页面被关闭，取值前先 dump 确认坐标；
- 键盘弹起后对话框会上移，**按钮坐标会变**，点击前重新 dump 取 bounds。

## 相关文件

- 订阅地址输入框：`app/src/main/java/com/bsfeng/monitor/ui/SkipRuleScreen.kt`
- ADB 调试流程：`docs/开发指南/ADB调试指南.md`
