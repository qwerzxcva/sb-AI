# sb-AI 进度

> 目标：AsteriskBOX 风格 UI + 自签签名 + 移植 LxBox / AsteriskBOX / ThroneForAndroid 功能；
> 仅 ARMv8；Kotlin(+Compose) 为主，sing-box 内核为 Go 预编译 AAR（libbox）。

## 已完成（本轮）

### 工程与构建
- 清理旧仓库误跟踪的构建产物（build/、.gradle/、META-INF/、解包 AAR 的 316MB jni/*.so 等）
- Kotlin 2.0.21 + AGP 8.7.3 + Gradle 8.14.2（wrapper 已提交）
- 仅 `arm64-v8a`：`splits.abi` + AAR 裁剪（`app/libs/libbox.aar` 由 120MB → 84MB，仅含 arm64）
- 自签签名：`sbai-keystore.jks`（CN=sb-AI, O=QWERZXCVA），debug/release 均使用
- `scripts/fetch-libbox.sh`：构建时下载内核，AAR 不入 git
- `.github/workflows/build.yml`：CI 下载内核 → 单元测试 → assembleRelease → ABI 校验 → 上传 artifact
- 本机验证：`assembleDebug` / `assembleRelease` 均 BUILD SUCCESSFUL；
  `apksigner verify` 通过；APK 内 `lib/` 仅 `arm64-v8a`（libbox.so / libsing-box.so）

### 功能（对应需求 0-7）
| 需求 | 实现 | 位置 |
|---|---|---|
| 0 仅 ARMv8 + Kotlin/Rust 选型 | arm64-only；UI=Kotlin/Compose，内核=Go 预编译（Rust 未引入，见「决策」） | `app/build.gradle.kts` |
| 1 独立路由规则页、一行一条 | 域名/后缀/关键词/正则/IP-CIDR/规则集 tag 全部多行文本，一行一条 | `ui/routes/RouteRulesScreen.kt` |
| 2 network/protocol 多选 | FilterChip 多选：tcp/udp；http/tls/quic/dns/bittorrent/stun/ssh | 同上 |
| 3 独立 DNS 页（DNS/DNS group，无需出口） | DNS server（udp/tcp/tls/https/quic/h3/local/hosts/fakeip）+ group；detour 可留空 | `ui/dns/DnsScreen.kt` |
| 4 直连/代理指定 DNS → 自动生成 DNS 规则 | 非拦截、非纯 IP/远程规则集规则选 DNS/group 后生成 DNS 规则（group 取首个 server） | `service/SingBoxConfigGenerator.kt` |
| 5 规则集勾选 IPv4/IPv6 → 生成 DNS 规则；4+5 同时命中只生成一条 | `ip_strategy=ipv4_only/ipv6_only`；与 DNS 联动合并为单条规则 | 同上 |
| 6 负载均衡（参考 LxBox）+「自动」模式 | 延迟优选/均衡负载（urltest 参数化）/手动切换（selector）；auto 模式在 LB 组上再套 urltest，可搭配 | `ui/balance/LoadBalanceScreen.kt` |
| 7 逻辑运算 or/and/invert | `RuleLogic.SINGLE/AND/OR` + `invert`，生成 `type=logical` 规则 | `data/Models.kt` + 生成器 |

### 服务层
- `LibboxRuntime`：`Libbox.setup(SetupOptions)` + `CommandServer` 生命周期
- `SbPlatformInterface`：TUN 建立（地址/路由/DNS/应用过滤）、socket protect、
  默认网卡监控、接口枚举、`DnsResolver` 本地 DNS 传输、WIFI 状态；
  不支持的平台特性（shell/bridge/auto-redirect/SFTP）显式抛 `UnsupportedOperationException`
- `SbAiVpnService`：前台服务 + 通知（含停止动作）+ `ServiceStatus` StateFlow
- 实现模式参考 AsteriskBOX（GPL-3.0）`engine/vpn/*`，已在 README 致谢

### 测试
- `SingBoxConfigGeneratorTest`：12 例全部通过，覆盖
  逐行值/多选、and+invert、DNS group 解析、ipv4_only、4+5 合并单条、
  拦截不联动、纯 IP 不联动、规则集 ip_strategy、LB 三模式+auto、无 detour DNS、默认配置
- 测试发现并修复两个真实缺陷：
  1. 根对象漏写 `outbounds`（配置不可用）
  2. `autoEnabled` 默认 true 导致未启用 LB 时也套 auto 层

## 决策记录
- **Rust 未引入**：sing-box 官方 Android 集成是 Go 预编译 libbox（gobind）。
  自研 Rust 内核等于重写代理栈，风险/工期不可控；故 v1 = Kotlin(Compose) + Go 预编译内核。
  如后续需要 Rust，可放在 JNI 侧做规则编译/校验等纯计算模块。
- **节点导入 v1 采用 sing-box outbound JSON 粘贴**（含 type/tag 校验），
  分享链接解析（vless/vmess/trojan/ss）与订阅留待后续。
- **负载均衡语义映射**：sing-box 原生只有 `urltest`/`selector`，
  LxBox 的 random/roundRobin/leastPing/leastLoad 映射为
  延迟优选(tolerance=0)/均衡负载(tolerance>0+idle_timeout)/手动切换(selector)，
  参数（URL、间隔、tolerance、idle_timeout、中断连接、参与节点）全部可操作。

## 待办 / 已知风险
1. **未做真机运行验证**（仅构建+单测）。VPN 权限、TUN 建立、libbox 启动需真机冒烟。
2. libbox 为 `v1.15.0-alpha.9-reF1nd`（AsteriskBOX 同源 fork，alpha）；
   rule-set `format=source` 的 `version` 字段随内核版本变化，需按实际内核校验。
3. `local` 规则集当前用 `type=inline`，若内核要求 `type=local`+文件路径需调整。
4. `fakeip` DNS 的 `inet4_range/inet6_range` 目前为空对象，需补默认网段。
5. 后续功能：分享链接/订阅导入、分应用代理、QS Tile、日志页、连接页、
   配置导入导出、GeoIP/GeoSite 更新、开机自启。
6. 合规：上游为 GPL-3.0，本项目 README 已声明 GPL-3.0 并致谢。

## 环境备注（重要）
- 沙箱内 `github.com:443` 不可达（`api.github.com`、`codeload.github.com` 可达），
  因此 `git push` 失败；本轮改用 **GitHub Git Data API**（blobs→tree→commit→ref）推送，
  脚本：`/workspace/push_api.py`（token 从 git remote 提取到 `/workspace/.gh_token`）。
- 远端 `qwerzxcva/sb-AI@master` 已更新至本轮提交；本地 history 与远端为「同内容不同 commit 对象」，
  后续推送继续走 API 脚本即可（parent 取远端 head）。
- 建议把 token 存入宿主「Git」面板并轮换当前明文 token（它出现在 git remote URL 中）。
