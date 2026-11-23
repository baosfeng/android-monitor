# mock location 无法让定位门禁升级

> 何时读：想在真机上验证「走动 → 门禁 PROBING/MOVING → 探测阶梯」却发现门禁纹丝不动时。结论：**adb 不需要 root 造不出走动**，这次真机验收的走动段因此标注「未覆盖」。

## 现象

用 `cmd location providers add-test-provider` + `set-test-provider-location` 每 20 秒向东挪 33m、连续 5 分钟（累计 ~500m），系统侧确实换了坐标：

```
last location=Location[network 31.222000,121.258000 hAcc=15.0 et=+16d11h29m59s796ms mock]
```

但 App 侧完全没反应：`gate` 恒为 SLEEPING、`mode` 恒为 STILL、`subscription=false`，服务每 5 分钟的日志一直是「门禁证据：步数=0 距离=0m」。

## 为什么

门禁的位移证据只有**一个**入口：`LocationGateState.offer(sample)`。而 offer 的调用点全在**我们自己去采点**的路径上（探测 / 订阅 / 心跳 / 断档自愈）。SLEEPING 按设计**不发起任何定位**，于是没有任何代码去读那份 mock 坐标 —— 它只是躺在系统缓存里。

补两条同类事实：

- `debug-cli.sh locate` 强采一次能进门禁，但它取到的是 **passive** 点，而 `MotionGate.onProbeSample` 对 `fromProbe=false` 的被动点**不计数、也不产生位移证据**（被动点频率由别的 App 决定，不能用来驱动退避阶梯）；
- 门禁升级还要求「位移超过两端精度之和（噪声地板 ≥30m）」并且锚点非空；锚点只在第一次真实采点后才建立。

## 替代路径（按可用性排序）

1. **真走几十步**（唯一能验硬件路径）：`ACTIVITY_RECOGNITION` 授予后步数计常驻，≥40m 才进 PROBING，这是设计口径；
2. **模拟器退化路径**：`emulator -avd <名字>` + `adb emu sensor set acceleration` 注入步态，验「软件步数 + 加速度判据」；模拟器没有步数计 / 显著运动传感器，验不了硬件路径；
3. **单测**：探测阶梯与升级判据在 `MotionGateTest` / `MotionGateProbeBackoffTest` / `MotionGateMovingKeepTest` 里已钉死，改动这些判据时必须先让它们绿。

## 更危险的一面：mock provider 会顶替真实 provider

`add-test-provider network` / `gps` 不是"额外加一个数据源"，而是**接管同名 provider**：`dumpsys location` 里会显示 `network provider [mock]`。此时凡是按名字取 provider 的代码都会拿到 mock 那个，而这个 provider 没有真实投点，于是：

```
23:34:46 W/LocationCollector: 主动定位跳过：没有可用的 provider（network, gps）
23:34:46 I/LocationForegroundService: 兜底没拿到点：窗口收紧到 5 分钟重试
```

**验完必须对称清理**，否则后续所有定位请求都会失败，而且现象像产品 bug（真机踩过：兜底连续失败，被误认为采点链路坏了）：

```bash
adb shell cmd location providers remove-test-provider network
adb shell cmd location providers remove-test-provider gps
adb shell dumpsys location | grep '\[mock\]'      # 必须为空
```

清理后同一条链路立刻恢复（实测下一拍 2.697s 出点）。

## 反过来的一次教训

判断「门禁有没有升级」不要只看系统坐标变没变：**看上一步写进库的点**（`records` 里 type=LOCATION 的时间戳与 detail.provider）才是事实源。mock 坐标能让 `dumpsys location` 好看起来，却骗不了 `offer` 这条链路。
