# sb-AI 进度（第 6 段续跑起点）

> 目标：Kototoro/ClashFest 风格 UI + LxBox 内核/功能基准 + AsteriskBOX 全量规则字段 + 全面审核循环。
> 仅 ARMv8，Kotlin/Compose，不集成 Root/Magisk。

## 环境关键事实（与前几段相同）

- `github.com:443` 不可达；推送用 `/workspace/push_api.py`（token 在 `/workspace/.gh_token`）。
- 远端 `qwerzxcva/sb-AI@master`，最近推送 `7c09468`（第 5 轮）。本段有未推送改动。
- 内核：`Leadaxe/sing-box-lx` v1.14.2-lx.11，`scripts/fetch-libbox.sh` 下载+校验+裁剪 arm64。
- 参考仓库：`/workspace/LxBox`、`/workspace/AsteriskBOX`、`/workspace/ThroneForAndroid`、`/workspace/references`(Kototoro)、`/workspace/flclash`、`/workspace/clashfest`(Nemu-x)。
- 子代理必须 `model: "inherit"`。
- **体积优化已生效**：`app/build.gradle.kts` release 里 `useLegacyPackaging=true`（libbox.so DEFLATE 压缩）+ `isMinifyEnabled=true` + `isShrinkResources=true` → 89MB→28MB。单测仍全绿。
- Python heredoc 与后续命令**换行分隔时是独立语句**，前面失败后面照样执行——拼接文件时务必用 `&&` 串联或全部放 heredoc 里。

## 用户第 6 轮反馈 → 完成状态

| 反馈 | 状态 |
|---|---|
| 体积 90MB→40MB 左右 | ✅ 28MB（DEFLATE + R8 + shrinkResources） |
| 路由/DNS 规则参数太少（参考 AsteriskBOX） | ⏳ 模型+生成器+RouteRuleJsonCodec 已扩充全量字段；**编辑器 UI 拼接未完成（见下）** |
| 监控页黑/丑 | ⏳ 已加 StatusHeroCard/TrafficCard（第 5 段），haze 参数已调 |
| 底栏玻璃磨砂 | ✅ haze 参数调到 Kototoro 水平（blur 16dp、surface 0.5 alpha、hairline） |
| hwid（LxBox） | ✅ 订阅身份系统：AppSettings.subscriptionUserAgent/subscriptionSendHwid/subscriptionHwid/deviceOs/verOs/deviceModel；SubscriptionManager 发 x-hwid/x-device-os/x-ver-os/x-device-model；设置页 UI + UUID 懒生成 |
| user agent（AsteriskBOX） | ✅ 全局 subscriptionUserAgent（订阅级 override > 全局 > 品牌 UA） |
| 功能还缺一半 | ⏳ 持续补齐中 |

## 当前卡点（必须先解决）

**RouteRuleEditorDialog 拼接失败**：
- 新编辑器全文在 `/workspace/sb-AI/new_editor.kt`（全量字段 + EditorSection 折叠 + 粘贴 JSON + rejectMethod）。
- 旧编辑器在 `app/src/main/java/com/sbai/ui/routes/RouteRulesScreen.kt`。
- 需要：定位旧编辑器的精确行范围（从 `// 路由规则编辑器` 注释块到 RouteRuleEditorDialog 函数的收尾 `}`），
  用 new_editor.kt 内容替换。**不要再猜行号**——先用 `grep -n` 找到 `private fun RouteRuleEditorDialog(` 的行号 start，
  再找它后面第一个独立的 `}`（函数结束）行号 end，用 python 按 [start-1 .. end] 替换。
  替换后删掉 new_editor.kt，确认 `EditorSection` 只有一个定义。
- 新编辑器引用 `EditorSection`（折叠区块）、`rejectMethod`、所有新字段，拼完必须编译验证。

## 新字段清单（已加入 RouteRule 模型）

sourceIpCidrs, sourcePorts, sourcePortRanges, packageNames, processNames, processPaths,
users, userIds, networkTypes(wifi/cellular/ethernet), wifiSsids, wifiBssids, inbounds,
clashMode, sourceIpIsPrivate, ipIsPrivate, networkIsExpensive, rejectMethod(default/drop)。

生成器：AND 平铺 / OR 按 6 类（DOMAIN/IP/SOURCE/TRANSPORT/APP/NETENV）拆分；
BLOCK 用 `action=reject` + `reject_method`（不再用 outbound=block）。

## 下一步

1. 完成编辑器拼接（见上），编译。
2. DNS 规则字段扩充（参考 AsteriskBOX SingBoxDnsRuleState：rcode/answer/ns/extra/timeout 等）→ DnsRule 模型 + 生成器 + 编辑器。
3. `./gradlew :app:testDebugUnitTest :app:assembleRelease` 全绿。
4. 推送 → 全面审核（model=inherit）→ 修复 → 复验 → 推送。

## 用户历史反馈红线（务必遵守）

只 ARMv8；无 Root；UI 参考 Kototoro/ClashFest；负载均衡并入首页；规则集与路由规则合并显示优先级；
长按拖动排序；network 含 icmp；protocol 全量 10 项；逻辑运算只有 AND/OR；DNS 页有手动+自动规则；
路由规则可粘贴 JSON 片段；配置覆盖双优先级；订阅有 UA/hwid/关键字过滤；TUN 地址段可自定义；
体积要 40MB 左右（已达 28MB）。
