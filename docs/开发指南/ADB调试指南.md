# ADB 调试指南 — macOS + 小米 14 (HyperOS)

## 环境

| 项 | 值 |
|---|---|
| ADB | `~/android-platform-tools/adb`（37.0.0，无 sudo；未装则下载 platform-tools-latest-darwin.zip 解压到 `~` 改名 `android-platform-tools`） |
| JDK | 25：`export JAVA_HOME=/opt/homebrew/opt/openjdk@25` |
| 手机 | 小米 14（23127PN0CC / houji），HyperOS，序列号见 `adb devices` |
| 脚本 | `scripts/debug-cli.sh`（仅 debug 包；release 包里没有这个 receiver） |

## 🚨 agent 操作手机界面只走无障碍通道

**红线：禁止用 `adb shell input` / `monkey` / `keyevent` 做界面操作。** 看屏、点击、滚动、返回一律走 `scripts/debug-cli.sh ui ...`，截图走 `scripts/debug-cli.sh shot`。

理由：输入注入是伪造事件，绕过 App 的真实交互链路，部分 HyperOS 版本直接拦；`ui` 通道在 App 进程内读无障碍节点树并 `performAction`，不注入事件、不需要 root，读到/点到的就是真实控件。

三个最常用（真机验收同款）：

```bash
scripts/debug-cli.sh ui dump                    # 看当前屏：控件树 → files/dsh-ui/ui.json（RESULT 行带 path/nodes/pkg）
scripts/debug-cli.sh ui click --es text 功能开关   # 按文案点（也可 --es desc / --es id / --es bounds x1,y1,x2,y2）
scripts/debug-cli.sh ui back                    # 返回上一屏（ui home / ui recents 同理）
```

| 命令 | 用途 |
|---|---|
| `ui dump [tag]` | 控件树 JSON（含 windows 列表、节点数、truncatedNodes），tag 即文件名 |
| `ui audit [tag]` | 无障碍审计：无可读名 / 触达 <48dp 的控件 → `files/dsh-ui/a11y-<tag>.json`；有缺口 `reason=a11y_gaps`、退出码 1（口径见[无障碍基线](../UI设计/无障碍基线.md)） |
| `ui click` / `ui longclick` + `--es text/desc/id/bounds` | 命中节点或其可点祖先 performAction，无可点祖先则手势点击 |
| `ui scroll --es dir up/down/left/right` | 前进（down、right）/后退（up、left）滚动：只滚方向对得上、贯穿屏幕中部的容器（页顶横向横条不吃竖向滚动），没有则手势滑动 |
| `ui swipe --ei x1 --ei y1 --ei x2 --ei y2 --ei ms` | 指定起止点的手势滑动 |
| `ui wait --ei ms` | 等界面稳定，上限 5000 |
| `shot [name]`（亦可 `ui shot [name]`） | `adb exec-out screencap -p` → `/tmp`（`SHOT_DIR` 可改） |

失败口径（全部 `ok=false`，绝不静默）：无障碍未开 `accessibility_off`、根窗口为空 `no_root`、选择器没命中 `not_found`、端上 11 秒超时 `timeout`、审计有缺口 `a11y_gaps`。dump / audit 的 JSON 只写 App 私有目录 `files/dsh-ui/`，不外置、不入库。

## 手机端设置与连接

1. 开发者选项：设置 → 我的设备 → 全部参数 → 连点「HyperOS 版本」7 次；开 USB 调试 + **USB 安装**（必需）+ **USB 调试（安全设置）**（推荐），必要时关「断崖式权限监控」。
2. 插线后下拉通知栏 → USB 通知 → 选「文件传输 (MTP)」，必须用数据线（纯充电线识别不到）。

连接自检：`~/android-platform-tools/adb start-server && ~/android-platform-tools/adb devices`，正常形如 `63caad7a  device usb:1-1 product:houji model:23127PN0CC device:houji transport_id:2`。

状态口径：`device` 可用 / `unauthorized` 拔插重连并在手机上勾「一律允许」/ `offline` 重启 adb server / 空列表按上两条查线-MTP-开关再重启 adb。

## 🚨 不用 force-stop

`adb shell am force-stop com.bsfeng.monitor` 会被 MIUI/HyperOS **自动移除无障碍权限**（见[踩坑](../踩坑/MIUI-force-stop导致无障碍服务被自动移除.md)），`ui` 通道随之失效且必须手动机上重开。装新包用 `adb install -r`（进程自动切换）；唤起作用只要拉起进程：`adb shell am start -n com.bsfeng.monitor/.MainActivity`。

每次改动后的全流程（连不上手机时跳过安装，但不能跳过编译）：

全流程：`./gradlew test && ./gradlew assembleDebug && ~/android-platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk`。

## 调试 CLI（含 db 取数）

```bash
export ANDROID_SERIAL=63caad7a
scripts/debug-cli.sh unlock          # 首次：解锁调试层（ui 命令同样受此闸门约束）
scripts/debug-cli.sh state           # 开关 / 定位权限 / 各类型当日计数
scripts/debug-cli.sh open settings   # 直达设置页（不用点 Tab）
scripts/debug-cli.sh seed day        # 造数：day | stay | move | gap
scripts/debug-cli.sh reset all       # 全表清空；单类型写 reset LOCATION
scripts/debug-cli.sh dump 20 LOCATION   # 倒最近 20 条
scripts/debug-cli.sh db tables       # 优先用它代替 adb pull 库文件（USB 断=半个文件，WAL 库拷主文件会坏页）
scripts/debug-cli.sh db schema records
scripts/debug-cli.sh db query "select type,count(*) c from records group by type"
```

结果在 logcat tag `DebugCmd`：`RESULT cmd=<n> ok=<bool> data=<json>`，脚本退出码 = `ok`；`Broadcast completed: result=0` 只代表投递成功。db 口径：`query` 默认 50 行、上限 200，单字段超 500 字截断（`truncatedFields` 报处数），BLOB 只报长度；端上 fail-closed，只放行单条 `select`/`with`，带分号/注释/写关键字一律 `reason=bad_args`。

## 日志与 FAQ

```bash
adb logcat -v time | grep -i "monitor\|bsfeng"   # 本应用；-d -s DebugCmd 看调试回执
adb logcat -v time -d > ~/Downloads/debug_log_$(date +%Y%m%d_%H%M%S).txt
```

FAQ：`adb: command not found` 用全路径或 `source ~/.zshrc`；充电但设备列表为空改 MTP 或换原装线；装包报签名错误先 `adb uninstall com.bsfeng.monitor`；HyperOS 不弹授权就解锁亮屏 + 撤销 USB 调试授权后重插。
