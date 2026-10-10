package com.sbai.ui.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.sbai.ui.components.BottomBarController
import com.sbai.data.ProxyNode
import com.sbai.data.RuleStore
import com.sbai.data.Subscription
import com.sbai.service.SbAiVpnService
import com.sbai.service.SbCommandClient
import com.sbai.service.VpnRuntimeState
import com.sbai.ui.monitor.formatUptime
import com.sbai.ui.monitor.toStatus
import com.sbai.service.NodeBatchTester
import com.sbai.service.SingBoxConfigGenerator
import com.sbai.service.SubscriptionManager
import com.sbai.ui.components.SbBadge
import com.sbai.ui.components.RestoreBottomBarOnDispose
import com.sbai.ui.components.SbGroup
import com.sbai.ui.components.SbItem
import com.sbai.ui.components.SbSpacer
import com.sbai.ui.components.SbSwitchItem
import com.sbai.ui.theme.LocalSbStyleTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    // 关键性能优化：这里绝不能全量订阅 AppState（`state by store.state.collectAsState()`）。
    // 那会导致任何节点/设置的任何字段变化都重组整个首页（含全部 SbGroup 与节点列表），
    // 是「回首页卡顿」的另一半根因（另一半是节点 JSON 解析，已由上一批修复）。
    // 首页只用到 3 个字段，改为字段级订阅 + distinctUntilChanged，只有相关字段真变化才重组。
    val loadBalance by remember(store) {
        store.state.map { it.loadBalance }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.loadBalance })
    val subscriptions by remember(store) {
        store.state.map { it.subscriptions }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.subscriptions })
    val proxyNodes by remember(store) {
        store.state.map { it.proxyNodes }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.proxyNodes })
    // 系统模式（全局/规则）：字段级订阅，避免全量 AppState 订阅导致整页重组。
    val globalMode by remember(store) {
        store.state.map { it.settings.globalMode }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.settings.globalMode })
    // 全局出口选择（直连/自动/节点）：本地先记，内核回写后以内核为准。
    val selectedOutbound by remember(store) {
        store.state.map { it.selectedOutboundTag }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.selectedOutboundTag })

    val status by SbAiVpnService.status.collectAsState()
    val proxyGroups by SbCommandClient.groups.collectAsState()
    val coreConnected by SbCommandClient.connectedToService.collectAsState()
    val scope = rememberCoroutineScope()
    val tokens = LocalSbStyleTokens.current

    val subManager = remember { SubscriptionManager(store, context.applicationContext) }
    var refreshingId by remember { mutableStateOf<String?>(null) }
    var testFeedback by remember { mutableStateOf<String?>(null) }

    var editingNode by remember { mutableStateOf<ProxyNode?>(null) }
    var editingSub by remember { mutableStateOf<Subscription?>(null) }
    // 订阅删除确认（含无 URL 的本地订阅，如 WARP）
    var subToDelete by remember { mutableStateOf<Subscription?>(null) }
    var importResult by remember { mutableStateOf<String?>(null) }
    // 批量测速进度：-1 = 未在测；0..100 = 已完成百分比
    var batchTestProgress by remember { mutableStateOf(-1) }
    // 正在单节点测速的节点 id（显示转圈）
    var testingNodeId by remember { mutableStateOf<String?>(null) }
    // WARP 注册对话框
    var showWarpDialog by remember { mutableStateOf(false) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        android.util.Log.i("SbAI_VPN", "vpnPermissionLauncher callback: resultCode=${result.resultCode}")
        if (result.resultCode == Activity.RESULT_OK) {
            android.util.Log.i("SbAI_VPN", "VPN permission granted, starting service")
            startVpn(context)
            // 无论 :core 是否已有实例，都重连一次，让 UI 状态跟上
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                SbCommandClient.connectWithRetry()
            }
        } else {
            android.util.Log.w("SbAI_VPN", "VPN permission denied or cancelled")
        }
    }

    // 点击启动/停止的统一入口。
    // 关键：:core 进程的 SbAiVpnService._status 是进程内 StateFlow，不跨进程共享，
    // UI 进程读到的恒为 Stopped。因此这里不能依赖 status 判断，
    // 必须以 CommandClient 是否连上 CommandServer（coreConnected）为真源。
    fun toggleVpn() {
        android.util.Log.i("SbAI_VPN", "toggleVpn: coreConnected=$coreConnected, status=$status")
        if (coreConnected) {
            // 已连上内核 → 停止
            android.util.Log.i("SbAI_VPN", "stopping VPN")
            stopVpn(context)
            SbCommandClient.disconnect()
            return
        }
        // 未连上 → 启动
        val intent = VpnService.prepare(context)
        android.util.Log.i("SbAI_VPN", "VpnService.prepare: ${if (intent == null) "null (authorized)" else "need permission"}")
        if (intent != null) {
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpn(context)
            // 启动后重连，让 UI 状态跟上 :core 进程
            scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                SbCommandClient.connectWithRetry()
            }
        }
    }

    // 全局出口选择：先写本地（卡片立刻变色），VPN 运行时再通知内核。
    fun selectGlobalOutbound(tag: String) {
        store.updateSelectedOutbound(tag)
        if (coreConnected) {
            SbCommandClient.selectOutbound("proxy", tag)
            testFeedback = when (tag) {
                "auto" -> "已切到自动"
                "direct" -> "已切到直连"
                else -> "已切到 $tag"
            }
        } else {
            testFeedback = "已记下「${if (tag == "auto") "自动" else if (tag == "direct") "直连" else tag}」，启动 VPN 后生效"
        }
    }

    // 单节点测速：触发内核 URLTest 后回填该节点延迟。
    // 需要 VPN 已运行（CommandServer 在线），否则提示先启动。
    fun testSingleNode(node: ProxyNode) {
        if (!coreConnected) {
            testFeedback = "请先启动 VPN 再测速"
            return
        }
        if (testingNodeId != null) return
        testingNodeId = node.id
        testFeedback = "「${node.name}」测速中…"
        scope.launch {
            runCatching {
                val groupTag = proxyGroups
                    .firstOrNull { it.type == "urltest" }?.tag
                    ?: proxyGroups.firstOrNull { it.type == "selector" }?.tag
                    ?: "lb"
                val delay = SbCommandClient.urlTestOutbound(
                    groupTag,
                    com.sbai.service.SingBoxConfigGenerator.nodeTagOf(node),
                    timeoutMs = 5000,
                )
                if (delay != null && delay > 0) {
                    store.updateCommitted { s ->
                        s.copy(proxyNodes = s.proxyNodes.map { n ->
                            if (n.id == node.id) n.copy(urlTestDelay = delay, urlTestTime = System.currentTimeMillis()) else n
                        })
                    }
                    testFeedback = "「${node.name}」延迟 ${delay}ms"
                } else {
                    testFeedback = "「${node.name}」测速失败（无响应或超时）"
                }
            }.onFailure {
                testFeedback = "「${node.name}」测速出错：${it.message}"
            }
            testingNodeId = null
        }
    }

    // 节点过滤状态（sb-AI NodeListFilter 基准）
    var nodeFilterQuery by remember { mutableStateOf("") }
    var nodeFilterProtocol by remember { mutableStateOf("") }
    var nodeFilterRegion by remember { mutableStateOf("") }
    var nodeFilterNoDelay by remember { mutableStateOf(false) }
    var nodeSortMode by remember { mutableStateOf<NodeSortMode>(NodeSortMode.NAME_ASC) }
    // 订阅展开状态（节点内嵌在订阅卡片中）
    var expandedSubscriptionIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val lb = loadBalance

    // 节点过滤结果缓存：避免在 LazyListScope 里每次重组都重新解析节点 JSON。
    // 订阅 id → 订阅名（纯内存映射，不订阅完整 AppState）
    val subscriptionNames = remember(subscriptions) {
        subscriptions.associate { it.id to it.name.ifBlank { "未命名订阅" } }
    }
    // 过滤和排序可能遍历大量订阅节点；放到 Default，避免返回首页时占用主线程。
    val filteredNodes by produceState<List<NodeRow>>(
        initialValue = emptyList(),
        proxyNodes,
        nodeFilterQuery,
        nodeFilterProtocol,
        nodeFilterRegion,
        nodeFilterNoDelay,
        nodeSortMode,
    ) {
        val q = nodeFilterQuery.trim()
        val proto = nodeFilterProtocol.trim()
        val region = nodeFilterRegion.trim()
        val noDelay = nodeFilterNoDelay
        val sortMode = nodeSortMode
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
            kotlinx.coroutines.currentCoroutineContext().ensureActive()
            proxyNodes.asSequence()
                .filter { node ->
                    (q.isEmpty() || node.name.contains(q, ignoreCase = true)) &&
                        (proto.isEmpty() || node.outboundJson.contains(proto, ignoreCase = true)) &&
                        (region.isEmpty() || node.name.contains(region, ignoreCase = true)) &&
                        (!noDelay || node.urlTestDelay > 0)
                }
                .sortedWith(
                    if (sortMode == NodeSortMode.LATENCY_ASC) {
                        compareBy<ProxyNode> { it.urlTestDelay.takeIf { d -> d > 0 } ?: Int.MAX_VALUE }
                            .thenBy { it.name.lowercase() }
                    } else compareBy { it.name.lowercase() },
                )
                // 按订阅分别限制预览，避免全局截断使后面的订阅显示为空。
                .groupBy { it.subscriptionId }
                .values.asSequence()
                .flatMap { group -> group.asSequence().take(30) }
                .map { node ->
                    val summary = node.outboundJson.nodeSummary()
                    val delayText = node.urlTestDelay.takeIf { it > 0 }?.let { "${it}ms" }
                    NodeRow(node, buildString {
                        append(summary)
                        delayText?.let { append(" · ").append(it) }
                        when {
                            node.disabledReason != null -> append(" · 已自动禁用（${node.disabledReason}）")
                            !node.enabled -> append(" · 已禁用")
                        }
                    })
                }
                .toList()
        }
    }
    // 独立节点（非订阅来源）：节点卡片已并入订阅卡片，此组只列手动/剪贴板导入的节点
    val filteredStandaloneNodes by remember(filteredNodes) {
        derivedStateOf { filteredNodes.filter { it.node.subscriptionId == null } }
    }
    val standaloneNodes = proxyNodes.count { it.subscriptionId == null }
    // :core 进程启动错误已由 VpnRuntimeState.message 跨进程刷新（每 2s），
    // 旧「读一次 SharedPreferences」的 persistedError 已废弃（只读一次，服务启动后崩溃的错误 UI 不刷新——P0 服务错误跨进程丢失 bug 已通过 VpnRuntimeState 根治）。
    // 注：VpnRuntimeState 已改用文件通道（跨进程永远最新），UI 轮询 refreshFromDisk 即可。
    // 连接在 MainScaffold 级别只启动一次；首页只消费状态，避免返回首页重复建连。
    // 状态真源：:core 进程发布的真实运行阶段（不用遥测连接冒充 VPN 状态）
    // persistedError 已从历史遗留中移除：VpnRuntimeState.message 是统一的错误展示真源，
    // 该变量读取后从未被消费（仅占一行内存），留着只会让新看代码的人误以为它是真源。
    val vpnPhase by VpnRuntimeState.phase.collectAsState()
    // VPN 错误消息订阅提升到顶层（避免在 LazyColumn item 内联 collectAsState().value）
    val vpnMessage by VpnRuntimeState.message.collectAsState()
    // 跨进程遥测快照（连接时长/出口IP/流量），由 :core 周期发布、上方轮询刷新
    val telemetry by VpnRuntimeState.telemetry.collectAsState()
    // 卡死检测：Starting/Stopping 超过 90s 视为已回退 Stopped（服务崩溃/被杀场景）
    var lastPublishedAt by remember { mutableStateOf(0L) }
    // 1s 时钟心跳：驱动 effectivePhase 的租约判断每秒重算。否则 :core 死后
    // lastPublishedAt 过期但 Compose 无新状态可订阅，页面不会自动重绘——
    // 必须切页（触发 recomposition）才刷新，正是用户报的「切回去才看到已停止」。
    var clockTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                VpnRuntimeState.refreshFromDisk(context)
                // 跨进程遥测快照（:core 发布的流量/连接/时长/出口IP），首页状态卡与监控页共用
                VpnRuntimeState.refreshTelemetry(context)
                // :core 会异步自动禁用坏节点；UI 轮询时把最新磁盘态刷入内存，
                // 否则首页的代理列表/节点数量会滞后（P0 相关体验问题）。
                RuleStore.get(context).refreshFromDisk()
                // 卡死检测需要「当前」时间戳：旧实现只在启动时读一次，
                // 90s 后任何 Starting/Stopping（含合法慢启动）都会误判为卡死、
                // 按钮被锁死或 VPN 显示状态错误（P0：点了没反应/状态错乱）。
                lastPublishedAt = VpnRuntimeState.lastPublishedAt(context)
            }
            clockTick = clockTick + 1
            delay(1000L)
        }
    }
    // 读 clockTick 以建立对该状态的订阅（值本身不直接使用）
    @Suppress("UNUSED_EXPRESSION") clockTick
    val effectivePhase = when (vpnPhase) {
        VpnRuntimeState.Phase.Starting, VpnRuntimeState.Phase.Stopping ->
            if (lastPublishedAt > 0 &&
                System.currentTimeMillis() - lastPublishedAt > VpnRuntimeState.STUCK_TIMEOUT_MS
            ) VpnRuntimeState.Phase.Stopped else vpnPhase
        // Running 过期（:core 死后心跳停续）→ 视为内核已死，不再显示运行中
        VpnRuntimeState.Phase.Running ->
            if (lastPublishedAt > 0 &&
                System.currentTimeMillis() - lastPublishedAt > VpnRuntimeState.RUNNING_LEASE_TIMEOUT_MS
            ) VpnRuntimeState.Phase.Error else vpnPhase
        else -> vpnPhase
    }
    val running = effectivePhase == VpnRuntimeState.Phase.Running ||
        effectivePhase == VpnRuntimeState.Phase.Starting
    val coreRunning = running || coreConnected

    // 节点编辑器：整页（二级页面），不是弹窗
    editingNode?.let { node ->
        NodeEditorScreen(
            initial = node,
            onBack = { editingNode = null },
            onSave = {
                store.upsertProxyNode(it)
                editingNode = null
            },
        )
        return
    }

    // 订阅编辑器：整页（二级页面），不是弹窗
    editingSub?.let { sub ->
        SubscriptionEditorDialog(
            initial = sub,
            onDismiss = { editingSub = null },
            onSave = { saved ->
                store.upsertSubscription(saved)
                editingSub = null
                // 保存后立即触发刷新，并把结果反馈给用户（否则失败只在卡片内不可见）。
                // 无 URL 的本地订阅（WARP 等）没有拉取源，跳过刷新避免必然报错。
                if (saved.url.isNotBlank() && refreshingId == null) {
                    scope.launch {
                        refreshingId = saved.id
                        try {
                            when (val result = subManager.refresh(saved)) {
                                is com.sbai.service.SubscriptionManager.Result.Success ->
                                    importResult = "订阅已保存并导入 ${result.nodeCount} 个节点"
                                is com.sbai.service.SubscriptionManager.Result.Failure ->
                                    importResult = "订阅已保存，但更新失败：${result.message}"
                            }
                        } finally {
                            refreshingId = null
                        }
                    }
                }
            },
            onDelete = {
                subToDelete = sub
                editingSub = null
            },
        )
        return
    }

    // 订阅删除确认（含无 URL 的本地订阅，如 WARP）
    subToDelete?.let { sub ->
        AlertDialog(
            onDismissRequest = { subToDelete = null },
            title = { Text("删除订阅源") },
            text = {
                val nodeCount = proxyNodes.count { it.subscriptionId == sub.id }
                Text(
                    "确定要删除「${sub.name.ifBlank { sub.id }}」吗？\n" +
                        "将同时删除其中的 ${nodeCount} 个节点。",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        store.deleteSubscription(sub.id)
                        subToDelete = null
                    },
                ) { Text("删除", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { subToDelete = null }) { Text("取消") }
            },
        )
        return
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = tokens.screenHorizontalPadding),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // ---- 顶部状态区（PiliPlus 式：居中标题 + 居中大电源键 + 模式卡片） ----
            item {
                Spacer(Modifier.height(24.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("sb-AI", style = MaterialTheme.typography.headlineLarge)
                    Text(
                        // 跨进程真源：:core 进程把运行阶段写盘，UI 进程读取；
                        // 不再用 CommandClient 连接状态冒充 VPN 状态。
                        displayVpnPhase(effectivePhase, vpnMessage) +
                            (vpnMessage?.let { " · $it" } ?: ""),
                        style = MaterialTheme.typography.bodyMedium,
                        color = when {
                            effectivePhase == VpnRuntimeState.Phase.Error -> MaterialTheme.colorScheme.error
                            running -> MaterialTheme.colorScheme.primary
                            else -> MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                    // 连接时长 + 出口 IP（跨进程遥测快照，:core 周期发布；主进程共享文件读取）
                    // 关键1：只在「phase 是 Running 且心跳未过期」时显示计时（:core 死后 telemetry
                    //   是死快照，不新鲜即隐藏，避免显示「已连接 00:25」的静态 UI）。
                    // 关键2：连续计时。uptimeSec 是「updatedAt 那一刻」的时长；用 uptimeSec +
                    //   (now - updatedAt)/1000 推算出「此刻」的真实时长，配合 1s tick 持续重组，
                    //   首页不再要切页才跳一下。数据仍来自内核（非本地瞎加）。
                    val tele = telemetry
                    val telemetryFresh = tele != null &&
                        (System.currentTimeMillis() - tele.updatedAt) <= VpnRuntimeState.RUNNING_LEASE_TIMEOUT_MS
                    if (effectivePhase == VpnRuntimeState.Phase.Running &&
                        tele != null && tele.uptimeSec > 0 && telemetryFresh
                    ) {
                        var tickSec by remember { mutableStateOf(0L) }
                        LaunchedEffect(tele.updatedAt) {
                            while (true) {
                                kotlinx.coroutines.delay(1000L)
                                tickSec = (System.currentTimeMillis() - tele.updatedAt) / 1000L
                            }
                        }
                        val liveUptime = tele.uptimeSec + tickSec.coerceAtLeast(0L)
                        Text(
                            buildString {
                                append("已连接 ${formatUptime(liveUptime)}")
                                if (tele.publicIp.isNotBlank()) append(" · 出口 ${tele.publicIp}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }

                    Spacer(Modifier.height(20.dp))

                    // ---- 系统模式卡（规则/全局，点选加深）——独占整行。 ----
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        // 系统模式卡：规则 / 全局（切换改 route.final + 是否输出路由规则，重启生效）
                        androidx.compose.material3.Surface(
                            modifier = Modifier.fillMaxWidth(),
                            shape = MaterialTheme.shapes.large,
                            color = MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Column(Modifier.padding(14.dp)) {
                                Text("系统模式", style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    FilterChip(
                                        selected = !globalMode,
                                        onClick = {
                                            store.updateSettings(store.state.value.settings.copy(globalMode = false))
                                            testFeedback = if (coreRunning) "已切换为规则模式，重启 VPN 后生效" else null
                                        },
                                        label = { Text("规则") },
                                    )
                                    FilterChip(
                                        selected = globalMode,
                                        onClick = {
                                            store.updateSettings(store.state.value.settings.copy(globalMode = true))
                                            testFeedback = if (coreRunning) "已切换为全局模式，重启 VPN 后生效" else null
                                        },
                                        label = { Text("全局") },
                                    )
                                }
                            }
                        }
                    }
                }
                SbSpacer()
            }

            // ---- 全局出口：直连 / 自动（不放进订阅源，全局一份） ----
            item {
                val liveSelected = proxyGroups.firstOrNull { it.tag == "proxy" }?.selected
                    ?.takeIf { it.isNotBlank() }
                val effectiveSelected = liveSelected ?: selectedOutbound.ifBlank { "auto" }
                SbGroup(title = "全局出口") {
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            OutboundSelectCard(
                                label = "自动",
                                subtitle = "在全部节点里选延迟最低的",
                                selected = effectiveSelected == "auto",
                                enabled = true,
                                onClick = { selectGlobalOutbound("auto") },
                            )
                            OutboundSelectCard(
                                label = "直连",
                                subtitle = "不走代理",
                                selected = effectiveSelected == "direct",
                                enabled = true,
                                onClick = { selectGlobalOutbound("direct") },
                            )
                            if (!coreConnected) {
                                Text(
                                    "VPN 未运行：选择已记下，启动后生效",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
                SbSpacer()
            }

            // ---- 实时流量卡（ClashFest 首页观感；运行中显示） ----
            if (coreRunning) {
                item {
                    HomeTrafficCard()
                    SbSpacer()
                }
            }

            // ---- 代理组（运行中：延迟/切换/测速） ----
            if (coreRunning && proxyGroups.isNotEmpty()) {
                item {
                    SbGroup(title = "代理组") {
                        proxyGroups.forEach { group ->
                            item {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(Modifier.weight(1f)) {
                                            Text(group.tag, style = MaterialTheme.typography.titleSmall)
                                            Text(
                                                "${group.type} · 当前: ${group.selected.ifBlank { "—" }}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        if (group.type == "urltest") {
                                            TextButton(onClick = { SbCommandClient.urlTest(group.tag) }) { Text("测速") }
                                        }
                                    }
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        group.items.take(30).forEach { item ->
                                            val selected = item.tag == group.selected
                                            FilterChip(
                                                selected = selected,
                                                onClick = {
                                                    if (group.selectable && !selected) {
                                                        SbCommandClient.selectOutbound(group.tag, item.tag)
                                                    }
                                                },
                                                label = {
                                                    val delay = if (item.delay > 0) "${item.delay}ms" else ""
                                                    Text(if (delay.isBlank()) item.tag else "${item.tag} · $delay")
                                                },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                    SbSpacer()
                }
            }

            // ---- 订阅源 ----
            item {
                SbGroup(title = "订阅源") {
                    // 测速/切换反馈只显示一次（在订阅列表顶部），不在每个卡片上方重复
                    if (testFeedback != null) {
                        item {
                            Text(
                                testFeedback!!,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                    subscriptions.forEach { sub ->
                        item {
                            val subNodes = filteredNodes.filter { it.node.subscriptionId == sub.id }
                            SubscriptionCard(
                                sub = sub,
                                nodes = subNodes,
                                expanded = sub.id in expandedSubscriptionIds,
                                refreshing = refreshingId == sub.id,
                                onToggleExpand = {
                                    expandedSubscriptionIds = if (sub.id in expandedSubscriptionIds) {
                                        expandedSubscriptionIds - sub.id
                                    } else expandedSubscriptionIds + sub.id
                                },
                                onRefresh = {
                                    if (refreshingId == null) {
                                        refreshingId = sub.id
                                        scope.launch {
                                            try {
                                                val result = subManager.refresh(sub)
                                                testFeedback = when (result) {
                                                    is SubscriptionManager.Result.Success ->
                                                        "${sub.name.ifBlank { "订阅" }} 更新 ${result.nodeCount} 个节点"
                                                    is SubscriptionManager.Result.Failure ->
                                                        result.message
                                                }
                                            } finally {
                                                refreshingId = null
                                            }
                                        }
                                    }
                                },
                                onToggleEnabled = { enabled ->
                                    store.upsertSubscription(sub.copy(enabled = enabled))
                                },
                                onTestNodes = {
                                    if (batchTestProgress == -1 && subNodes.isNotEmpty()) {
                                        if (!coreConnected) {
                                            testFeedback = "需先启动 VPN 才能测速"
                                        } else {
                                            testFeedback = null
                                            scope.launch {
                                                batchTestProgress = 0
                                                try {
                                                    val groupTag = proxyGroups
                                                        .firstOrNull { it.type == "urltest" }?.tag
                                                        ?: proxyGroups.firstOrNull { it.type == "selector" }?.tag
                                                        ?: "lb"
                                                    val results = NodeBatchTester.testNodes(
                                                        nodes = subNodes.map { it.node },
                                                        groupTag = groupTag,
                                                        onProgress = { done, total ->
                                                            batchTestProgress = if (total == 0) 100 else done * 100 / total
                                                        },
                                                    )
                                                    NodeBatchTester.applyResults(store, results)
                                                    testFeedback = "测速完成 ${results.size} 节点"
                                                } finally {
                                                    batchTestProgress = -1
                                                }
                                            }
                                        }
                                    }
                                },
                                onEditNode = { editingNode = it },
                                onDeleteNode = { store.deleteProxyNode(it) },
                                onToggleNode = { node, enabled ->
                                    store.upsertProxyNode(node.copy(enabled = enabled, disabledReason = null))
                                },
                                onEditSubscription = { editingSub = sub },
                                // 选中态：内核已回写用内核，否则用本地记下的选择（点了立刻变色）
                                selectedTag = proxyGroups.firstOrNull { it.tag == "proxy" }?.selected
                                    ?.takeIf { it.isNotBlank() }
                                    ?: selectedOutbound,
                                onSelectNode = { node ->
                                    selectGlobalOutbound(SingBoxConfigGenerator.nodeTagOf(node))
                                },
                            )
                        }
                    }
                    item {
                        SbItem(
                            title = "添加订阅源",
                            subtitle = "支持分享链接 / Clash YAML / sing-box JSON",
                            icon = Icons.Filled.Add,
                            onClick = { editingSub = Subscription() },
                        )
                    }
                    if (subscriptions.size > 1) {
                        item {
                            SbItem(
                                title = "全部更新",
                                subtitle = "触发所有启用订阅的拉取 + L7Filter + 测速回填",
                                icon = Icons.Filled.Refresh,
                                onClick = {
                                    if (refreshingId == null) {
                                        scope.launch {
                                            val targets = subscriptions.filter { it.enabled && it.url.isNotBlank() }
                                            if (targets.isEmpty()) {
                                                testFeedback = "没有可更新的订阅（需启用且填了地址）"
                                                return@launch
                                            }
                                            var ok = 0
                                            var failed = 0
                                            try {
                                                // 逐个刷新：每张卡片都能转圈，失败写进 lastError，最后给总数。
                                                for (sub in targets) {
                                                    refreshingId = sub.id
                                                    val result = runCatching { subManager.refresh(sub) }
                                                        .getOrElse { SubscriptionManager.Result.Failure(it.message ?: "更新失败") }
                                                    when (result) {
                                                        is SubscriptionManager.Result.Success -> ok++
                                                        is SubscriptionManager.Result.Failure -> failed++
                                                    }
                                                }
                                                testFeedback = "更新完成：成功 $ok，失败 $failed"
                                            } finally {
                                                refreshingId = null
                                            }
                                        }
                                    }
                                },
                            )
                        }
                        if (coreRunning && lb.enabled) {
                            item {
                                SbItem(
                                    title = "立即测速",
                                    subtitle = "对所有 URLTest 分组执行 urltest，结果回填到节点",
                                    icon = Icons.Filled.Bolt,
                                    onClick = {
                                        scope.launch {
                                            // 只使用当前核心实际公布的 lb 组；不以固定等待时间或旧缓存冒充新测速结果。
                                            val group = proxyGroups.firstOrNull {
                                                it.tag == "lb" && it.type in setOf("urltest", "selector")
                                            } ?: return@launch
                                            val results = NodeBatchTester.testNodes(
                                                nodes = proxyNodes,
                                                groupTag = group.tag,
                                            )
                                            NodeBatchTester.applyResults(store, results)
                                        }
                                    },
                                )
                            }
                        }
                }
                }
                SbSpacer()
            }

            // ---- 节点 ----
            // 批量测速面板：对所有启用节点逐个 URLTest，结果回填（参考 LxBox 009 列表测速）
            if (coreConnected && proxyNodes.any { it.enabled }) {
                item {
                    SbItem(
                        title = if (batchTestProgress in 0..99) "批量测速中… ${batchTestProgress}%" else "批量测速（所有节点）",
                        subtitle = "逐个节点 URLTest，并发 6，结果回填到节点延迟",
                        icon = Icons.Filled.Bolt,
                        onClick = {
                            if (batchTestProgress == -1) {
                                scope.launch {
                                    batchTestProgress = 0
                                    try {
                                        val groupTag = proxyGroups
                                            .firstOrNull { it.type == "urltest" }?.tag
                                            ?: proxyGroups.firstOrNull { it.type == "selector" }?.tag
                                            ?: "lb"
                                        val results = NodeBatchTester.testNodes(
                                            nodes = proxyNodes,
                                            groupTag = groupTag,
                                            onProgress = { done, total ->
                                                batchTestProgress = if (total == 0) 100 else done * 100 / total
                                            },
                                        )
                                        NodeBatchTester.applyResults(store, results)
                                    } finally {
                                        batchTestProgress = -1
                                    }
                                }
                            }
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                }
            }
            item {
                SbGroup(title = "本地源（${standaloneNodes}）") {
                    // 搜索过滤（订阅节点在订阅卡片内展开，这里只列手动/剪贴板导入的独立节点）
                    item {
                        Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)) {
                            OutlinedTextField(
                                value = nodeFilterQuery,
                                onValueChange = { nodeFilterQuery = it },
                                label = { Text("搜索（名称/地区）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                                trailingIcon = {
                                    if (nodeFilterQuery.isNotBlank()) {
                                        IconButton(onClick = { nodeFilterQuery = "" }) {
                                            Icon(Icons.Filled.Close, contentDescription = "清除", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                item { Spacer(Modifier.height(120.dp)) }
                                    }
                                },
                            )
                            Spacer(Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                FilterChip(
                                    selected = nodeFilterNoDelay,
                                    onClick = { nodeFilterNoDelay = !nodeFilterNoDelay },
                                    label = { Text("无延迟") },
                                )
                                SingleChoiceSegmentedButtonRow {
                                    SegmentedButton(
                                        selected = nodeSortMode == NodeSortMode.NAME_ASC,
                                        onClick = { nodeSortMode = if (nodeSortMode == NodeSortMode.NAME_ASC) NodeSortMode.LATENCY_ASC else NodeSortMode.NAME_ASC },
                                        shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                                    ) { Text("名称↑") }
                                    SegmentedButton(
                                        selected = nodeSortMode == NodeSortMode.LATENCY_ASC,
                                        onClick = { nodeSortMode = if (nodeSortMode == NodeSortMode.LATENCY_ASC) NodeSortMode.NAME_ASC else NodeSortMode.LATENCY_ASC },
                                        shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                                    ) { Text("延迟↑") }
                                }
                            }
                        }
                    }
                    // 独立节点（subscriptionId==null，即 WARP / 手动添加 / 剪贴板导入）：
                    for (row in filteredStandaloneNodes) {
                        item(key = "standalone-${row.node.id}") {
                            StandaloneNodeRow(
                                node = row.node,
                                subtitle = row.subtitle,
                                testing = testingNodeId == row.node.id,
                                onToggle = {
                                    store.upsertProxyNode(row.node.copy(enabled = !row.node.enabled, disabledReason = null))
                                },
                                onTest = { testSingleNode(row.node) },
                                onClick = { editingNode = row.node },
                                onDelete = { store.deleteProxyNode(row.node.id) },
                            )
                        }
                    }
                    item {
                        SbItem(
                            title = "获取 WARP（免费隧道）",
                            subtitle = "一键注册 Cloudflare WARP，生成 WireGuard 节点",
                            icon = Icons.Filled.CloudDownload,
                            onClick = { showWarpDialog = true },
                        )
                    }
                    item {
                        SbItem(
                            title = "手动添加节点",
                            subtitle = "粘贴 sing-box outbound JSON",
                            icon = Icons.Filled.Add,
                            onClick = { editingNode = ProxyNode() },
                        )
                    }
                    item {
                        SbItem(
                            title = "从剪贴板导入",
                            subtitle = "解析分享链接（vless/vmess/trojan/ss/hysteria2/wireguard）",
                            icon = Icons.Filled.ContentPaste,
                            onClick = {
                                val clip = clipboardText(context)
                                if (clip.isNullOrBlank()) {
                                    importResult = "剪贴板为空"
                                } else {
                                    val parsed = com.sbai.service.ShareLinkParser.parseSubscription(clip)
                                    if (parsed.isEmpty()) {
                                        importResult = "未识别到可解析的节点链接"
                                    } else {
                                        parsed.forEach { p ->
                                            store.upsertProxyNode(ProxyNode(name = p.name, outboundJson = p.outboundJson))
                                        }
                                        importResult = "已导入 ${parsed.size} 个节点"
                                    }
                                }
                            },
                        )
                    }
                    importResult?.let { msg ->
                        item {
                            SbItem(title = "导入结果", subtitle = msg)
                        }
                    }
                }
            }
        }
    }

    // ---- 对话框 ----
    if (showWarpDialog) {
        var warpBusy by remember { mutableStateOf(false) }
        var warpError by remember { mutableStateOf<String?>(null) }
        AlertDialog(
            onDismissRequest = { if (!warpBusy) showWarpDialog = false },
            title = { Text("获取 WARP 免费隧道") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "在设备上生成 WireGuard 密钥并注册 Cloudflare WARP，" +
                            "注册成功后自动添加一个免费 WireGuard 节点。私钥不会离开设备。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (warpBusy) {
                        Text("注册中…（需联网，约数秒）", color = MaterialTheme.colorScheme.primary)
                    }
                    warpError?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !warpBusy,
                    onClick = {
                        warpBusy = true
                        warpError = null
                        scope.launch(kotlinx.coroutines.Dispatchers.IO) {
                            try {
                                val account = com.sbai.service.WarpClient().register()
                                val uri = account.toWireguardUri()
                                val parsed = com.sbai.service.ShareLinkParser.parse(uri)
                                if (parsed == null) {
                                    warpError = "生成节点失败：wireguard 解析失败"
                                } else {
                                    store.upsertProxyNode(
                                        ProxyNode(name = "WARP", outboundJson = parsed.outboundJson),
                                    )
                                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        showWarpDialog = false
                                    }
                                }
                            } catch (e: Exception) {
                                warpError = "注册失败：${e.message}"
                            } finally {
                                warpBusy = false
                            }
                        }
                    },
                ) { Text("注册") }
            },
            dismissButton = {
                TextButton(enabled = !warpBusy, onClick = { showWarpDialog = false }) { Text("取消") }
            },
        )
    }

}

// ---------------------------------------------------------------------------
// 辅助
// ---------------------------------------------------------------------------

private fun statusText(status: SbAiVpnService.ServiceStatus): String = when (status) {
    is SbAiVpnService.ServiceStatus.Running -> "运行中"
    is SbAiVpnService.ServiceStatus.Starting -> "启动中…"
    is SbAiVpnService.ServiceStatus.Stopping -> "停止中…"
    is SbAiVpnService.ServiceStatus.Error -> "错误: ${status.message}"
    else -> "已停止"
}

/** :core 发布的跨进程阶段是状态来源；遥测连接不代表 VPN 正在运行。 */
private fun displayVpnPhase(phase: VpnRuntimeState.Phase, message: String?): String = when (phase) {
    VpnRuntimeState.Phase.Starting -> "启动中…"
    VpnRuntimeState.Phase.Running -> "运行中"
    VpnRuntimeState.Phase.Stopping -> "停止中…"
    VpnRuntimeState.Phase.Error -> when (message) {
        VpnRuntimeState.STALE_MESSAGE -> "状态未知，可重试"
        VpnRuntimeState.CORE_DIED_MESSAGE -> "内核已停止，可重启"
        else -> "启动失败"
    }
    VpnRuntimeState.Phase.Stopped -> "已停止"
}

private fun formatTime(epoch: Long): String =
    if (epoch <= 0) "未更新"
    else SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(epoch))

/** 订阅选项开关行 */
@Composable
private fun SubOptionSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Switch(checked = checked, onCheckedChange = onChange)
        Spacer(Modifier.size(8.dp))
        Text(label, style = MaterialTheme.typography.bodyMedium)
    }
}

// ---------------------------------------------------------------------------
// ClashFest 风格首页卡片
// ---------------------------------------------------------------------------

@Composable
private fun HomeTrafficCard() {
    // 内部独立 collect 高频流量状态，避免在 HomeScreen 顶层 collect 导致整页每 1s 重组
    val status by SbCommandClient.status.collectAsState()
    val telemetry by VpnRuntimeState.telemetry.collectAsState()
    // 跨进程：主进程 in-process CommandClient 无数据时，用 :core 发布的共享快照。
    // 但 :core 死后 telemetry 是死快照（updatedAt 停滞），必须看新鲜度，否则显示静态流量。
    val liveTelemetry = telemetry?.takeIf {
        (System.currentTimeMillis() - it.updatedAt) <= VpnRuntimeState.RUNNING_LEASE_TIMEOUT_MS
    }
    val effStatus = liveTelemetry?.toStatus() ?: status
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column {
                Text("实时流量", style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer.copy(alpha = 0.75f))
                Text(
                    "↑ ${com.sbai.ui.monitor.formatSpeed(effStatus.uplink)}   ↓ ${com.sbai.ui.monitor.formatSpeed(effStatus.downlink)}",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onPrimaryContainer,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("累计", style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer.copy(alpha = 0.75f))
                Text(
                    "↑ ${com.sbai.ui.monitor.formatBytes(effStatus.uplinkTotal)}   ↓ ${com.sbai.ui.monitor.formatBytes(effStatus.downlinkTotal)}",
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onPrimaryContainer,
                )
            }
        }
    }
}

@Composable
private fun SubscriptionCard(
    sub: Subscription,
    nodes: List<NodeRow>,
    expanded: Boolean,
    refreshing: Boolean,
    onToggleExpand: () -> Unit,
    onRefresh: () -> Unit,
    onToggleEnabled: (Boolean) -> Unit,
    onTestNodes: () -> Unit,
    onEditNode: (com.sbai.data.ProxyNode) -> Unit,
    onDeleteNode: (String) -> Unit,
    onToggleNode: (com.sbai.data.ProxyNode, Boolean) -> Unit,
    onEditSubscription: () -> Unit,
    // 当前全局选中的节点 tag（直连/自动在订阅外单独显示）
    selectedTag: String,
    onSelectNode: (com.sbai.data.ProxyNode) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val used = sub.trafficUpload + sub.trafficDownload
    val hasTraffic = sub.trafficTotal > 0
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        // 卡片主体
        Surface(
            onClick = onToggleExpand,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            color = if (sub.enabled) colors.surfaceContainer else colors.surfaceContainer.copy(alpha = 0.5f),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(sub.name.ifBlank { sub.url }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text(
                            sub.lastError?.let { "错误: $it" }
                                ?: "${nodes.size} 节点 · ${formatTime(sub.lastUpdatedAt)}",
                            style = MaterialTheme.typography.bodySmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        // 订阅启用/禁用（右侧 on/off）——master 的样式参考：on/off 文字标签 + Switch
                        Text(
                            text = if (sub.enabled) "on" else "off",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (sub.enabled) colors.primary else colors.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 2.dp),
                        )
                        Switch(
                            checked = sub.enabled,
                            onCheckedChange = onToggleEnabled,
                        )
                        IconButton(onClick = onToggleExpand) {
                            Icon(
                                imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (expanded) "收起节点" else "展开节点",
                            )
                        }
                        if (refreshing) {
                            CircularProgressIndicator(modifier = Modifier.size(22.dp), strokeWidth = 2.dp)
                        } else {
                            IconButton(onClick = onRefresh) {
                                Icon(Icons.Filled.Refresh, contentDescription = "更新")
                            }
                        }
                        // 订阅设置入口：之前只有开关/展开/刷新，没有编辑入口（用户反馈无法编辑订阅）
                        IconButton(onClick = onEditSubscription) {
                            Icon(Icons.Filled.Settings, contentDescription = "订阅设置")
                        }
                    }
                }

                if (hasTraffic) {
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(
                        progress = { (used.toFloat() / sub.trafficTotal.toFloat()).coerceIn(0f, 1f) },
                        modifier = Modifier.fillMaxWidth().height(6.dp),
                        color = if (used.toFloat() / sub.trafficTotal > 0.9f) colors.error else colors.primary,
                        trackColor = colors.surfaceContainerHigh,
                    )
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "${com.sbai.ui.monitor.formatBytes(used)} / ${com.sbai.ui.monitor.formatBytes(sub.trafficTotal)}" +
                            if (sub.trafficExpire > 0) {
                                " · 到期 " + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date(sub.trafficExpire * 1000))
                            } else "",
                        style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant,
                    )
                }
            }
        }
        
        // 展开后：节点卡片列表（点选即全局选中；直连/自动在订阅外单独显示）
        if (expanded) {
            Spacer(Modifier.height(6.dp))
            if (nodes.isEmpty()) {
                Text(
                    "该订阅暂无节点；可点击上方更新按钮拉取。",
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp),
                )
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onTestNodes) { Text("测速本订阅节点") }
                }
                nodes.forEach { row ->
                    val nodeTag = com.sbai.service.SingBoxConfigGenerator.nodeTagOf(row.node)
                    key(row.node.id) {
                        SelectableNodeRow(
                            row = row,
                            selected = selectedTag == nodeTag,
                            onSelect = { onSelectNode(row.node) },
                            onToggle = { onToggleNode(row.node, it) },
                            onDelete = { onDeleteNode(row.node.id) },
                            onEdit = { onEditNode(row.node) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SelectableNodeRow(
    row: NodeRow,
    selected: Boolean,
    onSelect: () -> Unit,
    onToggle: (Boolean) -> Unit,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        onClick = { if (row.node.enabled) onSelect() },
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
        shape = RoundedCornerShape(10.dp),
        color = when {
            selected -> colors.primaryContainer
            row.node.enabled -> colors.surfaceContainerHigh
            else -> colors.surfaceContainerHigh.copy(alpha = 0.5f)
        },
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    row.node.name.ifBlank { "未命名节点" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                )
                Text(
                    row.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) colors.onPrimaryContainer.copy(alpha = 0.75f) else colors.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已选中",
                    tint = colors.primary,
                    modifier = Modifier.size(18.dp),
                )
            }
            Switch(
                checked = row.node.enabled,
                onCheckedChange = onToggle,
            )
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, contentDescription = "删除节点")
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, contentDescription = "编辑节点")
            }
        }
    }
}

/** 全局出口选择卡片：点选选中加深。 */
@Composable
private fun OutboundSelectCard(
    label: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    androidx.compose.material3.Surface(
        onClick = { if (enabled) onClick() },
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        color = when {
            selected -> colors.primaryContainer
            enabled -> colors.surfaceContainer
            else -> colors.surfaceContainer.copy(alpha = 0.5f)
        },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (selected) colors.onPrimaryContainer else colors.onSurface,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (selected) colors.onPrimaryContainer.copy(alpha = 0.75f) else colors.onSurfaceVariant,
                )
            }
            if (selected) {
                Icon(
                    Icons.Filled.Check,
                    contentDescription = "已选中",
                    tint = colors.primary,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

/** 独立节点行（WARP / 手动添加 / 剪贴板导入，subscriptionId == null）：on/off 启停 + 单节点测速 + 点击编辑 + 删除 */
@Composable
private fun StandaloneNodeRow(
    node: ProxyNode,
    subtitle: String,
    testing: Boolean,
    onToggle: () -> Unit,
    onTest: () -> Unit,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    Surface(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 28.dp, vertical = 2.dp),
        shape = RoundedCornerShape(10.dp),
        color = if (node.enabled) colors.surfaceContainerHigh else colors.surfaceContainerHigh.copy(alpha = 0.5f),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                Icons.Filled.Widgets,
                contentDescription = null,
                tint = if (node.enabled) colors.primary else colors.onSurfaceVariant,
                modifier = Modifier.size(18.dp),
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    node.name.ifBlank { "未命名节点" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            if (testing) {
                CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
            } else {
                IconButton(onClick = onTest, modifier = Modifier.size(32.dp)) {
                    Icon(
                        Icons.Filled.Bolt,
                        contentDescription = "测速",
                        tint = colors.primary,
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            Text(
                text = if (node.enabled) "on" else "off",
                style = MaterialTheme.typography.labelSmall,
                color = if (node.enabled) colors.primary else colors.onSurfaceVariant,
            )
            Switch(
                checked = node.enabled,
                onCheckedChange = { onToggle() },
                modifier = Modifier.padding(start = 2.dp),
            )
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Filled.Delete, contentDescription = "删除", modifier = Modifier.size(18.dp))
            }
        }
    }
}

// 从 outboundJson 提取摘要，用纯字符串查找而非 Json.parseToJsonElement 全量解析。
// 节点列表渲染时对每个节点调用，全量 JSON 解析（含 tls/headers 等大字段）是回首页/滚动卡顿主因。
private fun String.nodeSummary(): String {
    if (isBlank()) return ""
    fun extract(key: String): String {
        // 匹配 "key":"value" 或 "key": "value"（value 不含转义引号）
        val idx = indexOf("\"$key\"")
        if (idx < 0) return ""
        var p = idx + key.length + 2
        while (p < length && (this[p] == ':' || this[p] == ' ' || this[p] == '\t')) p++
        if (p >= length || this[p] != '"') return ""
        val start = p + 1
        var end = start
        while (end < length && this[end] != '"') end++
        return substring(start, end)
    }
    val type = extract("type")
    val server = extract("server")
    val port = extract("server_port")
    return when {
        type.isBlank() && server.isBlank() -> ""
        server.isBlank() -> type
        else -> "$type · $server:$port"
    }
}

private fun clipboardText(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

private fun startVpn(context: Context) {
    android.util.Log.i("SbAI_VPN", "startVpn called")
    val intent = Intent(context, SbAiVpnService::class.java).setAction(SbAiVpnService.ACTION_START)
    // 等本进程待落盘的配置写完再启动 :core，否则其 reload() 读到旧配置
    val app = context.applicationContext
    com.sbai.data.RuleStore.get(app).afterPendingWrites { ContextCompat.startForegroundService(app, intent) }
    android.util.Log.i("SbAI_VPN", "startVpn completed - service starting")
}

private fun stopVpn(context: Context) {
    val intent = Intent(context, SbAiVpnService::class.java).setAction(SbAiVpnService.ACTION_STOP)
    context.startService(intent)
}

// ---------------------------------------------------------------------------
// 对话框
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SubscriptionEditorDialog(
    initial: Subscription,
    onDismiss: () -> Unit,
    onSave: (Subscription) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial.name) }
    var url by remember { mutableStateOf(initial.url) }
    var autoUpdate by remember { mutableStateOf(initial.autoUpdate) }
    var intervalHours by remember { mutableStateOf(initial.updateIntervalHours.toString()) }
    var userAgent by remember { mutableStateOf(initial.userAgent ?: "") }
    var includeKw by remember { mutableStateOf(initial.includeKeyword) }
    var excludeKw by remember { mutableStateOf(initial.excludeKeyword) }
    var removeDuplicates by remember { mutableStateOf(initial.removeDuplicates) }
    var removeInsecure by remember { mutableStateOf(initial.removeInsecure) }
    var removeInfoNodes by remember { mutableStateOf(initial.removeInfoNodes) }
    var urlTestAfterUpdate by remember { mutableStateOf(initial.urlTestAfterUpdate) }
    var removeUnavailable by remember { mutableStateOf(initial.removeUnavailable) }
    var sortByLatency by remember { mutableStateOf(initial.sortByLatency) }
    var detour by remember { mutableStateOf(initial.detour) }
    var skipCertVerify by remember { mutableStateOf(initial.skipCertVerify) }
    var blockImportRules by remember { mutableStateOf(initial.blockImportRules) }
    var groupNodesToRuleSets by remember { mutableStateOf(initial.groupNodesToRuleSets) }
    var renamePattern by remember { mutableStateOf(initial.renamePattern) }
    var renameReplace by remember { mutableStateOf(initial.renameReplace) }
    var filterProtocol by remember { mutableStateOf(initial.filterProtocol) }
    var filterRegion by remember { mutableStateOf(initial.filterRegion) }
    var error by remember { mutableStateOf<String?>(null) }

    // 整页编辑器（不再是弹窗）；拦截系统返回/侧滑回到首页
    BackHandler(enabled = true) { BottomBarController.show(); onDismiss() }
    RestoreBottomBarOnDispose()

    fun doSave() {
        val u = url.trim()
        // 只在新 URL 非空时校验格式：WARP 等无 URL 的本地订阅允许保存字段变更
        if (u.isNotBlank() && !u.startsWith("https://") && !u.startsWith("http://")) {
            error = "请输入 http/https 订阅地址"; return
        }
        onSave(
            initial.copy(
                name = name.trim(), url = u, autoUpdate = autoUpdate,
                updateIntervalHours = intervalHours.toIntOrNull()?.coerceAtLeast(0) ?: 24,
                userAgent = userAgent.ifBlank { null },
                includeKeyword = includeKw.trim(),
                excludeKeyword = excludeKw.trim(),
                removeDuplicates = removeDuplicates,
                removeInsecure = removeInsecure,
                removeInfoNodes = removeInfoNodes,
                urlTestAfterUpdate = urlTestAfterUpdate,
                removeUnavailable = removeUnavailable && urlTestAfterUpdate,
                sortByLatency = sortByLatency && urlTestAfterUpdate,
                detour = detour,
                skipCertVerify = skipCertVerify,
                blockImportRules = blockImportRules,
                groupNodesToRuleSets = groupNodesToRuleSets,
                renamePattern = renamePattern.trim(),
                renameReplace = renameReplace,
                filterProtocol = filterProtocol.trim(),
                filterRegion = filterRegion.trim(),
            ),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial.url.isBlank()) "添加订阅源" else "订阅源") },
                navigationIcon = {
                    IconButton(onClick = { BottomBarController.show(); onDismiss() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    if (onDelete != null) {
                        IconButton(onClick = onDelete) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                        }
                    }
                    TextButton(onClick = { doSave() }) { Text("保存") }
                },
            )
        },
    ) { padding ->
            // 整页可滚动：字段多（20+ 输入框/开关），不可滚动的 Column 会溢出屏幕看不到/点不到。
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称（留空则自动识别机场名）") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("订阅 URL（http/https）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)

                // #13：订阅更新走哪个出口
                Text("更新出口", style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = detour == "direct",
                        onClick = { detour = "direct" },
                        label = { Text("直连") },
                    )
                    FilterChip(
                        selected = detour == "proxy",
                        onClick = { detour = "proxy" },
                        label = { Text("代理") },
                    )
                }
                Text(
                    if (detour == "proxy") "通过当前运行的代理隧道拉取（需先启动 VPN）"
                    else "绕过代理直接拉取（订阅被墙时请改用代理）",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 跳过证书校验（机场 CDN 证书不匹配时用）
                SubOptionSwitch(
                    "跳过 TLS 证书校验",
                    skipCertVerify,
                ) { skipCertVerify = it }
                if (skipCertVerify) {
                    Text(
                        "⚠ 不安全：仅当机场域名证书不匹配（如提示 Hostname not verified）时开启。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                // 禁止导入订阅自带的路由规则
                SubOptionSwitch(
                    "禁用订阅规则",
                    blockImportRules,
                ) { blockImportRules = it }
                Text(
                    "开启后不导入这份订阅自带的路由规则，只用我自己的规则。下次更新生效。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Text(
                    "订阅规则默认按直连 / 代理 / 拦截归并成三个规则集，在「路由」页查看。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // 把订阅节点按直连/代理/拦截分组到规则集
                SubOptionSwitch(
                    "订阅节点分组到规则集",
                    groupNodesToRuleSets,
                ) { groupNodesToRuleSets = it }
                Text(
                    "另外生成「<订阅名>直连 / 代理 / 拦截」三组节点，路由规则可整组引用这批节点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                OutlinedTextField(
                    value = userAgent, onValueChange = { userAgent = it },
                    label = { Text("User-Agent（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = autoUpdate, onCheckedChange = { autoUpdate = it })
                    Spacer(Modifier.size(8.dp))
                    Text("自动更新")
                }
                if (autoUpdate) {
                    OutlinedTextField(
                        value = intervalHours, onValueChange = { intervalHours = it },
                        label = { Text("更新间隔（小时，0 = 每次启动检查）") }, singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                OutlinedTextField(
                    value = includeKw, onValueChange = { includeKw = it },
                    label = { Text("包含关键字（空格分隔，可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = excludeKw, onValueChange = { excludeKw = it },
                    label = { Text("排除关键字（空格分隔，可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = filterProtocol, onValueChange = { filterProtocol = it },
                    label = { Text("协议过滤（如 hysteria2 vless，空格分隔，可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = filterRegion, onValueChange = { filterRegion = it },
                    label = { Text("地区过滤（如 香港 日本，空格分隔，可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                // 节点重命名（正则替换）：renamePattern 非空时把节点名中的匹配部分换成 renameReplace。
                OutlinedTextField(
                    value = renamePattern, onValueChange = { renamePattern = it },
                    label = { Text("节点重命名正则（可选，如 `香港|HK`）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                if (renamePattern.isNotBlank()) {
                    OutlinedTextField(
                        value = renameReplace, onValueChange = { renameReplace = it },
                        label = { Text("替换为（可留空=删除匹配部分）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }

                // ---- 节点后处理（SubscriptionOptions 基准） ----
                Text("节点后处理", style = MaterialTheme.typography.labelLarge)
                SubOptionSwitch("去重（同名节点保留第一个）", removeDuplicates) { removeDuplicates = it }
                SubOptionSwitch("去除不安全节点（明文/无加密）", removeInsecure) { removeInsecure = it }
                SubOptionSwitch("去除信息节点（剩余流量/到期/官网公告）", removeInfoNodes) { removeInfoNodes = it }
                SubOptionSwitch("更新后自动测速（urltest）", urlTestAfterUpdate) {
                    urlTestAfterUpdate = it
                    if (!it) { removeUnavailable = false; sortByLatency = false }
                }
                if (urlTestAfterUpdate) {
                    SubOptionSwitch("移除不可用节点", removeUnavailable) { removeUnavailable = it }
                    SubOptionSwitch("按延迟排序", sortByLatency) { sortByLatency = it }
                }

                // 流量信息展示（来自 subscription-userinfo 头）
                if (initial.trafficTotal > 0) {
                    val used = initial.trafficUpload + initial.trafficDownload
                    Text(
                        "流量: ${com.sbai.ui.monitor.formatBytes(used)} / " +
                            "${com.sbai.ui.monitor.formatBytes(initial.trafficTotal)}" +
                            if (initial.trafficExpire > 0) {
                                " · 到期: " + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                                    .format(Date(initial.trafficExpire * 1000))
                            } else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
    }
}

// ---------------------------------------------------------------------------
// 节点列表过滤器枚举
// ---------------------------------------------------------------------------

/** 预计算好的节点行：subtitle 在缓存阶段算好，composable 内不再解析 JSON */
private data class NodeRow(val node: ProxyNode, val subtitle: String)

enum class NodeSortMode { NAME_ASC, LATENCY_ASC }

