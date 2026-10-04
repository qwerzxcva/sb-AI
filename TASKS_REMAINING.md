# sb-AI 待完成任务清单

## 已完成 ✅
1. 路由规则：移除 root 相关 UI 字段（进程名/路径等）
2. 监控页面：改为全白色背景
3. VPN 启动：添加调试日志
4. 测速功能：已实现 URLTest
5. 拆分隧道：已移除
6. 资源管理编辑：已实现（子代理修复）

## 待处理（按优先级）

### P0 - 核心功能
1. **VPN 启动问题诊断**
   - 用户反馈：点击启动无反应，一直显示"已停止"
   - 已添加调试日志（TAG: SbAI_VPN）
   - 需要：用户安装 Debug APK 后测试并报告 logcat

2. **订阅源编辑问题**
   - 用户反馈："无法编辑已经添加的订阅链接"
   - 可能原因：保存时出现英文错误提示
   - 需要：检查 SubscriptionManager.refresh() 的错误处理

### P1 - 用户体验
3. **节点管理重构**（用户需求明确）
   - 当前：独立节点列表 + 订阅源
   - 目标：移除独立节点列表，在订阅源内展开显示节点
   - 手动添加节点自动创建"本地订阅组"
   - 涉及文件：
     - HomeScreen.kt（节点列表 UI）
     - Models.kt（ProxyNode.subscriptionId 已存在）
     - RuleStore.kt（节点删除逻辑）

4. **设置页二级导航**
   - 当前：设置页过长（888 行）
   - 目标：改为二级页面或展开式
   - 涉及文件：SettingsScreen.kt

5. **国际化错误信息**
   - 当前：错误信息为英文
   - 目标：翻译为中文
   - 涉及文件：SubscriptionManager.kt, HomeScreen.kt

### P2 - 优化
6. **GitHub 推送**
   - 当前：网络限制导致推送失败
   - 建议：用户手动 git push

## 技术细节

### 订阅编辑错误处理
```kotlin
// 在 SubscriptionManager.refresh() 中，错误信息应该是中文
// 当前可能有英文错误如 "Subscription URL must start with http/https"
// 需要翻译或移除不必要的校验
```

### 节点管理重构方案
```kotlin
// 方案 A：在 SubscriptionCard 中添加展开/折叠
// 点击箭头图标展开显示该订阅的节点列表
// 节点列表显示：名称、延迟、启用开关、删除按钮

// 方案 B：完全移除节点列表，只在订阅详情中显示
// 订阅源点击进入详情页，详情页包含节点管理
```

## 编译状态
- `./gradlew assembleDebug`: BUILD SUCCESSFUL ✅
- `./gradlew testDebugUnitTest`: BUILD SUCCESSFUL ✅

## 下一步行动
1. 等待用户测试 VPN 启动，提供 logcat 日志
2. 继续节点管理重构（这是用户明确需求）
3. 修复订阅编辑错误提示国际化
