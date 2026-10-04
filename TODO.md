# sb-AI 待处理问题清单

## 已完成
- [x] 监控页面颜色统一为白色背景
- [x] 路由规则模型中添加注释说明 root 字段需要权限
- [x] 编译测试通过

## 待处理（按优先级排序）

### P0 - 核心功能修复
1. **VPN 启动无反应** 
   - 现象：点击启动按钮无反应，一直显示"已停止"
   - 可能原因：
     - VpnService.prepare() 返回非 null，需要用户授权
     - SbCommandClient.connect() 失败，UI 无法更新状态
     - 日志中没有看到错误信息
   - 建议：添加更多调试日志，检查权限授权流程

2. **测速功能缺失**
   - 现象：没有测速功能
   - 实现：已添加 URLTest 接口调用，但 UI 显示可能需要完善
   - 文件：SbCommandClient.kt 已有 urlTest() 方法

### P1 - 用户界面优化
3. **设置页过长**
   - 建议：改为二级页面或展开式折叠菜单
   - 涉及文件：SettingsScreen.kt (844 行)

4. **资源管理按钮无响应**
   - 现象：编辑按钮点击无反应
   - 建议：检查 onClick lambda 是否正确绑定

5. **订阅源编辑问题**
   - 现象：无法编辑已添加的订阅链接
   - 提示英文错误，看不懂
   - 建议：国际化翻译错误信息

6. **订阅组无法删除**
   - 建议：检查删除逻辑是否正确

### P2 - 功能重构
7. **拆分隧道功能**
   - 用户反馈：写规则不好吗？
   - 建议：移除或隐藏该功能

8. **单独节点无用**
   - 用户反馈：不如删了，保留订阅源
   - 建议：重构节点管理，在订阅源内展开节点列表
   - 手动添加节点自动创建"本地订阅组"

### P3 - 代码清理
9. **移除 root 相关 UI 字段**
   - 已标记但需从编辑器中移除显示
   - 文件：RouteRulesScreen.kt

## 技术细节

### VPN 启动调试建议
```kotlin
// 在 HomeScreen.kt 的 startVpn() 中添加日志
private fun startVpn(context: Context) {
    Log.i(TAG, "startVpn called")
    val intent = Intent(context, SbAiVpnService::class.java).setAction(SbAiVpnService.ACTION_START)
    ContextCompat.startForegroundService(context, intent)
    Log.i(TAG, "startVpn completed")
}
```

### 订阅错误信息国际化
当前错误信息是英文，需要翻译为中文：
- "订阅地址必须是 http/https" → 已有
- 其他英文错误需要添加对应中文

## 编译验证
- assembleDebug: ✅ BUILD SUCCESSFUL
- testDebugUnitTest: ✅ BUILD SUCCESSFUL

## 下一步行动
1. 优先修复 VPN 启动问题（添加调试日志）
2. 完善测速功能 UI 显示
3. 重构设置页面为二级导航
4. 移除拆分隧道和单独节点功能
5. 修复资源管理和订阅编辑的 bug
