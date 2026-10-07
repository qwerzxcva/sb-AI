# sb-AI 11 项审查与修复记录

基线：`d151f34`，继承 `5b94c1d` 远端工作，不 reset/覆盖其他贡献。
本记录是 11 个审查切面及集成验收，不等于 11 次真机性能测试。

|轮次|范围|结果|
|---|---|---|
|1|性能基线|连接 UI 快照在创建 Kotlin 对象时限制为 300 项，而不是构造完整列表后截断；不声称限制了 native 活动连接数量。|
|2|错误与取消|订阅更新和 Worker 显式传播取消异常，不把取消当普通失败。|
|3|资源生命周期|广播只入队，资源网络移至 IO Worker；finally disconnect；两种更新 unique KEEP 去重。|
|4|数据完整性|设置页低频操作发生时获取完整快照，后台 JSON/IO；备份保留 TLS 分片字段，导入重复 ID 去重；加载快照不删除当前快照目录。|
|5|布局|窄屏减少外边距，底栏与独立 VPN 同排等高 56dp；宽屏 80dp；图标保留 Tab 语义，每项至少 48dp，极窄窗口允许滚动。|
|6|并发|VPN 单实例启动/停止清理串行化，停止使启动结果失效，销毁异步清理；平台显式关闭回调/TUN；通过验证后退出自动禁用循环。|
|7|结构|提取 bounded text Reader；统一后台调度器；首页配置预览移至 Default，不再在 derivedStateOf 读取阶段生成。|
|8|安全|删除订阅无条件 trust-all 和异常自动降级；资源使用系统 TLS；资源文本限 4M UTF-16 字符；订阅失败日志不含原始 URL；广播要求 DUMP 权限，保留 shell/ADB 与同 UID 入口，阻挡普通第三方应用。|
|9|测试|新增 11 项：有界读取 6 项，备份去重/TLS 3 项，快照目录保留/删除后拒绝恢复 2 项。|
|10|独立复审|独立审阅发现快照目录覆盖缺陷，已修复并补测试；完整构建与 XML 统计见下。|
|11|交付与 CI|Build 工作流保留测试门禁并上传测试结果；Release 工作流新增测试门禁及报告。凭据未配置，未推送、未触发/核验 GitHub CI。|

## 实际验证

- `./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleRelease`：BUILD SUCCESSFUL。
- 12 个 TEST XML：147 tests，0 failures，0 errors，0 skipped。
- Release 构建包含 lintVitalRelease 与 R8；未声称完整 Android lint 全绿。
- APK ABI：arm64-v8a；apksigner JAR 验证两 APK 均 Verifies（v2）。
- Release：29,515,849 字节；SHA-256 `41c01e4687a17c06ac2afecd514554f96bbdd05f23b40879b03cc9a1a4c8eeda`。
- Debug：49,952,008 字节；SHA-256 `d314205a9d9ddca59dfa3696cefe2a8276ca6476f9b2adbd929893e001caa8d6`。
- Gradle 9 兼容性及部分 Android API deprecated 警告仍存在。

## 未闭环的风险（必须如实保留）

1. 未安装/运行 APK，未取得 ANR trace、帧时间或 profiler；不能宣称回首页卡顿已根治。
2. RuleStore 构造同步 load、关键更新同步 JSON/commit、SharedPreferences 跨进程缓存与写入排序仍需专门迁移设计和测试；本轮没有冒险改存储格式。
3. VPN Mutex 为单 Service 实例；旧实例清理与新实例重建交叠仍需设备回归。native start/stop 若无限阻塞，不能靠协程强制中断。
4. Tile 静态状态跨进程、CommandClient 旧连接回调污染、DNS callback 取消竞态是后续核实项，不能算已修复。
5. 首页节点区域仍有非懒组合的分组结构；玻璃效果仍在。是否为主要热点须测量，不再以源码猜测结论。
6. TLS 收紧后，证书异常订阅会正确失败；仅用户明确选择订阅级 skipCertVerify 才能绕过。资源没有绕过选项。
7. DUMP 权限的 ADB/同 UID 行为、TalkBack、字体缩放/窄屏、后台更新取消、启动中撤销 VPN 权限必须设备测试。
8. Git 凭据查询明确返回未配置。需要在 Git 面板配置 github.com 用户名和有仓库写权限的 Token；不要在聊天中发送 Token。恢复后先 fetch/review，非快进时合并协作提交，不强推。
