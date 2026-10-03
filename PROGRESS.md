# sb-AI 进度（第 4 段续跑起点）

> 目标：Kototoro 风格 UI + LxBox 内核/功能基准 + 三大代理 UI 交集功能 + 全面审核循环。
> 仅 ARMv8，Kotlin/Compose，**不集成 Root/Magisk**。

## 环境关键事实（必读，与第 3 段相同）

- 沙箱内 `github.com:443` 不可达；`api.github.com`/`codeload.github.com`/`repo1.maven.org` 可达。
- **推送统一走 `/workspace/push_api.py`**（GitHub Git Data API），token 在 `/workspace/.gh_token`。
- 远端：`qwerzxcva/sb-AI` 分支 `master`。最近推送：`08e97a6`（第二轮）。本轮代码**尚未推送**。
- 构建：`./gradlew :app:testDebugUnitTest :app:assembleRelease`（编译约 40s，release 约 1.5-2.5min）。
- compileSdk 必须 34（platforms 有 android-34 / android-37.0，build-tools 34.0.0）。
- **内核已切换为 LxBox 同款 `Leadaxe/sing-box-lx` v1.14.2-lx.11**（`app/libs/libbox.aar` 79MB arm64-only，不入 git）。
  - 下载+SHA256校验+裁剪脚本：`scripts/fetch-libbox.sh`（已重写，勿再用旧版指向 Asterisk4Magisk）。
  - gobind API 差异（已适配）：**没有** `usePlatformAutoRedirect`/`createAutoRedirect`（PlatformInterface 里已删）；
    **多了** `writeDNSQuery(DnsQuery)`（SbCommandClient 已加 no-op 实现）；其余（CommandServer/CommandClient/Connections/TunOptions/SetupOptions）一致。
  - lx 内核 AAR 解包参考在 `/tmp/lxsrc/io/nekohasekai/libbox/`（如目录被清理，重新解包 app/libs/libbox.aar 的 classes.jar）。
- 旧 AsteriskBOX 内核参考源码 jar：`/tmp/libbox-src/`（第 3 段解包，可能已不存在）。
- haze 1.5.2（`HazeState()` 构造 + `haze`/`hazeChild`，无 shape 参数）；androidx 版本被 force 锁定，见 app/build.gradle.kts。
- 子代理必须传 `model: "inherit"`（默认自定义模型 temperature 400 报错）。

## 用户第 4 轮反馈 → 完成状态

| 反馈 | 状态 |
|---|---|
| UI 被底栏挡住 | ✅ `BottomBarClearance`(132.dp) 常量 + 底栏加 `navigationBarsPadding()`；各页面底部 spacer 需统一改用它 |
| network 少一个 / protocol 少 | ✅ tcp/udp/**icmp**；protocol 全量 **bittorrent/dns/dtls/http/ntp/quic/rdp/ssh/stun/tls**（RouteRulesScreen 顶部常量） |
| 单条件 vs or 区别 | ✅ RuleLogic 删除 SINGLE，只留 AND/OR；卡片与编辑器显示明确说明；单类别时 OR 自动平铺不包 logical |
| DNS 页增加 DNS 规则（映射+自建） | ⏳ 模型/生成器已就绪（`DnsRule`、`autoDnsRules()`、手动规则在前），**DnsScreen 还没加 Tab** |
| 规则集与路由规则合并+优先级 | ✅ 规则集改为入口对话框（内联管理）；路由规则统一有序列表显示 `#优先级` + **长按拖动排序**（DragDropLazyColumn） |
| 负载均衡仅用 N 个节点 | ✅ 模型+生成器（round_robin + balancer{pool,pool_tolerance,sticky_hash}，fork 扩展）；**HomeScreen UI 还没加 pool/urltestMode 控件** |
| 订阅源增强 | ⏳ 模型已加字段（UA/interval/includeKeyword/excludeKeyword/traffic*）；**SubscriptionManager 还没用这些字段** |
| 路由规则粘贴 JSON 片段 | ✅ RouteRuleJsonCodec.fromJson/toJson + 编辑器「粘贴 JSON」按钮 |
| 自定义配置→覆盖（双优先级） | ✅ ConfigMerger + ConfigOverride 模型；**SettingsScreen 还是旧 customConfig UI，需重写** |
| 内核用 LxBox 的 sing-box | ✅ sing-box-lx v1.14.2-lx.11 |

## 下一步（按顺序执行）

1. **SbAiVpnService.kt**：把 `settings.customConfig` 引用改为 `configOverride`（编译会报错）：
   `val config = if (override.enabled && override.json.isNotBlank()) SingBoxConfigGenerator.generate(state) /* merged */ ...`
   实际直接调 `SingBoxConfigGenerator.generate(state)`（内部已处理覆盖），删掉 customConfig 分支。
2. **HomeScreen.kt**：负载均衡参数 UI 更新——`intervalSeconds`→`interval`（duration 字符串如 "15m"）、`idleTimeoutSeconds`→`idleTimeout`；
   新增「选点模式」（least_test/round_robin 说明卡片）、「节点池大小 N」（round_robin 时显示）、sticky_hash 多选；
   节点 tag 显示统一用 `SingBoxConfigGenerator.nodeTagOf(node)`。
3. **DnsScreen.kt**：Tab 改 3 个（DNS 服务器 / DNS 规则 / DNS group）。
   DNS 规则 Tab：手动规则用 DragDropLazyColumn（调 store.reorderDnsRules）；自动规则（`SingBoxConfigGenerator.autoDnsRules(state)`）只读展示带「自动」徽章；
   手动规则编辑器：域名/后缀/关键词/正则/IP-CIDR/规则集/network/port/query_type 多行输入 + server 下拉 + ip_strategy 下拉 + disable_cache/rewrite_ttl/client_subnet。
4. **SubscriptionManager.kt**：UA（subscription.userAgent ?: 默认）、https 强制、响应体 4MB 上限、
   解析后按 includeKeyword/excludeKeyword 过滤节点名、读 `subscription-userinfo` 响应头（upload/download/total/expire）写回 store。
5. **SettingsScreen.kt**：「自定义配置」分组改「配置覆盖」：启用开关 + 优先级 SegmentedButton（UI层最高/导入JSON最高）+
   导入 JSON 编辑对话框（同 CustomConfigEditorDialog 改字段名 configOverride）；删 DEFAULT_CUSTOM_CONFIG 或改为覆盖示例。
6. **测试**：SingBoxConfigGeneratorTest 更新——`intervalSeconds`→`interval`、`RuleLogic.AND` 默认、
   `customConfig`→`configOverride`；新增：balancer pool 输出、手动 DNS 规则顺序在前、ConfigMerger 用例（UI_HIGHEST 补充/IMPORT_HIGHEST 覆盖/数组按 tag 合并）、RouteRuleJsonCodec round-trip。
7. `./gradlew :app:testDebugUnitTest :app:assembleRelease`，全绿后 `cd /workspace && python3 push_api.py`。
8. 全面审核子代理（security + engineering，model=inherit）→ 修复 → 复验 → 推送。

## 架构速查（本轮变更后）

```
data/Models.kt:
  RuleLogic { AND, OR }          // 无 SINGLE
  LoadBalanceConfig { mode, urltestMode: LEAST_TEST/ROUND_ROBIN, pool, poolTolerance, stickyHash: List<StickyHashKey>,
                      checkUrl, interval("15m"), toleranceMs, idleTimeout("30m"), interruptExistConnections, autoEnabled, outbounds }
  DnsRule { id, enabled, autoFromRouteRuleId(null=手动), name, domains/domainSuffixes/..., ruleSetTags, ipCidrs,
            networks, ports, queryTypes, server, ipStrategy, disableCache, rewriteTtl, clientSubnet }
  Subscription { ..., userAgent, updateIntervalHours, includeKeyword, excludeKeyword, trafficUpload/Download/Total/Expire }
  ConfigOverride { enabled, priority: UI_HIGHEST/IMPORT_HIGHEST, json }
  AppSettings { ..., configOverride }   // customConfig 已删除
service/:
  SingBoxConfigGenerator  // generate()=含覆盖；generateUiOnly()；autoDnsRules(state)；nodeTagOf()
  ConfigMerger            // 深度合并
  RouteRuleJsonCodec      // 规则 JSON 片段 <-> RouteRule
  ShareLinkParser/SubscriptionManager/SbCommandClient/SbPlatformInterface/LibboxRuntime/SbAiVpnService/SbTileService/BootReceiver
ui/:
  components/ SbComponents(SbGroup/SbItem/.../BottomBarClearance) + DragDropLazyColumn(长按拖动)
  routes/RouteRulesScreen  // 统一有序列表+规则集管理对话框+JSON粘贴+拖动排序
  home/dns/monitor/settings/theme + MainActivity(GlassBottomBar)
```

## 已知坑（本轮新踩过的）

- PlatformInterface 的 `put()` 在 buildJsonObject lambda 里返回旧值（首次为 null），
  `?.let{...} ?: fallback` 会因 let 返回 null 而误触发 fallback——必须写成
  `val x = query["k"]?.takeIf{...}; put("k", x ?: default)` 模式（ShareLinkParser 已踩过，RouteRuleJsonCodec 用了正确模式）。
- gobind 接口类没有 AutoRedirect（lx 1.14.2），加了 @Override 会编译失败，已删。
- DragDropLazyColumn 的 onDragEnd 用 LaunchedEffect(draggingIndex) 触发，参数是 keys 列表（调 store.reorderXxx）。
