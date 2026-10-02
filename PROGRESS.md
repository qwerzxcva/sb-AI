# sb-AI 项目进度

## 项目概述
sb-AI 是一个 Android 应用，用于管理 singbox 配置，整合三个参考项目的功能。

## 已完成
1. ✅ 项目基础结构（Kotlin/Android Gradle）
2. ✅ 数据模型层：RouteRuleManager, DnsRuleManager, LoadBalanceManager
3. ✅ 主界面框架：MainActivity, SbAiApp, Navigation
4. ✅ UI 布局文件和 Fragment
5. ✅ 路由规则编辑页面（支持 network/protocol 多选、逻辑运算）
6. ✅ DNS 规则页面
7. ✅ 负载均衡规则编辑页面
8. ✅ singbox 配置文件生成器

## 进行中
- 修复 Material 库中缺少 RadioGroup 的问题（使用 android.widget.RadioGroup 替代）

## 待完成
1. 完成编译并测试
2. 实现配置导出/导入功能
3. Git 初始化并推送到 GitHub

## 当前阻塞
- Material 库 1.11.0 可能不包含 radiogroup 包中的 RadioGroup 类
- 需要改用 android.widget.RadioGroup
