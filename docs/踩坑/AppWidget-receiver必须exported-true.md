# AppWidget receiver 必须 exported="true"，否则桌面列表看不到组件

**结论**：AppWidget 的 `<receiver>` 一律 `android:exported="true"`，否则系统根本不注册 provider。

## 现象

- 6 个 Glance receiver 写成 `exported="false"`，`:app:assembleDebug` 全绿、APK 安装成功、`aapt2 dump` 里 receiver / intent-filter / meta-data 齐全；
- 但桌面长按 → 小组件里**看不到**这 6 个组件；
- `adb shell dumpsys appwidget | grep -i <包名>` **0 命中**（系统级 provider 列表里就没有）。

## 原因

桌面 launcher 是**另一个应用**：它需要通过 `APPWIDGET_UPDATE` 广播找到并把 RemoteViews 交给 provider。
`exported="false"` 让 receiver 对跨应用（含系统 launcher）不可见 → PackageManager 不把它当作 widget provider 注册，
后续 `AppWidgetManager.getAppWidgetIds` 也永远返回空。

这是「最小暴露面」直觉在 widget 上失效的典型：**只有 widget receiver 必须导出**，其余广播接收器照旧 `false`。

## 修复

```xml
<receiver
    android:name=".widget.FinanceWidgetReceiver"
    android:exported="true"
    android:label="@string/widget_finance_label">
    <intent-filter>
        <action android:name="android.appwidget.action.APPWIDGET_UPDATE" />
    </intent-filter>
    <meta-data android:name="android.appwidget.provider" android:resource="@xml/widget_finance_info" />
</receiver>
```

- intent-filter 只留 `APPWIDGET_UPDATE`，别加别的 action（多一个就多一条外部可伪造广播的入口）；
- 改完必须**重新安装**，再查 `dumpsys appwidget`：出现 6 条 provider 才算成功。

## 判定与取证

```bash
~/android-platform-tools/adb shell dumpsys appwidget | grep -i com.bsfeng.monitor
```

6 条 `ProviderId{... com.bsfeng.monitor/widget.XxxWidgetReceiver}` 即为注册成功；
组件加到桌面后再查同一条命令，`Instances` 段会多出对应实例。
