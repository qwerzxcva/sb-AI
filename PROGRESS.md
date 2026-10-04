# sb-AI 进度（第 15 段续跑起点）

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
- 订阅编辑器表单（UA / hwid / dedupe / keywords / proxy / 机场名 / filterProtocol / filterRegion）
- 订阅源列表，按 lastUpdatedAt 降序
- 订阅导入（HTTP + HTTPS，失败时自动回退 HTTP）
- 进入首页卡顿优化（启动检查配置加超时保护）
- 节点表单完整编辑器（UTLS / 多路复用 / http-socks 等高级字段）
- 节点列表过滤器：名称搜索 + 无延迟过滤 + 名称/延迟排序 + L7Filter 协议/地区关键字
- **新**：订阅自动更新（WorkManager + BroadcastReceiver + AndroidX Startup，5h周期/15min flex）
- **新**：L7Filter 协议/地区关键字过滤（SubscriptionManager 集成）

### DNS
- DNS 服务器 CRUD（UDP/TCP/DoH/DoQ/DoT/Local/Hosts/FakeIP 全类型）
- DNS 规则 CRUD（rule_set/geosite/anti_lens/domain/ip/source）
- FakeIP 联动 DNS 自动规则（query_type A/AAAA）
- FakeIP 唯一性校验（sing-box 只允许一个 fakeip server）
- DnsRuleEditorDialog 整页编辑器（含 rewrite_ttl / client_subnet / ECH / query_types 全字段）

### UI/UX
- Kototoro 液态玻璃底栏（玻璃拟态 + 悬浮效果）
- 三端一致的圆角卡片间距
- 底部导航栏
- 节点池编辑
- 负载均衡策略切换 / 分组 / 自动优选
- 拆分隧道（Split Tunneling）：域名列表 → 直连规则注入
- 多配置 Profiles（Throne 基准）：保存/重命名/加载/删除快照
- 广播控制（ADB）：START_VPN / STOP_VPN / SUB_UPDATE / RESOURCE_UPDATE
- AsteriskBOX 资源管理：Resource 模型 + SettingsScreen UI + ConfigGenerator 注入 China IP 列表
- JSON 校验入口（SettingsScreen 配置覆盖区块）
- **修复**：监控页颜色统一（surfaceContainer）、首页 VPN 状态低频重试
- **新增**：SbItemWithBadge 组件（带计数徽章的条目）

### 其他
- 进程隔离（:core + UI 双进程架构）

---

## 🔄 进行中（第 9/10 轮）

### P0 待做
1. **FakeIP 测速后死节点禁用** — 需 CommandServer 暴露 urltest 结果或本地 ping 实现

### P2 待做
2. **JSON 配置编辑器 Schema 校验** — 现有 CustomConfigEditorDialog 只做 JSON 解析校验，缺 sing-box schema check

---

## 📦 编译/推送状态
- `./gradlew assembleDebug` BUILD SUCCESSFUL ✅
- `./gradlew testDebugUnitTest` BUILD SUCCESSFUL ✅
- 最新 commit 待推送到 GitHub

## Git log（最近 10 条）
```
[待 push] feat: AsteriskBOX 资源管理 + UI 一致性优化
[04a9a79] feat: JSON 校验 + 最终清理 + 进度文档更新
[4ef2dfd] feat: 多配置 Profiles + ADB 广播控制
[b48542d] feat: 拆分隧道（Split Tunneling）+ 配置覆盖预览修复
[62222c6] feat: 订阅自动更新 + 节点过滤器 + L7Filter + 监控页颜色修复
[2db0ca7] fix: 修复首页VPN状态不更新bug + 监控页颜色反差问题
```
