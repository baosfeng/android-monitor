# 踩坑：自绘 BottomSheet 传 align 定位无效，抽屉贴在屏幕顶部

> ⚠️ **何时阅读：** 修改 `ui/components/Surfaces.kt` 里的 `BottomSheet`，或在页面里想用
> `Modifier.align(Alignment.BottomCenter)` 把抽屉贴到底部时。

## 现象

页面按"看起来最直白"的写法调用抽屉：

```kotlin
Box(Modifier.fillMaxSize()) {
    OverlayScrim(onDismiss = { selected = null })
    BottomSheet(
        visible = true,
        onDismiss = { selected = null },
        modifier = Modifier.align(Alignment.BottomCenter),   // ← 意图是"贴底"
    ) { /* 内容 */ }
}
```

抽屉却出现在**屏幕顶部**（紧贴状态栏），而不是底部。没有报错、没有崩溃、编译与单测全绿 —— 只是位置不对。

## 根因

`Modifier.align` 是 **Box 的 parentData**，只有落在 Box 的**直接子节点**上才会被 Box 读取。

`BottomSheet` 原实现是：

```kotlin
AnimatedVisibility(visible = visible, enter = ..., exit = ...) {
    Column(modifier = modifier.fillMaxWidth() /* ... */) { /* 内容 */ }   // ← modifier 落在了这里
}
```

传入的 modifier 落在 `AnimatedVisibility` **内部**的 `Column` 上，而它不是 Box 的直接子节点，
Box 读不到这份 parentData，于是按默认的 `TopStart` 摆放。

## 修法

把 modifier 作用到组件**根节点**（`AnimatedVisibility`）上：

```kotlin
AnimatedVisibility(
    visible = visible,
    modifier = modifier,        // ← 关键：根节点
    enter = ..., exit = ...,
) {
    Column(Modifier.fillMaxWidth() /* ... */) { /* 内容 */ }
}
```

调用方一行都不用改，原来写的 `Modifier.align(Alignment.BottomCenter)` 立刻生效。

> 若公共组件一时不能改，调用方可以自己兜住：`Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter)`
> 把抽屉包一层（`contentAlignment` 对所有子节点生效，不依赖 parentData）。

## 影响范围与验证

修复前的 `Modifier.align(Alignment.BottomCenter)` 对**所有**调用方都是失效的（三处都是这个写法）：

| 调用方 | 修复前 | 修复后 |
|-------|--------|--------|
| 记账「记一笔」的四个抽屉（`finance/ui/RecordSheets.kt`） | ❌ 贴顶 | ✅ 贴底 |
| 广告跳过的记录详情抽屉（`ui/AdScreen.kt`） | ❌ 贴顶 | ✅ 意图生效 |
| 实时事件流的记录详情抽屉（`ui/MainScreen.kt`） | ❌ 贴顶 | ✅ 意图生效 |

实测证据（Android 模拟器 1080×2400，Robolectric 测不了布局，必须真机/模拟器看）：

```
修复前  uiautomator: 「选择分类」标题 bounds y = 221       ← 贴在状态栏下面
修复后  uiautomator: 「选择分类」标题 bounds y = 1265      ← 贴底（屏幕高 2400）
```

## 教训

1. **自研容器组件，`modifier` 必须作用在组件根节点上** —— 尤其是 `align` / `weight` 这类
   parentData 修饰符，一旦被塞进内部子节点就彻底失效，而**不会有任何报错**；
2. 这类坑**编译、单测、Lint 全都抓不到**，只体现在位置与尺寸上 —— UI 组件改完必须截图看一眼
   （本次就是靠"原型 vs 实现"逐屏对照才暴露出来的）。
