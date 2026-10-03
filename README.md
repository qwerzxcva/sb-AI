# sb-AI

基于 sing-box（AndroidLibBoxLite 预编译内核）的 Android 代理客户端。
UI 风格借鉴 [AsteriskBOX](https://github.com/Asterisk4Magisk/AsteriskBOX)，
功能设计参考 [LxBox](https://github.com/Leadaxe/LxBox)、
[AsteriskBOX](https://github.com/Asterisk4Magisk/AsteriskBOX)、
[ThroneForAndroid](https://github.com/throneproj/ThroneForAndroid)。

## 特性

- **仅 ARMv8**（arm64-v8a），Kotlin + Jetpack Compose（Material 3）
- **独立路由规则页面**
  - 域名 / 域名后缀 / 关键词 / 正则 / IP-CIDR / 规则集 tag，全部**一行一条**
  - `network`（tcp/udp）与 `protocol`（http/tls/quic/dns/…）**多选**
  - 逻辑运算：**or / and / invert**
  - 规则排序、启停
- **独立 DNS 规则页面**
  - 添加 DNS（udp/tcp/tls/https/quic/h3/local/hosts/fakeip）与 DNS group，**无需指定出口**
- **DNS 联动（需求 4/5）**
  - 非拦截、非纯 IP/远程规则集的路由规则，可指定 DNS/DNS group → 自动生成 DNS 规则
  - IPv4/IPv6 勾选（默认全选）；取消任一项生成 `ip_strategy` DNS 规则
  - 两者同时命中时**只生成一条合并 DNS 规则**
- **负载均衡（参考 LxBox）**
  - 延迟优选（urltest tolerance=0）/ 均衡负载（urltest+tolerance）/ 手动切换（selector）
  - **自动模式**：在 LB 组之上自动优选，可与 LB 模式搭配
  - 可配置：测速 URL、间隔、tolerance、idle_timeout、是否中断已有连接、参与节点
- **自签签名**发布（`sbai-keystore.jks` 随仓库提交，可复现构建）
- **GitHub Actions CI**：构建时自动下载 libbox 内核并产出 arm64 APK artifact

## 构建

```bash
# 1. 下载预编译内核（不提交进 git）
bash scripts/fetch-libbox.sh

# 2. 本机构建
./gradlew :app:assembleRelease

# 3. 单元测试（配置生成器语义）
./gradlew :app:testDebugUnitTest
```

产物：`app/build/outputs/apk/release/app-arm64-v8a-release.apk`

CI：push 到 `main` 即触发 `.github/workflows/build.yml`，
自动下载 libbox、构建并上传 `sb-AI-arm64-v8a-release` artifact。

## 架构

```
app/src/main/java/com/sbai/
├── data/Models.kt                  # 路由/DNS/规则集/负载均衡数据模型（kotlinx.serialization）
├── data/RuleStore.kt               # SharedPreferences 持久化 + StateFlow
├── service/SingBoxConfigGenerator.kt  # sing-box 配置生成（DNS 联动/逻辑运算/负载均衡）
├── service/LibboxRuntime.kt        # Libbox.setup + CommandServer 生命周期
├── service/SbPlatformInterface.kt  # PlatformInterface 实现（TUN/网卡/DNS 传输）
├── service/SbAiVpnService.kt       # VPN 前台服务
└── ui/                             # Compose 页面：首页/路由/DNS/负载均衡/设置
```

## 内核

使用 [AndroidLibBoxLite](https://github.com/Asterisk4Magisk/AndroidLibBoxLite)
预编译 AAR（`v1.15.0-alpha.9-reF1nd`，已裁剪至 arm64-v8a），
由 `scripts/fetch-libbox.sh` / CI 在构建时下载。

## 许可

GPL-3.0（与上游 AsteriskBOX / sing-box 保持一致）。
PlatformInterface/Runtime 实现模式参考 AsteriskBOX（GPL-3.0），特此致谢。
