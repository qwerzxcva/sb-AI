# 首页进入卡顿复审

## 本轮已核实并修复

1. `HomeScreen` 原先在进入组合时启动 `LibboxRuntime.setup` 与 `SbCommandClient.connectWithRetry`。连接 API 内部包含同步 socket/native 调用；调用链已移到 `MainScaffold` 的应用级 effect，并固定在 `Dispatchers.IO`，返回首页不再重复建立连接。
2. 全屏 `layerBackdrop` 会捕获 NavHost 整页，首页长列表切换时会扩大绘制成本。默认关闭整页玻璃采样，底栏改用不采样的纯色背景；参数仍保留但未宣称玻璃效果在所有设备可用。
3. 节点过滤、排序和摘要生成从组合线程移到 `produceState + Dispatchers.Default`，key 变化会取消旧计算；进入计算前检查取消。最多向 UI 提供 30 行。
4. 订阅卡片原有展开按钮没有渲染子列表，点击后没有视觉结果；已移除空展开控件，节点统一从节点区过滤/分组管理。
5. 单订阅保存刷新、卡片刷新、全部刷新和批量测速均使用 `finally` 清理 busy/progress，避免异常后永久显示转圈；保存刷新也受现有刷新状态保护。
6. 应用级连接重试以 `connectedToService` 为 key；连接断开后会重新发起有限重试，且随 `MainScaffold` 生命周期取消。

## 仍需真机证据

- 当前沙箱没有无线 ADB 端口，无法抓取该次进入首页的 ANR trace、帧时间或 Perfetto。
- 不能仅凭上述静态修复宣称卡顿已根治。若安装后仍卡，下一步必须抓取 `com.sbai` 的主线程栈，而不是继续猜测。
- `RuleStore` 仍在构造时同步反序列化，但 `SbAiApp.onCreate()` 已预热它；本轮没有冒险改为异步空状态，避免用户操作期间旧配置覆盖新状态。

## 验证

- `:app:testDebugUnitTest`：147 tests，0 failures，0 errors，0 skipped。
- `:app:assembleDebug`：成功。
- `:app:assembleRelease`：成功，含 R8 与 lintVitalRelease。
- Debug SHA-256：`802083c0278bb4acce78528d3673747f276f252729840e514717de3a1948465a`。
- Release SHA-256：`8a0dd48bf8bcb2c397cd7cad3d69c59a59132597b126cd7f7651e2b64443d850`。
