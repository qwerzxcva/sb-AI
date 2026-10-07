# 上游功能缺口对照报告（sb-AI ↔ LxBox / AsteriskBOX / ThroneForAndroid）

> 生成：对照上游参照源（~/LxBox、~/AsteriskBOX-main、~/ThroneForAndroid-main）与
> sb-AI 当前代码（com.sbai.*）逐项核对。结论按"已移植 / 缺失 / 有意不移植"三档。
> 判定标准：功能**真实生效**（有调用链 + 内核配置输出 + UI 入口），不算 UI 空壳。

## 一、已移植（✓ 功能完整）

| 上游特性 | sb-AI 实现 | 状态 |
|---|---|---|
| 订阅 URL / 自动更新 | SubscriptionManager + SubUpdateWorker | ✓ |
| 节点导入（vless/vmess/trojan/ss/hysteria2/wireguard + clash yaml/json + sing-box json） | ShareLinkParser + SubscriptionFormat + ClashYamlParser | ✓ |
| 配置生成 + 校验 + 内核拒绝节点自动禁用 | SingBoxConfigGenerator + NodeAutoDisabler | ✓ |
| 路由规则（自定义/预设/由规则自动推导） | RouteRulesScreen + buildRouteRule | ✓ |
| DNS（服务器/规则/per-rule 策略/分组/FakeIP） | DnsScreen + buildDnsServers/Rule | ✓ |
| 节点列表（订阅卡片 + 独立节点 + 搜索过滤） | HomeScreen + StandaloneNodeRow | ✓ |
| 节点编辑器 | NodeEditorScreen | ✓ |
| 节点健康（URLTest/延迟/批量测速） | NodeBatchTester | ✓ |
| VPN 服务（启停/开机自启/前台 keep-alive/卡死自愈） | SbAiVpnService + BootReceiver + VpnRuntimeState | ✓ |
| 每 app 代理（include/exclude package） | perAppProxy → include_package/exclude_package | ✓ |
| 实时状态（跨进程遥测/连接列表） | VpnRuntimeState + 7738495/50f9144 | ✓ |
| 诊断（日志） | LibboxRuntime + MonitorScreen | ✓（部分） |
| 自动化（控制接收器/QS 磁贴/开机） | VpnControlReceiver + SbTileService + BootReceiver | ✓ |
| WARP（一键注册向导） | WarpClient + WARP wizard | ✓ |
| DPI 硬化（ECH） | SingBoxConfigGenerator ECH 输出 | ✓ |
| 备份/恢复 | BackupManager | ✓ |
| 配置编辑器 | CustomConfigEditorDialog | ✓ |
| 负载均衡（round_robin + balancer pool） | LoadBalanceConfig + balancer{pool} | ✓ |
| detour/direct（订阅拉取绕过隧道） | SubscriptionManager detour=direct | ✓ |
| Tailscale endpoint | SingBoxConfigGenerator endpoint 注入 | ✓ |
| uTLS 指纹规范化 | UtlsFingerprintNormalizer | ✓ |

## 二、缺失（✗ 未移植，按优先级排序）

### P0 — 内核行为 / 数据正确性
1. **节点链（多跳）**（LxBox 006-DETOUR_AND_BALANCE · §018F）
   - 未移植。sing-box 通过 outbound 的 `outbound` 字段指向另一个 outbound tag 实现跳板；
     sb-AI 的 buildOutbounds 无此能力。
   - LxBox 不变量："跳板引用绝不静默退化为直连"（chain 缺 hop 时整条剔除，而非降级 direct）。
   - 价值：高（跳板/中转是刚需场景）。
2. **idle-suspend / 后台休眠**（LxBox 010-VPN_SERVICE · P17）
   - 未移植。仅 loadBalance 有 `idle_timeout`；tun/wireguard 层无 `lx.wg.idle_suspend`、
     无 auto-exit-on-idle。空闲隧道不自动挂起。
   - 价值：中（省电/省流）。实现量小（config-gen + 设置项）。
3. **流量分析器**（LxBox 028-TRAFFIC_PROFILER）
   - 未移植。MonitorScreen 仅"连接摘要"；无"每 app 连接日志 + 归属 + DNS trace + JSON 导出"。
   - 价值：高（诊断刚需）；依赖内核输出 per-app 事件，实现量大。

### P1 — 组织 / 易用
4. **工作区（命名配置集）**（LxBox 018-WORKSPACES）
   - 未移植。无"家/工作/实验"多套设置一键切换；设置是单一 AppState。
   - 价值：中。自包含（新增 Workspace 模型 + 存储 + UI），与他 AI 遥测无冲突。
5. **完整 Debug API / 崩溃报告**（LxBox 013-DIAGNOSTICS · 027-DEBUG_API）
   - 部分。LibboxRuntime 有基础；无本地 HTTP 控制面 /help、route map、crash report 聚合。
   - 价值：中。
6. **本地化 i18n**（LxBox 029-LOCALIZATION）
   - 未移植。界面全部硬编码中文，无 strings-<lang>。
   - 价值：低（sb-AI 目标用户以中文为主；暂不优先）。

## 三、有意不移植（设计边界，非缺口）

- **ROOT 路径**（AsteriskBOX 的 asteriskd 监督进程 / BPF / TPROXY / BPF2SOCKS / hevtun）
  - sb-AI 明确"不集成 Root / Magisk"（SettingsScreen L841 自述）。整条 ROOT 执行路径本就不做。
- **域名级分流 splitTunnel**（LxBox 011-SPLIT_TUNNELING 的 domains 部分）
  - 已有意移除（SingBoxConfigGenerator L435 注释"用户反馈：写规则不好么？"）。
    用户用路由规则编辑器即可完成等价效果；per-app 分流保留。
- **clash 热切换 API**（AsteriskBOX 用 setClashMode；sb-AI 用 sing-box Command API）
  - 内核契约不同（sb-AI = Leadaxe/sing-box-lx 的 Command API，非 experimental.clash_api）。

## 四、建议实施顺序（若继续做）

1. **idle-suspend / auto-exit**（P0 实现量最小，真实生效）
2. **工作区**（P1 自包含，无冲突）
3. **节点链**（P0 价值最高但复杂，需 UI + config-gen + 不变量测试）
4. **流量分析器**（P0，依赖内核事件，量最大）

> 注：以上判定基于代码静态核对（grep + 源码阅读），未做真机 e2e。
> 每项落地前需按"绝对能够使用"标准验证调用链 → 内核配置 → UI 入口三段。
