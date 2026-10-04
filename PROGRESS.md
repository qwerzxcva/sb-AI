# sb-AI 进度（第 12 段续跑起点）

> 目标：Kototoro 液态玻璃 UI + LxBox 内核/功能基准 + AsteriskBOX 全量规则字段 + 全面审核循环。
> 仅 ARMv8，Kotlin/Compose，不集成 Root/Magisk。

## 环境关键事实

- 推送用 `/workspace/push_api.py`（token `/workspace/.gh_token`，已加 4 次重试，单次 5-10 分钟）。
- 远端 `qwerzxcva/sb-AI@master`，最近推送 **`eec58f6`（第 11 轮）**。CI run 37141177504 构建中。
- 内核 `Leadaxe/sing-box-lx` v1.14.2-lx.11。体积 28MB。compileSdk 必须 34。
- backdrop 液态玻璃 vendor 在 `backdrop/`（已 patch 兼容 34）。Kotlin 2.0.21。
- 子代理 `model:"inherit"` 可用但慢/可能超时。

## 第 11 轮已完成（用户 4 项反馈，已推送 eec58f6）

| 反馈 | 根因/修复 |
|---|---|
| 订阅依旧无法导入 | **根因：Android 9+ 默认禁明文 HTTP**，机场订阅大量 http:// → 拉取静默失败。加 `usesCleartextTraffic=true`。解析器经单测验证无误（5 节点全解析） |
| 进入/返回首页卡顿 | `SbCommandClient.connect()` 阻塞 socket 在主线程 LaunchedEffect 跑 → 移 Dispatchers.IO；玻璃底栏 blur 40→8（大半径每帧全页采样） |
| DNS 服务器编辑器没改二级页面 | 已改整页 Scaffold+TopAppBar+BackHandler |
| 所有弹窗改二级页面 | 6 个主编辑器全部整页化：DNS服务器/DNS group/DNS规则/路由规则/规则集/订阅/节点 |

**第 10 轮审核修复也已包含在 eec58f6**：CHANGE_NETWORK_STATE 权限、RuleStore.reload()（:core 进程配置同步）、
解析失败备份防清空、fakeIP 联动规则移尾部、fakeIP 默认段改 10.0.0.0/8、auto 仅多候选出口时套。

测试 73 例全绿。

## 待办（用户历史未满足 + 审核遗留）

1. **仍是弹窗的小对话框**（用户说"所有弹出式"，但这些是轻量确认/输入，可酌情）：
   - RouteSetManagerDialog（规则集列表管理，从路由页"规则集"入口进）
   - CustomConfigEditorDialog（设置页配置覆盖 JSON 编辑）
   - ConfigPreviewDialog（配置预览只读）
   - TextEditDialog（单行文本输入，负载均衡参数用）
   - JsonPasteDialog / DnsJsonPasteDialog（JSON 粘贴，已是可编辑大框）
   - AppPickerDialog（应用选择器，带搜索多选，弹窗合理）
   - 负载均衡模式选择、节点选择对话框
   → 若要彻底，把 RouteSetManagerDialog 和 CustomConfigEditorDialog 也改整页。

2. **P0 Clash 模式切换未实现**：route.rules 用了 clash_mode 字段但无 global/direct/rule 切换入口。
   需 CommandClient.setMode + 首页切换 UI。

3. **P1 订阅 detour=proxy 未实现**：UI 有选项但实际不生效（只有 direct 绑底层网络）。

4. **P2 玻璃底栏采样**：blur 已降到 8，但 layerBackdrop 仍整页捕获。若仍卡可考虑只捕获底部区域。

5. **监控页"黑色背景"**：用户连续多轮反馈。已加渐变+标题+流量聚合卡，但深色主题 background=0xFF131720 偏暗。
   可能需要默认浅色主题或进一步提高卡片对比度。

6. **真机验证从未做过**：VPN 启动、TUN、libbox 运行、液态玻璃观感、跨进程 CommandClient 全靠编译+单测。

7. **"三大代理功能还缺一半"**：LxBox 的 WARP 向导/链式代理/分流方向/流量profiler/trace explorer/
   规则集在线替换/速度测试页；Throne 的多配置切换/per-profile 设置。尚未系统对齐。

## 用户红线

只 ARMv8；无 Root/Magisk；UI 参考 Kototoro（液态玻璃）+ ClashFest（首页/订阅观感）；功能以 LxBox 为准；
负载均衡并入首页；规则集与路由规则合并显示优先级；长按拖动排序；network 含 icmp；protocol 全量；
逻辑运算只有 AND/OR；路由/DNS 规则可粘贴+输入 JSON；配置覆盖双优先级；订阅 UA/hwid/去重(按配置)/关键字/出口/机场名；
TUN 地址段+stack；fakeIP 自定义段+联动；体积 40MB 内；**编辑器用二级页面不用弹窗**；tag 留空自动生成；每轮全面审核。
