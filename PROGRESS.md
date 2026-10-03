# sb-AI 进度（第 12 段续跑起点）

> 目标：Kototoro 液态玻璃 UI + LxBox 内核/功能基准 + AsteriskBOX 全量规则字段 + 全面审核循环。
> 仅 ARMv8，Kotlin/Compose，不集成 Root/Magisk。

## 环境关键事实

- 推送用 `/workspace/push_api.py`（token `/workspace/.gh_token`，已加 4 次重试，单次推送可能 5-10 分钟）。
- 远端 `qwerzxcva/sb-AI@master`，已推送 `752b7b5`（第 10 轮）。**本轮审核修复未推送**。
- 内核 `Leadaxe/sing-box-lx` v1.14.2-lx.11（`scripts/fetch-libbox.sh`）。体积 28MB。
- compileSdk 必须 34（沙箱 `android-37.0` 非标准命名，35/37 不可用；compose BOM 锁 2024.12.01）。
- backdrop 液态玻璃 vendor 在 `backdrop/`（已 patch 兼容 34）。Kotlin 2.0.21。
- 子代理 `model:"inherit"` 可用但慢/可能超时；超时后自己查更快。

## 第 10 轮（已推送 752b7b5）+ 审核修复（进行中）

第 10 轮 15 项反馈全部实现（节点表单编辑器 utls/多路复用/http-socks、二级页面、进程隔离 :core、
订阅 http/detour/机场名、QUERY_ALL_PACKAGES、tag 自动生成、日志 debug UI、监控页流量聚合卡）。

**第 10 轮全面审核已完成**（工程+安全子代理），发现并修复的真实缺陷：

| 缺陷 | 状态 |
|---|---|
| P0 `CHANGE_NETWORK_STATE` 权限缺失 → 订阅 detour=direct 的 `setProcessDefaultNetwork` 会 SecurityException 静默失败 | ✅ 已在 AndroidManifest 加权限 |
| P0 `:core` 进程 RuleStore 快照过期 → UI 改配置后内核仍用旧值 | ✅ 加 `RuleStore.reload()`（含解析失败备份防清空）+ 服务启动前调用 |
| P1 fakeIP 自动 DNS 规则（query_type A/AAAA）放最前，吞掉全部手动规则 | ⏳ **正在修，见下** |
| P2 lb-selector(MANUAL) 嵌 auto(urltest) 单成员包装退化（用户选 urltest 时会忽略手动选择） | 待修：auto 包单成员时直接引用 finalProxyTag |
| P1 fakeIP 默认段 198.18.0.0/15 是 TEST-NET-2，可能与真实流量冲突 | 待改：默认改 10.0.0.0/8 |
| P1 订阅 detour=proxy 未实现（UI 有选项但实际不生效） | 待实现：走 :core 内 fake tunnel outbound 或明确标注不支持 |
| P0 Clash 模式（global/direct/rule）未实现（route.rules 里用了 clash_mode 字段但无切换入口） | 待实现：CommandClient.setMode + 首页切换 UI |
| P2 玻璃底栏「采样背景」实现不正确（performanceReport 证实） | 待修：layerBackdrop 捕获范围/位置 |
| P2 launcher icon 是单色灰底非渐变 | 待修 |
| P2 HWID 懒生成时机（首次自动更新可能全空） | 待修 |

## 当前卡点（必须先做）

**fakeIP 联动 DNS 规则顺序修复（进行中）**：
我在 `SingBoxConfigGenerator.buildDnsRules` 里删掉了放在最前的 fakeIP 联动块（它会吞掉手动规则），
但还没在**手动规则之后**补回。正确顺序应为：
1. 用户手动 DNS 规则（dnsRules，最高优先级）
2. 规则集 ip_strategy 规则
3. 路由规则自动推导的 DNS 规则（autoDnsRules）
4. **fakeIP 联动规则（query_type A/AAAA → fakeip server）——放最后，作为兜底**

需要：在 `buildDnsRules` 返回 result 之前（手动规则+规则集规则+自动规则都加完后），
追加 fakeIP 联动块：
```kotlin
state.dnsServers.firstOrNull { it.enabled && it.type == DnsServerType.FAKEIP && it.tag.isNotBlank() }
    ?.let { fake ->
        result.add(buildJsonObject {
            putJsonArray("query_type") { add("A"); add("AAAA") }
            put("server", fake.tag)
        })
    }
```
对应测试 `fakeip custom ranges and auto rules` 期望 fakeIP 规则存在（已存在断言），
但它断言顺序可能要看是否还成立——跑单测确认。

## 下一步

1. 补回 fakeIP 联动规则到尾部 → 编译 + 单测（确认 `fakeip custom ranges and auto rules` 顺序断言）。
2. 改 fakeIP 默认段为 10.0.0.0/8（生成器两处 + DnsScreen 提示文案）。
3. 修 lb-selector 嵌 auto 退化：auto 的 outbounds 只有 1 个成员时不套 urltest，直接引用 finalProxyTag。
4. 推送（`python3 push_api.py`）。
5. 继续处理 P0 Clash 模式切换 / P1 订阅 detour=proxy / P2 玻璃底栏。
