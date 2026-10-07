# sb-AI Master 合并指引（给负责合并的 AI）

## 任务
把 `review-fix` 分支合并到 `master`，最终只保留一条 `master` 线。

## 三个源头的定位
| 分支 | HEAD SHA | 主要内容 |
|------|---------|---------|
| `master` | `250b01a` | **我的第十四批**：订阅 UA→`clash-verge/v1.7.3` 修 403、订阅卡片展开内联节点(on/off+测速)、删首页右上角按钮、玻璃质感 blur8+降采样2x |
| `fix/subscriptions-vpn-integration` | `cace842` | **另一个AI的10提交**：VPN 生命周期 Mutex 重构、`VpnRuntimeState` 跨进程真源、base64 严格解码、NodeDedup 跨订阅合并、`StateFlow` 字段级订阅优化、175 测试 |
| `review-fix` | `ee55c61` | **我审核 fix 分支后的修复**：df80f09（修复17个 lint error）+ ee55c61（修复4个 P1/P0 bug：A订阅删除/B空URL保存/C资源展开/D错误跨进程） |

**推荐合并顺序**：
```bash
git checkout master
git merge fix/subscriptions-vpn-integration --no-ff -m "merge: fix分支"   # 先合另一AI的
git merge review-fix --no-ff -m "merge: review-fix (审核+bug修复)"      # 再合我修过的
```
或直接 `git merge review-fix`（它基于 fix 分支，一个 merge 即完成）。

## 预期冲突文件
演练时发现 **2 个文件有冲突**（其他 39 个文件自动合并无冲突）：

### 1. `app/src/main/java/com/sbai/ui/components/SbGlassBottomBar.kt`（~1处）
- **master**：blur 8px + BackdropResolutionScale=2x（第十四批质感回调）
- **review-fix**（fix 分支）：不同的液态玻璃实现风格
- **解法建议**：**保留 fix 分支的实现**（更完整的 Glass 组件重构），但确认 blur radius 不低于 6px（否则用户反馈「质感没了」）。若 fix 分支的 blur < 6px，手动改为 6-8px。

### 2. `app/src/main/java/com/sbai/ui/home/HomeScreen.kt`（~12处，主战场）
冲突本质：**两 AI 都对 HomeScreen 做了「订阅节点合并到订阅卡片」的重构，但实现方式不同**。

| 区域 | master (我) 的做法 | review-fix (fix+我的审核) 的做法 |
|------|-------------------|-------------------------------|
| 订阅卡片展开 | 内联 NodeRow 列表 | `SubscriptionCard` 自带展开 + 内联节点 |
| 节点 on/off | 订阅卡片右侧 Switch | 订阅卡片 + 每个节点行都有 Switch |
| 测速 | 单节点 Bolt 按钮 + 批量测速 | 单节点 Bolt 按钮（NodeBatchTester 改走内核组 URLTest） |
| VPN 启停真源 | `connectedToService` | `VpnRuntimeState.phase`（跨进程 SharedPreferences） |
| 底部间距 | `Spacer(120.dp)` 独立 item | 内置在 Group 内 |
| 字段级订阅 | 无（全量 collectAsState） | 有（`map+distinctUntilChanged`，修复全局卡顿放大器） |

**解法建议**：
- **保留 review-fix 的框架**（VPN 真源用 VpnRuntimeState、字段级订阅优化是更重要的性能修复）
- **补充 master 的特色**：订阅卡片的 on/off Switch 样式 + 独立批量测速按钮（review-fix 可能缺）
- **删除冲突标记后的重复实现**：两边都有的功能只保留一份

### 冲突区域速查（冲突行号，基于 master 的 HomeScreen.kt）
```
300-316  : VPN 启停真源（connectedToService vs VpnRuntimeState.phase）
403-407  : 订阅展开状态管理（expandedSubs）
644-653  : 订阅卡片渲染（SubscriptionCard 调用点）
676-718  : 节点卡片合并到订阅卡片（我的第十四批实现）
831-897  : 独立节点区（WARP/手动添加/剪贴板导入）
1195-1441: SubscriptionCard 组件定义（两边实现不同）
```
**建议合并策略**：以 `review-fix` 的实现为基底，把 master 特有的 UI 微调（如 on/off 文字标签样式、独立批量测速按钮的位置）补进去。

## 合并后必须执行的验证（缺一不可）
```bash
# 1. 编译
JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 ANDROID_HOME=$HOME/android-sdk ./gradlew :app:compileDebugKotlin

# 2. 测试（必须 175+ 全绿；若丢失某个测试类需补回）
./gradlew :app:testDebugUnitTest

# 3. Lint（必须 0 errors；warnings 24 个可接受：10个IconLauncherShape + 3 UnusedResources + 
#         2 BadHostnameVerifier + 2 IconDipSize + 1 LeanbackUsesWifi + 1 ChromeOsAbiSupport + 
#         1 CustomX509TrustManager + 1 ObsoleteSdkInt + 1 IconDuplicates）
./gradlew :app:lintDebug

# 4. assembleDebug（端到端打包验证）
./gradlew :app:assembleDebug

# 5. 检查无遗留冲突标记
grep -rn '<<<<<<<' app/src/main/  # 必须为空
grep -rn '=======' app/src/main/  # 必须为空
grep -rn '>>>>>>>' app/src/main/  # 必须为空
```

## 质量红线（不可妥协）
1. **Lint errors = 0**：原 fix 分支 17 errors 已被我修复，合并后不能引入新 error
2. **测试数 ≥ 175**：原 fix 分支有 175 测试（+32 新测），master 有 143 测试，合并后不应少于 175
3. **VPN 启停必须真实生效**：`VpnRuntimeState` 跨进程状态是真源，不能退回 `connectedToService` 冒充
4. **订阅 UA 必须是 `clash-verge/v1.7.3`**（master 已改，合并不能丢）
5. **玻璃质感 blur radius ≥ 6px**（用户明确反馈低于 6px 质感明显丢失）

## 不合并的文件
以下文件在两个分支都不涉及，直接保留 master 版本即可：
- `.github/workflows/*.yml`
- `docs/HOME_PERFORMANCE_REVIEW.md`
- `PROGRESS.md`, `FINAL_REPORT.md` 等文档

## 推送
合并完成后：
```bash
git push origin master   # 需要 token + HTTP/1.1（GitHub TLS 阻断匿名请求）
# 若推送失败重试 6 次 × timeout 120s + sleep 8s
for i in 1 2 3 4 5 6; do
  timeout 120 git -c http.version=HTTP/1.1 push origin master && break
  sleep 8
done
```