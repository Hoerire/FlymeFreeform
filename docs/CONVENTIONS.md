# 编码约定（必须遵守，评审按此打回）

## 命名
- 类：名词短语（FanLaunchPolicy / ClosePolicy / WindowForm）
- 纯逻辑函数：动词开头（decide / classify），只返回结果，不做副作用
- 反射封装：以 Access 结尾（ColorOsTaskAccess），且只暴露语义方法，不暴露原始 Method

## 分层
- logic/：零 Android 依赖、零反射、纯函数，必须可单测
- platform/coloros/：只做“事实采集”与“执行动作”，不含策略判断
- hook/：只做挂钩与转发，不含业务判断

## 注释要求
- 注释写“为什么”和“约束”，不写“做了什么”
- 每条实测结论必须带证据形式：日志码、日志时间、dumpsys 片段、或字节码方法名
- 禁用词：“应该”“大概”“一般来说”。不确定就写进 ARCHITECTURE.md 的未知项表

## 日志
- 格式：全大写下划线，带可检索前缀（SYSTEM_ / ZOOM_ / SIDEBAR_ / FFLAUNCH_ / FFOUT_）
- 判定类日志必须能回答“为什么走这条分支”：把输入值带进日志
- 探针日志用 PROBE_ 前缀，验收后删除，不允许长期残留

## 反射
- 每次新增反射点，先在 /system/framework/oplus-services.jar 用 dexsql 确认签名，并在注释里写出方法签名
- 反射失败必须 fail-open（保持原行为）并打一条 WARN，不许静默吞掉

## 变更纪律
- 一次构建只验一个行为变量；合并需求时先杀旧构建
- 每片重构独立提交，提交信息写“为什么改 + 验收方式”
