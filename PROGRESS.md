# sb-AI 进度（第 10 段续跑起点）

> 目标：Kototoro 液态玻璃 + 二级页面重构 + 完整 action + 修复正确性 bug。
> 仅 ARMv8，Kotlin/Compose，不集成 Root/Magisk。

## 环境关键事实

- 推送用 `/workspace/push_api.py`（token 在 `/workspace/.gh_token`），github.com 直连不可达。
- 远端 `qwerzxcva/sb-AI@master`，最近推送 `997fb39`（第 8 轮）。本轮代码**未推送**。
- 内核 `Leadaxe/sing-box-lx` v1.14.2-lx.11，`scripts/fetch-libbox.sh` 下载。
- 体积优化已生效：89MB→28MB（DEFLATE + R8 + shrinkResources）。
- CI（`.github/workflows/build.yml`）已在 runner 上成功构建（release 27.7MB + debug 45.7MB）。
- backdrop 液态玻璃：vendor `io.github.kyant0:backdrop:2.0.0` 源码进 `backdrop/` 模块（30 个 kt 文件），
  compileSdk 34 兼容 patch 完成（lens 改 no-op、context params 改显式参数、blendMode/colorFilter 改普通字段）。
  Kotlin 已回退 2.0.21（context params 已移除，无需 2.2）。compileSdk 34 本地/CI 一致（35/37 平台目录命名 `android-37.0` 非标准，AGP 解析失败，不可用）。
- 子代理 `model:"inherit"` 可用但慢；自定义模型 temperature 报错。

## 用户第 9 轮反馈 → 完成状态

| 反馈 | 状态 |
|---|---|
| 去重应该按配置不是同名 | ✅ `normalizeOutbound()` 忽略 tag/name 按配置去重 |
| lxbox JSON 格式识别 | ✅ `RouteRuleJsonCodec` 支持 route 包裹 + inline rule_set + package_name_regex + action=reject |
| 拖拽失效 | ✅ `DragDropLazyColumn` 改用 `pointerInput(items.size)`（不用被拖项 key，避免重排销毁） |
| 底栏下滑隐藏 | ✅ MainActivity nestedScroll 监听方向 + offset 动画 |
| 监控页 UI/布局 | ⏳ 已有渐变+标题，待进一步重设计 |
| 完整 action | ✅ RuleAction 7 个（ROUTE_PROXY/ROUTE_DIRECT/REJECT/SNIFF/RESOLVE/HIJACK_DNS/ROUTE_OPTIONS），生成器+UI 补全 |
| fakeIP 自定义段 | ✅ DnsServer.inet4Range/inet6Range + 编辑器字段 |
| fakeIP 联动路由规则 | ✅ 生成器自动加 fakeIP 段 route 规则 + query_type A/AAAA DNS 规则 |
| tun stack system/gvisor/mixed | ✅ AppSettings.tunStack + 设置页 segmented + 生成器 |
| 二级页面（路由/DNS 编辑） | ⏳ **路由编辑器改成 Scaffold 整页时括号错位（当前卡点）**，DNS 编辑器还没改 |
| 规则名称框大/字被挡/放置不均 | ⏳ 待修（编辑器名称行 Row 布局） |
| JSON 只能粘贴不能输入 | ⏳ 待改为 editable TextField |
| 应用选择器无 root 说明 | ⏳ 待加提示 |

## 当前卡点（必须先解决）

`app/src/main/java/com/sbai/ui/routes/RouteRulesScreen.kt` 的 RouteRuleEditorDialog：
- 我把 AlertDialog 外壳改成了 Scaffold（整页），但删弹窗 confirmButton/dismissButton 时多删了一个 `}`，
  导致 639 行附近括号错位（Scaffold body lambda 提前闭合，后面的 `if (showJsonPaste)` 变成顶层声明，TopAppBar/ArrowBack 未 import）。
- **正确结构**：Scaffold(topBar={TopAppBar(...)}) { padding -> Column(...){ ...内容... } } 之后才是 `if (showJsonPaste)` 和 `if (showAppPicker)`。
- 需要：在 Scaffold body Column 结束后（内容最后一项 `}`）加 `}` 闭合 Scaffold body，再加 `}` 闭合函数……
  实际只需让 `if (showJsonPaste)`/`if (showAppPicker)` 仍在函数体内、且在 Scaffold 之外即可。
- 补 import：`androidx.compose.material3.TopAppBar`、`androidx.compose.material.icons.automirrored.filled.ArrowBack`。

## 下一步

1. 修 RouteRulesScreen 编辑器括号 + import，编译通过。
2. DNS 编辑器同样改整页（参考路由编辑器的 Scaffold 模式）。
3. 监控页重设计（对齐 ClashFest 状态卡/流量卡/订阅卡）。
4. 规则名称框布局修复（Row weight 不均）。
5. JSON 粘贴框改为 editable。
6. 应用选择器加「无 root 时 process_name/process_path/user 不生效」提示。
7. 构建+单测+推送+审核。

## 用户历史红线

只 ARMv8；无 Root；UI 参考 Kototoro/ClashFest；液态玻璃用 backdrop；开关/LB 解耦；
network 含 icmp；protocol 全量 10 项；逻辑运算只有 AND/OR；规则集与路由规则合并显示；
长按拖动排序；路由/DNS 规则可粘贴 JSON；配置覆盖双优先级；订阅有 UA/hwid/去重（按配置）；
TUN 地址段+stack 自定义；fakeIP 自定义段+联动；体积 40MB 内（已 28MB）；二级页面而非弹窗。
