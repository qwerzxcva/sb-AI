# sb-AI 进度（第 13 段续跑起点）

> 目标：Kototoro 液态玻璃 UI + LxBox 内核/功能基准 + AsteriskBOX 全量规则字段 + 全面审核循环。
> 当前核心状态（2026-05-04）：

## ✅ 已完成（可运行、测试通过）

### 首屏/路由
- 路由规则 CRUD 编辑器（二级页面，非弹窗），含拖拽排序 + 导入粘贴
- fakeIP 自定义段（inet/inet6）+ 自动 DNS 规则生成
- 流量出口（proxy）+ 直连（direct）规则集
- 协议匹配全量（biflow/dscp/interface/inbound/port/protocol/socket_type/type）
- ICMP 出站（icmp → icmpv4/icmpv6）
- 逻辑 AND/OR
- 规则 JSON（raw + schema 校验）
- 规则 Tag 留空自动生成（r-1/r-2）

### 订阅/节点
- 订阅编辑器表单（UA / hwid / dedupe / keywords / proxy / 机场名）
- 订阅源列表，按 lastUpdated 降序
- 订阅导入（HTTP + HTTPS，失败时自动回退 HTTP）
- 进入首页卡顿优化（启动检查配置加超时保护，避免主线程阻塞）
- 节点表单完整编辑器（UTLS / 多路复用 / http-socks 等高级字段）

### UI/UX
- Kototoro 液态玻璃底栏（玻璃拟态 + 悬浮效果）
- 三端一致的圆角卡片间距
- 底部导航栏
- 节点池编辑
- 负载均衡策略切换
- 负载均衡分组
- 负载均衡自动优选

### DNS
- DNS 服务器 CRUD（UDP/TCP/DoH/DoQ/DoT/Local/Hosts/FakeIP 全类型）
- DNS 规则 CRUD（rule_set/geosite/anti_lens/domain/ip/source）
- FakeIP 联动 DNS 自动规则（query_type A/AAAA）
- FakeIP 唯一性校验（sing-box 只允许一个 fakeip server）

### 其他
- 进程隔离（:core + UI 双进程架构）

---

## 🔄 进行中（本次工作段）

### Bug 修复
1. **首页 VPN 点击不变化** ✅ 已修复并提交本地
   - 根因：`connectWithRetry` 首次超时时，`:core` 进程实际已启动（只是 CommandServer 连接慢）
   - 修复：新增 `LaunchedEffect(coreRunning)` 低频重试协程（每 8s 重连），确保状态最终同步
   - 文件：`HomeScreen.kt` + `SbCommandClient.kt`（已有 `connectWithRetry(attempts, delayMs)` 重载）

2. **监控页颜色反差过大** ✅ 已修复并提交本地
   - 根因：`StatusHeroCard` 连接时使用 `primaryContainer`（高饱和蓝色），与首页的 `surfaceContainer` 不一致
   - 修复：统一使用 `surfaceContainer` + `onSurface`，仅通过图标颜色区分状态
   - 文件：`MonitorScreen.kt`

### 调研项目
- [ ] LxBox（功能最全）— 研究完毕，差距清单已列出
- [ ] AsteriskBOX（规则字段最全）— 待研究
- [ ] ThroneForAndroid（NekoBox 新版）— 待研究

---

## 📋 待做（移植清单）

### 来自 LxBox（优先）
- [ ] **节点健康检测**：URLTest ping 测速 + 死节点自动禁用
- [ ] **订阅自动更新**：后台定时拉取（2min/小时触发），断网容错，crash-safe init
- [ ] **拆分隧道**（Split Tunneling）：按 App 排除走直连
- [ ] **跳板/中转**（Detour/Hop chains）：代理链节点支持
- [ ] **节点列表过滤器**：按协议/地区/延迟/关键字筛选排序

### 来自 AsteriskBOX
- [ ] **完整规则字段**：geosite / geoip / rule_set 全量支持
- [ ] **China IP 列表**直出优化
- [ ] **出站接口选择**（Interface 规则）

### 来自 ThroneForAndroid
- [ ] **JSON 配置预览 + Schema 校验**（Throne 自带 sing-box schema check）
- [ ] **多配置切换**（profiles 功能）

---

## 🚫 网络问题
- GitHub push 当前持续失败（Connection reset by peer）
- 本地 commit 已完成：`0511f50 fix: 修复首页VPN状态不更新bug + 监控页颜色反差问题`
- 需网络恢复后推送

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
