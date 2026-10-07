# sb-AI Batch 15：P1 Bug 修复 + P0 健强化

## 目标
- 修复已知的 P1 bug（代码级证据确认）
- 加固 P0 VPN 启动链路（无真机时的静态验证）
- 符合“绝对能使用”要求，每个改动对应真实调用链

## 背景
- 工作区：~/sb-AI-master (基于 250b01a, batch 14)
- 远程：fix/ 分支与 master 并排，不做 UI 冲突区
- 基线：`:app:testDebugUnitTest` 全绿，`:app:assembleDebug` 成功

## 已确认 Bug

### A: 订阅组无法删除 (P1-6)
- HomeScreen.kt L341-343: `onDelete = if (sub.url.isNotBlank()) {...} else null`
- WARP/分享链接/ClashYAML 导入的订阅 url 为空 → 无删除按钮

### B: 订阅源编辑保存报错 (P1-5)
- doSave() L1321-1322 强制 URL 非空，WARP 等空 URL 订阅保存失败
- 应允许无 URL 的本地/WARP 订阅更新字段（开关/名称等）

### C: 资源管理展开不可靠 (P1-4)
- SettingsScreen.kt L381: `rememberSaveable` 在 LazyColumn item 内，滚动回收后重置为折叠
- 用户点击展开看似"无响应"

### D: 服务错误跨进程丢失 (P0 辅助)
- persistError 写 "sbai_vpn" sp，UI 仅在 LaunchedEffect(Unit) 读一次
- 服务启动后崩溃的错误，UI 不刷新

## 实施步骤
1. 确认资源管理问题 + persistedError 消费处
2. Bug A 修复 + 确认框
3. Bug B 修复（doSave 允许空 URL）
4. Bug C 修复（移到顶层 rememberSaveable）
5. Bug D 加固（persistent error 监听 sp 变化）
6. testDebugUnitTest + assembleDebug 全绿
7. git status/diff review, push with fetch+rebase
8. 写 BATCH15_REPORT.md

## 验证原则
- 不写 UI 摆设：每个改动对应真实调用链
- 不引入新英文错误
- 不改 AndroidManifest / 不引入新权限
- 不触碰 fix/ 分支正在重写的 UI 文件（HomeScreen 订阅卡片、SettingsScreen 主题区）
