# sb-AI 重构计划

## 用户反馈的核心问题

### 1. VPN 启动无反应
- **现象**：点击启动按钮后，状态一直显示"已停止"
- **已添加调试日志**：TAG "SbAI_VPN"
- **需要**：用户真机测试并报告 logcat 输出

### 2. 节点管理重构（用户明确需求）
- **当前**：独立节点列表 + 订阅源
- **目标**：
  - 移除独立节点列表分组
  - 在每个订阅源卡片中添加展开/折叠按钮
  - 展开后显示该订阅的节点列表
  - 手动添加的节点自动归入"本地订阅组"
- **状态**：规划中，待实现

### 3. 设置页过长
- **当前**：888 行，单个页面
- **目标**：二级导航或展开式折叠菜单
- **状态**：部分清理，待重构

### 4. 订阅源编辑/删除问题
- **用户反馈**："无法编辑已经添加的订阅链接"
- **可能原因**：
  - 保存时出现错误提示（英文）
  - 删除按钮逻辑问题
- **已检查**：
  - 编辑对话框已实现（SubscriptionEditorDialog）
  - 错误信息已翻译为中文
  - 删除逻辑正常（store.deleteSubscription()）
- **需要**：用户具体报错内容

## 技术细节

### lxbox 内核接口差异
用户提到："使用 lxbox 的 singbox 内核时，需要把接口重新转换到对应的 sb-AI 上，因为 lxbox 的内核是修改过的，不是原生 singbox 内核"

**当前发现**：
- libbox.aar 版本：`v1.14.2-lx.11`（Leadaxe/sing-box-lx fork）
- Java 包名：`io.nekohasekai.libbox`
- 命令列表：
  - `CommandStatus` ✅ 已注册
  - `CommandGroup` ✅ 已注册
  - `CommandLog` ✅ 已注册
  - `CommandConnections` ✅ 已注册
  - `CommandOutbounds` ❌ 未注册（已修复）
  - `CommandDNS` ❌ 未注册（已修复）
  - `CommandClashMode` ❌ 未注册（可能需要？）

**已修复**：
- 在 `SbCommandClient.connect()` 中添加 `CommandOutbounds` 和 `CommandDNS` 注册

### 节点数据模型
```kotlin
data class ProxyNode(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val enabled: Boolean = true,
    val subscriptionId: String = "",  // 所属订阅源 ID
    val outboundJson: String = "",    // sing-box outbound JSON
    val urlTestDelay: Int = 0,
    val urlTestTime: Long = 0L,
)
```

**重构方案**：
1. 在 `SubscriptionCard` 中添加展开/折叠按钮
2. 展开后显示该订阅的节点列表（过滤 `subscriptionId == sub.id`）
3. 手动添加的节点设置 `subscriptionId = "local"`（本地订阅组）
4. 移除独立的节点列表分组

## 下一步行动

### 立即行动
1. 用户安装 Debug APK 测试 VPN 启动
2. 查看 logcat 日志：`adb logcat -s SbAI_VPN`
3. 报告具体问题或错误信息

### 开发任务（按优先级）
1. **节点管理重构**（用户明确需求，P1）
2. **VPN 启动问题诊断**（基于日志）
3. **设置页二级导航**
4. **订阅源编辑问题修复**（需要用户反馈）

## 编译状态
- `assembleDebug`: BUILD SUCCESSFUL ✅
- `testDebugUnitTest`: BUILD SUCCESSFUL ✅

## 下载链接
- Release APK (28MB): https://github.com/qwerzxcva/sb-AI/releases/download/v1.0.0/sb-AI-arm64-v8a-release.apk
- Debug APK (47MB): https://github.com/qwerzxcva/sb-AI/releases/download/v1.0.0/sb-AI-arm64-v8a-debug.apk

---
*计划生成时间：2026-10-04*
*下次迭代目标：完成节点管理重构，诊断 VPN 启动问题*
