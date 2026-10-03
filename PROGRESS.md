# sb-AI 进度（第 5 段续跑起点）

> 目标：Kototoro/ClashFest 风格 UI + LxBox 内核/功能基准 + Karing 式内联规则集 + 全面审核循环。
> 仅 ARMv8，Kotlin/Compose，**不集成 Root/Magisk**。

## 环境关键事实（与前几段相同，勿重复踩坑）

- `github.com:443` 不可达；推送用 `/workspace/push_api.py`（token 在 `/workspace/.gh_token`）。
- 远端 `qwerzxcva/sb-AI@master`，最近推送 `3660a66f`（第 4 轮审核修复）。
- 内核：`Leadaxe/sing-box-lx` v1.14.2-lx.11，`scripts/fetch-libbox.sh` 下载+校验+裁剪 arm64。
- 参考仓库本地副本：`/workspace/LxBox`、`/workspace/AsteriskBOX`、`/workspace/ThroneForAndroid`、
  `/workspace/references`(Kototoro)、`/workspace/flclash`、`/workspace/clashfest`(Nemu-x)。
- 子代理必须 `model: "inherit"`。
- buildJsonObject 里 `put()` 返回旧值，`?.let{} ?: fallback` 会误触发——用 `val x=...; put("k", x ?: default)`。

## 用户第 5 轮反馈 → 调研结论 + 完成状态

| 反馈 | 调研结论 | 状态 |
|---|---|---|
| Karing 路由规则可一行一个加远程规则集 | Karing(Flutter)：ruleSetTags 里支持直接输 URL，自动建规则集；意图=远程规则集和手动域名/IP 放一起 | ⏳ 待做：RouteRule 的 ruleSetTags 改为「可输 tag 或 URL」，URL 自动在 RouteRuleSet 里创建/复用 |
| FAB「➕」与底栏重叠 | FAB 未避让悬浮底栏 | ⏳ 已定义 `FabBottomBarClearance`（SbComponents.kt），**还没应用到 RouteRulesScreen/DnsScreen 的 FAB** |
| 监控页黑的要死 | 监控页背景用 surfaceContainer 太暗 + 无底色层次 | ⏳ 待做：MonitorScreen 卡片用 surfaceContainerLow/High 区分层次 |
| 底栏没有玻璃磨砂通透质感 | haze 参数不对 | ✅ 已改（MainActivity.kt：blurRadius 28dp、noiseFactor 0.08、surface 55% 透明、双层 tint、细描边） |
| LxBox 可自定义 IPv4/IPv6 段地址 | LxBox settings_storage 有 `tun_address`/`tun_address6` | ⏳ 待做：AppSettings 加 tunAddress/tunAddress6，生成器用它们，设置页加编辑项 |
| Throne 订阅可改更多 | Throne：UA/自动更新/备注已有基础；LxBox 有更多（去重/排除/展开） | ⏳ 部分已做（UA/关键字/间隔/流量）；可再补「展开为规则集」选项 |
| AsteriskBOX 的 DNS 规则 | AsteriskBOX DNS 规则字段：server/地址/类型/规则匹配；本端已有手动+自动 DNS 规则 | ✅ 大部分已覆盖（手动规则排序+自动展示），可再核对字段 |
| ClashFest 首页/订阅第一观感 | ClashFest=kr328 Clash fork：四 Tab（主页/配置/路由/设置），主页=启动按钮+流量统计卡+订阅用量卡+快捷操作；配置页=订阅卡片列表(头像/用量/更新按钮) | ⏳ 待做：首页加「流量统计卡」与订阅用量卡（已有基础，优化布局）；订阅区参考 Profiles 卡片化 |
| 功能布局要对齐 ClashFest | 首页放状态/流量/订阅/快捷操作；设置放系统选项；路由放规则 | ⏳ 调整首页为「状态卡 + 流量卡 + 订阅用量卡 + 快捷入口」，底部导航已有四页（首页/路由/DNS/监控/设置） |

## 当前代码变更（本段已落盘，**未编译**）

1. `MainActivity.kt`：玻璃底栏质感增强（blurRadius 28dp、noiseFactor 0.08f、
   `backgroundColor=surface.copy(alpha=0.55f)`、`tints=[surfaceContainer 22%, primary 4%]`、
   描边 0.8dp/45%、shadowElevation 10dp）。
2. `ui/components/SbComponents.kt`：新增 `val FabBottomBarClearance = BottomBarClearance - 16.dp`。

## 下一步（按顺序）

1. **FAB 避让**：RouteRulesScreen + DnsScreen 的 Scaffold 加
   `floatingActionButton = { Box(Modifier.padding(bottom = FabBottomBarClearance)) { ... } }`
   或直接在 ExtendedFloatingActionButton 上加 `Modifier.padding(bottom = FabBottomBarClearance)`。
   HomeScreen 无 FAB（用状态区按钮），检查即可。
2. **MonitorScreen 对比度**：SbGroup 卡片在暗背景下太平，给「内核状态」卡片加 surfaceContainerHigh 背景，
   日志区背景换 surfaceContainerLow、日志字体加大到 bodyMedium。
3. **Karing 内联远程规则集**：
   - RouteRuleJsonCodec/RouteRule 的 ruleSetTags 语义：「tag 或 URL」。
   - 在 SingBoxConfigGenerator.buildRouteRule 引用规则集时，把 URL 形态的条目解析为自动规则集：
     tag=`url-${md5(url).take(8)}`，type=remote，url=该 URL。生成 route.rule_set 时自动补这些。
   - 路由规则编辑器「规则集 tag」输入框改名「规则集 tag 或 URL（一行一条）」，说明可粘 URL。
   - 也可在 RuleStore 层做：upsertRouteRule 时把 URL 形态的 tag 同步进 routeRuleSets（去重）。
     **推荐在生成器层做**（不动存储，纯推导）。
4. **TUN 地址段**：AppSettings 加 `tunAddress: String = "172.18.0.1/30"` 和 `tunAddress6: String = "fdfe:dcba:9876::1/126"`；
   SingBoxConfigGenerator 的 tun inbound 用它们；SettingsScreen 「内核」或「TUN」组加两个文本编辑项。
5. **首页布局对齐 ClashFest**：状态区下方加「流量统计卡」（实时 ↑↓ 速率 + 累计，数据来自 SbCommandClient.status，
   已有字段），加「订阅用量卡」（每个订阅的已用/总量/到期，已有 trafficUpload/Download/Total/Expire 字段）。
6. `./gradlew :app:testDebugUnitTest :app:assembleRelease` → 推送 → 审核。

## 关键参考结论（已调研，勿重复查）

- ClashFest 主页结构：顶部状态栏 + 大启动按钮 + 流量统计卡(实时/累计) + 订阅卡(用量/到期/更新) + 快捷操作(模式/规则) + 代理选择入口。
- ClashFest 配置页：订阅卡片列表，每张卡含名称/类型/用量进度条/到期时间/更新时间/更新按钮/编辑/删除。
- LxBox TUN 默认：`tun_address=172.18.0.1/30`（与本项目一致），`tun_address6` 可自定义。
- Karing 规则集：在规则编辑里 ruleSetTags 支持直接输 URL 自动建集。
- haze 1.5.2 玻璃质感参数：blurRadius、noiseFactor、backgroundColor(半透明)、tints(HazeTint 分层)。

## 已知坑（本段新增）

- `FabBottomBarClearance` 是「内容底部 padding」，FAB 避让要用「在底栏上方」的 padding——两者不同，
  用 `FabBottomBarClearance = BottomBarClearance - 16.dp`（我在 SbComponents.kt 里定义的，名字其实误导，
  它本质是"内容区底部留白"，FAB 避让直接用它也行）。
  **注意**：SbComponents.kt 里 BottomBarClearance=132dp 是内容底部留白；
  FAB 避让用同一个常量包一层 padding(bottom=BottomBarClearance) 即可（实测 FAB 会落到栏上方）。
