# FlymeFreeform 实现说明

版本 1.3.6 / versionCode 2026110717　工程根 `srcOld`　产物 `out/FlymeFreeform-v1.3.6.apk`
源码 `Hoerire/FlymeFreeform`　LSPosed 侧 `Xposed-Modules-Repo/io.github.hoerire.flymefreeform`

本文只讲「代码怎么实现的」与「改动时不能碰的边界」。功能说明见 README。

---

## 〇、开工闸门（新会话第一条，先做再说话）

交接文档教的是「照做，不要跳」，本条优先于它。**没走完闸门之前不动设备、不重装、不重启。**

**铁律 0（最高优先级，用户 2026-11-08 两次重申）：动手之前先回答。**
用户提问时先给结论；没有用户明确说「做 / 继续 / 装 / 发 / 构建」，就不构建、不装包、不重启、不推送。
“顺手先做”不算授权。一次只验一个变量，一个改动一个包。

1. 读本文件第七节（日志）、第九节（改动守则）、第十节（当前状态），一句话复述给用户
2. 核设备当前包：`dumpsys package io.github.hoerire.flymefreeform | grep codePath` → `sha256sum <codePath>/base.apk`，与第十节对照，不认版本号
3. 取日志现状：`grep -aE 'OVERFLOW_|MODULE_CONFIG_READY|MODULE_STATE' 最新 modules log`，先看现在到底是哪版在跑
4. 要重启 → 一律走 `sh restart-gate.sh --changed … --pkg … --verify … --logs … -- "<命令>"`，
   四段缺一项脚本就拒绝执行；声明落 `tmp/restart-declarations.log`
5. 收工：回写第十节

> 为什么写死成脚本：上下文压缩会丢掉「承诺过不做的事」，只留「现在做到哪了」。
> 纪律必须能从磁盘读出来并拦得住动作，不能靠新会话的自觉。

## 一、进程分工

模块按进程名分派到不同安装器，改代码前先确认目标代码跑在哪个进程。

| 进程 | 安装器 | 负责 |
|---|---|---|
| system_server | `SystemServerHookInstaller` | 启动判定与参数注入、窗口数量上限与应用准入、窗外关闭、快速上滑、扇形协调器 |
| com.android.systemui | `SystemUiHookInstaller` | 底角触摸监听、通知点击转场 |
| com.android.launcher | `LauncherHookInstaller` | 桌面侧底角触摸监听 |
| com.coloros.smartsidebar:ui | `SidebarHookInstaller` | 「更多」面板接管、面板工具小窗注入 |

跨进程通信只有两条路：侧边栏走 Binder（`SidebarProtocol`），模块应用与框架走 SharedPreferences 与广播。**没有静态字段共享**。

## 二、目录

```
hook/                       各进程安装器与运行环境
  launch/ActivityStarterZoomInjector.kt   启动判定与参数注入（核心）
  launch/ZoomLaunchSpec.kt                小窗参数契约
  launch/InterAppLaunchTracker.kt         跳转关系记录，识别返回
  launch/FlexibleWindowLimitHook.kt       数量上限与应用准入
  launch/NotificationZoomSourceHook.kt    通知来源标记
platform/coloros/           ColorOS 私有接口封装，全部反射
  ColorOsFreeformCoordinator.kt           system_server 内扇形总协调
  ColorOsAllApps*.kt                      「更多」面板窗口与内容（侧边栏进程）
  PanelToolLaunchBridge.kt                面板工具小窗参数注入（侧边栏进程）
  FavoriteGroupInjector.kt                面板数据原地改写
gesture/                    纯逻辑：手势状态机、命中计算
window/                     浮层自绘与动画
ui/                         Compose + Miuix 设置界面
config/ apps/ framework/ log/
```

按功能定位：

| 要改什么 | 去哪里 |
|---|---|
| 扇形呼出、命中半径 | `gesture/CornerGestureEngine.kt`、`RadialGeometry.kt` |
| 扇形浮层 | `window/CornerRadialOverlayView.kt` |
| 角区触摸窗口 | `hook/SystemUiCornerInputMonitor.kt` |
| 「更多」面板 | `platform/coloros/ColorOsAllApps*.kt` |
| 面板工具小窗 | `PanelToolLaunchBridge.kt` + `SidebarHookInstaller` |
| 通知点击 | `hook/NotifBundleHook.kt` |
| 各类启动判定 | `hook/launch/ActivityStarterZoomInjector.kt` |
| 数量上限与准入 | `hook/launch/FlexibleWindowLimitHook.kt` |
| 窗外关闭 | `hook/OutsideTapCloseHookInstaller.kt` |
| 快速上滑 | `hook/HandleSwipeUpHookInstaller.kt` |
| 暂停条件 | `hook/ModuleEnvironmentState.kt` |
| 设置界面 | `ui/` 下对应 Screen |

## 三、各功能怎么实现的

**扇形菜单**　system_server 内的协调器监听底角指针，手势逻辑在 `CornerGestureEngine`（纯状态机，可单测），
命中计算在 `RadialGeometry`；浮层由 `CornerRadialOverlayView` 自绘，窗口类型 `TYPE_INPUT_METHOD_DIALOG`(141000)，
压在输入法与键盘之上，回落 `TYPE_APPLICATION_OVERLAY`(111000)。松手当帧提交启动、复制原生 zoom 参数、
侧边栏连接常驻——三处缺一都会明显变慢。

**「更多」面板**　不替换原厂面板，而是接管它的内容视图：`ColorOsAllAppsContent` 构造侧边栏自己的面板类，
`FavoriteGroupInjector` 在 `bindAdapter` 时**原地改写同一个 ArrayList**（不换对象、不 notify）；
点击回调按 `alias` 回查而不是按索引分发，避免搜索后索引错位打开错误应用。

**面板工具小窗**　付款码、扫一扫这类工具由侧边栏自建 Intent，经 `AppPlatformUtils.n(Intent, Bundle, UserHandle)`
交给系统 uid 代发，界面进程无法重发（目标是第三方未导出 Activity）。做法是点击前登记目标包，
在代发瞬间把 zoom 参数**就地并入系统已建好的 Bundle**，启动链路不变。

**通知点击**　钩 SystemUI 的 `OplusStatusBarNotificationActivityStarterExImpl.setNotificationTypeStarterLog`
（类名带 `Impl`）：依次移除横幅 → 剥离 `android:activity.remoteTransition` → 写窗口参数 →
非横幅时调 `collapseShadeForActivityStart()`。横幅实例早于钩子创建，需同时钩 `HeadsUpManagerImpl.showNotification` 取 thisObject。

**窗外关闭 / 快速上滑 / 数量上限**　分别钩 `FlexibleTaskController.exitFlexibleTask`、
普通小窗底部横条上滑入口、`FlexibleWindowManagerService.getMaxWinNum`；应用准入钩三类共 9 个
`isSupportFlexibleWindow`（FlexibleWindowManagerService 2、FlexibleTaskController 3、FlexibleWindowUtils 3）。

**超限关窗（不贴边）—— 已移除，不要再加回来**

2026-11-08 决定删除 `OverflowCloseHook`，`SystemServerHookInstaller` 里的安装调用一并去掉。
现在超限完全交给系统：窗口缩成边缘浮标，就是原厂行为。

移除原因（三轮实测都失败，代价大于收益）：

- 系统的溢出路径不止一个入口。`updateTaskMaxNumWhenFocusChanged` → `determineFlexibleTaskToExit`
  → `setLastFlexibleTaskToExitLocked` 里既有 `FloatHandleController.isInFloatingList` 这类早退，
  也有直接建浮标的路径，钩子经常根本不触发；
- 钩住唯一必经点后，能拿到的只有 `prevTask`。从桌面新开窗口时它不在小窗列表里，
  代码会退回「按列表取第一个」，而第一个往往正是刚打开的窗口，于是形成
  **关掉 → 系统重启该应用 → 再关** 的循环，表现为整机卡死（实测 00:47:03/07/11 连续三轮）；
- 按 `taskId` 判「最早」也不成立：任务被复用后 id 保持旧值，重开 QQ 时它比新任务还小，
  刚打开的会被误关（表现为点开就消失、切不回去）；
- 关窗后应用立即变 cached，与系统 freezer 的节奏打架，反复冻结/解冻放大卡顿。

要重新做，先满足三条前提：拿到当前焦点任务（钩 `updateTaskMaxNumWhenFocusChanged` 的第二个参数）、
一次只关一个并带冷却、以及一套能稳定复现的验收脚本；否则不要动系统默认的浮标行为。

**暂停条件**　`ModuleEnvironmentState` 维护锁屏、横竖屏、游戏模式、前台包；前台包来自
`ActivityTaskManagerService.setLastResumedActivityUncheckLocked`。横屏与游戏模式只暂停扇形呼出，
小窗能力用不含暂停的 `isModuleActive()` 判定。

## 四、启动判定（`reasonFor`）

全部启动改写受单一开关 `zoomLaunch` 控制：`reasonFor` 首行读它，关闭即返回 null，任何入口都不改写。
八段判定，先命中先返回：

1. 来源标记（SystemUI 写入的通知标记、控制中心磁贴）
2. 排除列表与「返回发起方」识别 —— 放行
3. 通知点击（发起方 SystemUI + PendingIntent）
4. 文件选择 —— 始终全屏
5. 应用间跳转（带 resultTo 的跨包、双方均为第三方的跨包）
6. 桌面发起（快捷项 / 快捷菜单 / 卡片），实现见 `launcherOriginReason`
7. system_server 以 MAIN 代发
8. SystemUI 自身发起的其余启动，按磁贴 / 通知栏区分

顺序不能调：通知点击与文件选择都带 resultTo，放到第 5 段之后会被抢先命中；控制中心判定依赖发起方信息，必须更晚。

桌面四类入口只靠发起方字段区分，不看 Intent 内容：

| 入口 | callingPackage | realCallingUid | resultTo | action |
|---|---|---|---|---|
| 图标点击（保持全屏） | 桌面 | 桌面 | 有 | MAIN |
| 长按快捷项 | 目标应用 | 桌面 | 无 | 任意 |
| 快捷菜单（应用详情） | 桌面 | 桌面 | 无 | 非 MAIN |
| 卡片 / 负一屏 | 桌面 | 桌面 | 有 | 非 MAIN |

- 只判 `callingPackage == 目标包` 会把应用内跳转一起小窗化，必须同时比对桌面 uid
- 不能用 action 区分图标与快捷项：QQ 的快捷项就是 `ACTION_MAIN` 加参数（flags `0x14000000`）
- 应用内页内跳转同样携带 resultTo，所以第 5 段要求「跨包」
- 返回发起方的启动与主动跳转特征相同，靠 `InterAppLaunchTracker`（2 秒窗口）识别

`LAUNCHER_CARD_SKIP` = SKIP_TARGETS + 安装器 + 分享面板 + 桌面自身 + 安全中心：桌面长按菜单的
「编辑」「上锁」因此不会被开成小窗。

## 五、关键参数

- 扇形命中半径 40–140 dp，默认 100 dp；选中放大 1.2×
- 触发范围 24–160 dp，默认 84 dp
- 扇形条目数 1–6，小窗数量 1–5（两个上限不同，不可混用）
- 双击关窗阈值 `ViewConfiguration.getDoubleTapTimeout()` = 300 ms
- 面板数据在 `bindAdapter` 时原地改写，不 notify、不换对象

## 六、构建、签名与发布

- 只能走 GitHub Actions（`workflow_dispatch`，ref = main），release 已开 R8
- CI 产物未签名，本地两步：`zipalign -P 16 -f 4` → `apksigner sign`
  - 密钥 `release.keystore`，口令 `android`，别名 `flymefreeform`，证书 `CN=Hoerire, O=FlymeFreeform, C=CN`
  - 签名方案要 v1+v2+v3 全开：apksigner 按 APK 的 minSdk(35) 默认只写 v3，必须加 `--min-sdk-version 23`
  - 校验：`apksigner verify -v --min-sdk-version 23` 三项全 true；`zipalign -c -P 16 4` 通过
- `/storage` 是 noexec：装机先 `cp` 到 `/data/local/tmp` 再 `pm install -r -d`
- 上传 Release 资产用 node fetch + Buffer（本机 curl 是 node wrapper，`--data-binary` 会传成 25 字节）
- 一次发布同步三处：① 源码仓库 main　② 源码仓库 Release（tag `v<版本名>`）　③ LSPosed 一侧
  （模块仓库 Release，tag `<versionCode>-<版本名>`，与介绍页 README）。后两处资产与源码仓库那份必须同一摘要
- 介绍页 README 由主仓库 README 转换：去 shields 徽章块、`<h1 align="center">` 改 `# `、相对图片路径改 raw 绝对地址
- 提交历史保持单条：`commit --amend` 后 `push -f`；纯内部修复不新建 tag，只换同名资产
- 换签名会导致覆盖安装失败（1.3.6 起改用个人证书，旧版升级需先卸载一次）

## 七、日志与排障

日志只在 `/data/adb/lspd/log/modules_*.log`。诊断消息一律 lambda 传入 `ModuleLog.debug(tag) { }`，
详细日志关闭时不构造字符串、不读状态；常开 INFO 只留给 `*_INSTALLED` / `*_READY`。

```
L=$(ls -t /data/adb/lspd/log/modules_*.log | head -1)
grep -aE 'PATTERN' "$L" | sed 's/.*FlymeFreeform //' | tail -30
```

本机 grep 是 BRE，`\|` 会静默失效，必须用 `-E`。

| 现象 | 看什么 |
|---|---|
| 启动未接管 | `ZOOM_DECISION_*_okfalse` 的 `cp` / `rr` / `rt` / `tg` |
| 注入成功 | `ZOOM_INJECTED_<launcher_card \| launcher_shortcut \| launcher_settings \| inter_app \| system_launch \| notification \| control_center>` |
| 磁贴长按 | `ZOOM_PREEMPTED_<包名>` |
| 面板工具 | `PANEL_TOOL_HANDLED_<alias>` 后紧跟 `PANEL_TOOL_ZOOM_<包名>` |
| 面板点击 | `PANEL_CLICK` 的 index / alias / aligned / size / query |
| 通知 | `NOTIF_BUNDLE_ZOOM` 的 dropped；钩子缺失看 `NOTIF_BUNDLE_HOOKED_*` |
| 横幅未移除 | `NOTIF_HEADS_UP_MANAGER_NULL`（未取到实例）/ `NOT_HEADS_UP`（时机过晚） |
| 扇形被拦 | `SYSTEM_GESTURE_REJECTED_<原因>` |
| 作用域外的子进程 | `MODULE_SKIPPED_UNEXPECTED_PROCESS_<进程名>`，DEBUG 级属正常 |

面板工具注入后 system_server 侧对应那条会显示 `okfalse`——参数已在侧边栏写好，注入器读到
`alreadyZoom` 不再二次改写，属正常，不是漏判。

生效范围：system_server 改 `killall system_server`（约 30–40 秒恢复）；SystemUI 改 `kill com.android.systemui`；
面板改 `kill com.coloros.smartsidebar`；桌面改 `killall com.android.launcher`；纯 UI 改重开模块应用。

## 八、不做的事

以下方向投入过排查，结论是不做，重新评估前先看这里：

| 方向 | 结论 |
|---|---|
| 游戏内悬浮窗的窗外关闭 | 游戏助手拉起的窗口不归 FlexibleTaskController，柔性状态读 0，首次点击必然落到系统 |
| 小窗居中 | 位置由 `OplusFlexibleTaskLayoutPolicy.calcLaunchBounds` 决定，注入参数里没有位置键 |
| 翻译 / 识屏工具 | 跨进程无法可靠触发，已移除 |
| 系统分发服务代发的 PendingIntent 启动（无法注入，只能抢占） | 应用（如酷安）把链接 Intent 交给 `android.ext.services` 代发时，
  调用方是系统 uid、真发起者是原应用。这类启动由后台发起，往 options 注入小窗参数会被
  `FlexibleTaskController.adjustOptionsForFlexibleTask` 以 `reason:9` 拒绝，窗口创建不出来（表现为点击无反应）。
  因此不走 inject，改在 `preemptWithZoomWindow` 里用系统 zoom 入口重发；重发失败时原启动照常进行，退化为全屏 |

## 九、改动守则

1. 结论要能指到一行日志或一段字节码，反编译优先于猜测（`dexsql` 查 services.jar / oplus-services.jar / SystemUI.apk）
2. 重启前先把话说完：改了什么、装的哪个包、sha256、验证什么、看哪几条日志；命令用 `setsid` 脱离进程组
3. 能只重启一个进程就不重启设备
4. 注释与提交信息第三人称陈述，只写做什么、为什么、约束是什么
5. 不在手机上跑 Gradle；不跨进程用静态字段通信；不按固定次数 `postDelayed` 撞系统数据
6. 面板数据不能按文本改标题（两个标题都叫「工具」，须按视图树顺序取），不能读 `UserPanelView.getRecentData()`（占位内容）
7. 不在 SystemUI 侧改写通知点击入口（回调不稳定，命中失败会丢弃原始事件）
8. 工程纪律：构建输出落 `out/`、临时文件落 `tmp/`、`release.keystore` 必须保留、交付前 `tar -czf` 打包放存储根目录

## 十、当前状态

更新时间：2026-11-08 00:56（Asia/Shanghai）

| 项 | 值 |
|---|---|
| 版本 | 1.3.6 / 2026110717（资产同名替换，以 sha256 为准，不以版本号为准） |
| 功能状态 | **超限关窗已移除**（`OverflowCloseHook.kt` 删除，安装调用移除）；超限回到系统默认的边缘浮标 |
| 远端 main | `0816306`（单条提交 force push；提交号每轮重建，以 `git rev-parse HEAD` 为准） |
| 设备已装 | `869ebd78…c981`（00:54:40 装，2056495 B）——**这是当前唯一有效版本** |
| 上一版（已作废） | `10115b43…`（带冷却的关窗版）、`1f89fc39…`（带冻结版）、`56137ecf…`（prevTask 版）、`76409b26…`（taskId 版）——全部不要再装 |
| 源码 Release / 模块仓库 Release | 仍是 `56137ecf…77d7`，**尚未更新**，与设备不一致 |
| 归档 | `/storage/emulated/0/FlymeFreeform-src-20261108.tar.gz`（被打包时的内容，早于本次移除，摘要以实测为准） |

### 下一步（按序）

1. 重启 system_server 后确认日志里不再出现 `OVERFLOW_HOOK_INSTALLED` / `OVERFLOW_CLOSE_DIRECT`，
   切应用时看到系统边缘浮标即为正常
2. 两处 Release 资产要换成 `869ebd78…`，归档重新打包，否则发布物与设备不一致
3. 小窗数量上限目前仍设成 1；若不要频繁出浮标，可拖回 5（系统默认）
4. 下次发布前仍走「开工闸门」五步，重启一律经 `restart-gate.sh`

### 本轮的坑（新会话别再踩）

- 一次改动 force push 了四次，于是同时挂了三个「等构建」的后台任务，其中两个等的是已被覆盖的旧提交 —— 改完之后只保留最后一个 sha 的等待器
- 判断「设备上是不是新代码」要拿 APK 的 sha256 与 CI 产物比，不要看版本号，版本号一直是 1.3.6
- grep -c 计数为 0 时退出码是 1，用 && 串起来的安装链路会整条断掉，改用 ;
