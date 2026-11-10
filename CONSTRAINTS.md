# 项目说明（读这一页）

详细历史和逐条排障记录在 CONSTRAINTS-ARCHIVE.md，需要时再查，不要整篇读。

## 规则

1. 先回答再动手。构建、装包、重启、推送之前，先在回复里说清改了什么、装哪个包、sha256、验什么、看哪条日志，等用户点头。
2. 一次只跑一个构建。有新需求先停掉在跑的任务，合并完再出包；不要挂后台长任务。
3. 新增反射点先用 dexsql 在 /system/framework/oplus-services.jar 里确认签名，没确认不写代码。
4. 判断要能落到一条日志上，否则等于没有。

## 构建与发布

1. `node tmp/api-push5.js <文件…> [rm:<路径>]` 推送到 main（幂等，只推有差异的文件）
2. `node tmp/dlM.js <commit>` 等 CI 并取产物（前台轮询，不后台常驻）
3. `zipalign -P 16 -f 4` → `apksigner sign --ks release.keystore --ks-pass pass:android --key-pass pass:android --ks-key-alias flymefreeform --min-sdk-version 23`
4. `cp` 到 /data/local/tmp → `pm install -r -d`
5. 重启只热重启 system_server：`sh restart-gate.sh --changed … --pkg … --verify … --logs … -- "killall system_server"`
6. 发布 `node tmp/release.js <versionName> <versionCode> <apk> <body.txt>`；body 只列功能，首行「扇形菜单」

## 不要碰的东西

- 不进远端：`CONSTRAINTS*.md`、`tmp/`、`out/`、`*.keystore`、`PUSH_TOKEN.txt`
- 模块不自己关窗口，只换退出策略并隐藏 surface
- 日志在 `/data/adb/lspd/log/modules_*.log`，本机 grep 是 BRE，要加 `-E`
- 重装 APK 后必须重启进程，否则测的是旧代码

## 当前状态

| 项 | 值 |
|---|---|
| 版本 | 2.1 / versionCode 2026111028 |
| 设备已装 | `c25d550783e0a1f884cfdad65d818b87248080538ba04b5cd76383f826b12d56` |
| 远端 main | `6185774a` |

## 单元测试

147 条全绿（2026-11-10 09:28）。之前长期红的 4 条已修：手势两条按「多指不取消、换发起手指才取消」重写；
面板入场曲线改为自算 cubic-bezier 并取二分上界（中点会在顶部抖 4e-5）；图标几何按 2.5 倍命中半径、保持圈 0 修正。
