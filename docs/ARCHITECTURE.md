# FlymeFreeform 结构说明（契约层）

本文件定义“系统长什么样、边界在哪、判据由谁负责”。改动前先读这里；实现细节读代码注释；
踩坑历史读 CONSTRAINTS-ARCHIVE.md。

## 1 进程与职责（唯一权威）

| 进程 | 安装器 | 只做这些事 |
|---|---|---|
| system_server | SystemServerHookInstaller | 启动判定与参数注入、窗口数量与准入、窗外关闭、收窗策略替换、扇形协调器 |
| com.android.systemui | SystemUiHookInstaller | 底角触摸采集、通知点击转场 |
| com.android.launcher | LauncherHookInstaller | 桌面侧底角触摸、桌面页面状态上报 |
| com.coloros.smartsidebar:ui | SidebarHookInstaller | 「更多」面板接管、面板工具小窗注入 |

跨进程只有两条通道：侧边栏走 Binder（SidebarProtocol）；配置与状态走广播 + 远程 SharedPreferences。
禁止静态字段跨进程共享。

## 2 三条契约（判据只在这里定义）

### 契约 A：启动判定（是否把小窗参数写进这次启动）
- 实现：hook/launch/ActivityStarterZoomInjector（reasonFor 八段 + 抢占）
- 输入：调用方包/uid、目标、action、resultTo、来源标记、开关快照
- 输出：是否注入 + 早退原因
- 不变量：总开关关闭 → 不改写；排除列表前台 → 不改写；禁用浮窗（目标/发起方/前台）→ 不改写

### 契约 B：扇形启动（点扇形固定项之后做什么）
- 纯逻辑：logic/FanLaunchPolicy（无 Android 依赖、无反射、可单测）
- 事实采集：platform/coloros/ColorOsFreeformCoordinator（反射读任务树与厂商状态）
- 输入：目标包、该包现有窗口形态（无 / 小窗 / 迷你窗 / 浮标 / 后台全屏任务）
- 输出：FrontExisting | RestoreToWindow | LaunchAsZoom
- 不变量：已有可见普通小窗 → 不重新启动；浮标或迷你窗 → 恢复成普通小窗；无窗口 → 正常小窗启动

### 契约 C：收窗策略（系统要收浮标时怎么办）
- 纯逻辑：logic/ClosePolicy（可单测）
- 输入：退出策略种类、触发入口（系统裁决 / 用户手势）、autoCloseWindow 开关
- 输出：KeepOriginal | ReplaceWithToBack
- 不变量：模块从不自己关窗口；只在系统裁决路径上把 ToFloat 换成 ToBack，并当帧隐藏 surface

## 3 数据流

设置页 → 写远程 SharedPreferences → 各进程快照；
同时发普通广播（必须 setShareIdentityEnabled）→ 接收方 forceReload + override。

## 4 不变量（违反即 bug）

1. 模块不创建、不移动、不缩放窗口；只替换策略对象与隐藏 surface
2. 任何反射调用都必须能声明“目标类/方法/字段已用 dexsql 核实”
3. 任何在 hook 进程里注册的跨进程监听都视为不可靠；实时性只靠广播
4. 纯逻辑层不得引用 android.*（logic/ 下的文件）
5. 每个分支都必须有一条可搜索的日志码（SYSTEM_* / ZOOM_* / SIDEBAR_*）

## 5 未知项（写死在代码里之前必须实测）

| 未知 | 现状 | 决定方式 |
|---|---|---|
| 三种形态的状态码（小窗 / 迷你窗 / 浮标） | 厂商 getFlexibleZoomState() 取值未测 | 探针 :s&lt;值&gt;，各形态点一次扇形取映射 |
| 迷你窗的几何 | Task 边界仍是 1272×2800（截图已证） | 只能按状态码判，不看 bounds |
| 浮标是否在 mFlexibleTasks | 实测为否 | 不得作为判据 |

## 6 重构分片（每片独立可验收，禁止一次性大改）

| 片 | 内容 | 验收 |
|---|---|---|
| S1（本次） | 契约与约定文档、logic/ 纯逻辑骨架 | 文档与骨架评审 |
| S2 | 扇形启动改为“采集事实 → 纯逻辑决策 → 执行”三段；形态映射表落位 | 单测 + 真机一次 |
| S3 | 收窗改为 ClosePolicy 决策 + 唯一执行点 | 单测 + 日志成对出现 |
| S4 | 启动判定 reasonFor 拆为数据驱动规则表 | 单测覆盖八段 |
| S5 | 反射统一收敛到 platform/coloros/Reflect | 全仓 grep 检查 |
