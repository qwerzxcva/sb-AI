package com.sbai.ui.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Widgets
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import com.sbai.data.LoadBalanceConfig
import com.sbai.data.LoadBalanceMode
import com.sbai.data.ProxyNode
import com.sbai.data.RuleStore
import com.sbai.data.StickyHashKey
import com.sbai.data.Subscription
import com.sbai.data.UrltestMode
import com.sbai.service.SbAiVpnService
import com.sbai.service.SbCommandClient
import com.sbai.service.VpnRuntimeState
import com.sbai.service.NodeBatchTester
import com.sbai.service.SingBoxConfigGenerator
import com.sbai.service.SubscriptionManager
import com.sbai.ui.components.SbBadge
import com.sbai.ui.components.SbCollapsibleGroup
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

    val status by SbAiVpnService.status.collectAsState()
    val proxyGroups by SbCommandClient.groups.collectAsState()
    val coreConnected by SbCommandClient.connectedToService.collectAsState()
    val scope = rememberCoroutineScope()
    val tokens = LocalSbStyleTokens.current

    val subManager = remember { SubscriptionManager(store, context.applicationContext) }
    var refreshingId by remember { mutableStateOf<String?>(null) }

    var editingNode by remember { mutableStateOf<ProxyNode?>(null) }
    var editingSub by remember { mutableStateOf<Subscription?>(null) }
    // 订阅删除确认（含无 URL 的本地订阅，如 WARP）
    var subToDelete by remember { mutableStateOf<Subscription?>(null) }
    var showConfigPreview by remember { mutableStateOf(false) }
    var showModeDialog by remember { mutableStateOf(false) }
    var showNodesPicker by remember { mutableStateOf(false) }
    var editingText by remember { mutableStateOf<Triple<String, String, (String) -> Unit>?>(null) }
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

    // 单节点测速：触发内核 URLTest 后回填该节点延迟。
    // 需要 VPN 已运行（CommandServer 在线），否则提示先启动。
    fun testSingleNode(node: ProxyNode) {
        if (!coreConnected) {
            importResult = "请先启动 VPN 再测速"
            return
        }
        if (testingNodeId != null) return
        testingNodeId = node.id
        scope.launch {
            runCatching {
                val groupTag = proxyGroups
                    .firstOrNull { it.type == "urltest" }?.tag
                    ?: proxyGroups.firstOrNull { it.type == "selector" }?.tag
                    ?: "lb"
                val delay = SbCommandClient.urlTestOutbound(groupTag, node.name, timeoutMs = 5000)
                if (delay != null && delay > 0) {
                    store.updateCommitted { s ->
                        s.copy(proxyNodes = s.proxyNodes.map { n ->
                            if (n.id == node.id) n.copy(urlTestDelay = delay, urlTestTime = System.currentTimeMillis()) else n
                        })
                    }
                } else {
                    importResult = "「${node.name}」测速失败（无响应或超时）"
                }
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
    LaunchedEffect(Unit) {
        while (true) {
            withContext(Dispatchers.IO) {
                VpnRuntimeState.refreshFromDisk(context)
                // :core 会异步自动禁用坏节点；UI 轮询时把最新磁盘态刷入内存，
                // 否则首页的代理列表/节点数量会滞后（P0 相关体验问题）。
                RuleStore.get(context).refreshFromDisk()
            }
            delay(2000L)
        }
    }
    // 卡死检测：Starting/Stopping 超过 90s 视为已回退 Stopped（服务崩溃/被杀场景）
    var lastPublishedAt by remember { mutableStateOf(0L) }
    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            lastPublishedAt = VpnRuntimeState.lastPublishedAt(context)
        }
    }
    val effectivePhase = when (vpnPhase) {
        VpnRuntimeState.Phase.Starting, VpnRuntimeState.Phase.Stopping ->
            if (lastPublishedAt > 0 &&
                System.currentTimeMillis() - lastPublishedAt > VpnRuntimeState.STUCK_TIMEOUT_MS
            ) VpnRuntimeState.Phase.Stopped else vpnPhase
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
            // ---- 顶部状态区 ----
            item {
                Spacer(Modifier.height(24.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("sb-AI", style = MaterialTheme.typography.headlineLarge)
                        Text(
                            // 跨进程真源：:core 进程把运行阶段写盘，UI 进程读取；
                            // 不再用 CommandClient 连接状态冒充 VPN 状态。
                            displayVpnPhase(effectivePhase) +
                                (vpnMessage?.let { " · $it" } ?: ""),
                            style = MaterialTheme.typography.bodyMedium,
                            color = when {
                                effectivePhase == VpnRuntimeState.Phase.Error -> MaterialTheme.colorScheme.error
                                running -> MaterialTheme.colorScheme.primary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    // 启动控制已收敛到底栏常驻 VPN 控件（导航栏旁），此处不再重复提供按钮。
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

            // ---- 代理出口（负载均衡内嵌；自动模式与负载均衡解耦，可单独开） ----
            item {
                SbGroup(title = "代理出口") {
                    item {
                        SbSwitchItem(
                            title = "负载均衡",
                            subtitle = if (lb.enabled) lb.mode.displayName else "关闭",
                            icon = Icons.Filled.Balance,
                            checked = lb.enabled,
                            onCheckedChange = { store.updateLoadBalance(lb.copy(enabled = it)) },
                        )
                    }
                    if (lb.enabled) {
                        item {
                            SbItem(
                                title = "模式",
                                subtitle = lb.mode.displayName,
                                icon = Icons.Filled.Router,
                                onClick = { showModeDialog = true },
                            )
                        }
                        item {
                            SbItem(
                                title = "参与节点",
                                subtitle = if (lb.outbounds.isEmpty()) "全部节点" else lb.outbounds.joinToString(),
                                icon = Icons.Filled.Hub,
                                onClick = { showNodesPicker = true },
                            )
                        }
                    }
                    // 自动模式独立于负载均衡：关掉负载均衡后仍可单独开启自动优选
                    item {
                        SbSwitchItem(
                            title = "自动模式",
                            subtitle = "自动优选延迟最低的出口（可与负载均衡搭配，也可单独使用）",
                            icon = Icons.Filled.Sync,
                            checked = lb.autoEnabled,
                            onCheckedChange = { store.updateLoadBalance(lb.copy(autoEnabled = it)) },
                        )
                    }
                    item {
                        SbItem(
                            title = "配置预览",
                            subtitle = "查看生成的 sing-box 配置",
                            icon = Icons.Filled.Code,
                            onClick = { showConfigPreview = true },
                        )
                    }
                }
                SbSpacer()
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

            // ---- 负载均衡高级参数（折叠） ----
            if (lb.enabled) {
                item {
                    SbCollapsibleGroup(
                        title = "负载均衡参数",
                        summary = "测速间隔 ${lb.interval} · tolerance ${lb.toleranceMs}ms · 选点 ${lb.urltestMode.displayName}" +
                            if (lb.urltestMode == UrltestMode.ROUND_ROBIN) " · 池 ${lb.pool} 节点" else "",
                    ) {
                        item {
                            SbItem(title = "测速 URL", subtitle = lb.checkUrl, onClick = {
                                editingText = Triple("测速 URL", lb.checkUrl) { v ->
                                    store.updateLoadBalance(lb.copy(checkUrl = v.trim()))
                                }
                            })
                        }
                        item {
                            SbItem(title = "测速间隔", subtitle = "${lb.interval}（sing-box duration，如 5m/15m/1h）", onClick = {
                                editingText = Triple("测速间隔（如 15m）", lb.interval) { v ->
                                    if (v.isNotBlank()) store.updateLoadBalance(lb.copy(interval = v.trim()))
                                }
                            })
                        }
                        item {
                            SbItem(title = "tolerance（毫秒）", subtitle = lb.toleranceMs.toString(), onClick = {
                                editingText = Triple("tolerance（毫秒）", lb.toleranceMs.toString()) { v ->
                                    v.toIntOrNull()?.let { n -> store.updateLoadBalance(lb.copy(toleranceMs = n)) }
                                }
                            })
                        }
                        item {
                            SbItem(title = "idle_timeout", subtitle = "${lb.idleTimeout}（如 30m）", onClick = {
                                editingText = Triple("idle_timeout（如 30m）", lb.idleTimeout) { v ->
                                    if (v.isNotBlank()) store.updateLoadBalance(lb.copy(idleTimeout = v.trim()))
                                }
                            })
                        }
                        // 选点模式（sb-AI §208）
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("选点模式", style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.height(4.dp))
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    UrltestMode.entries.forEachIndexed { i, mode ->
                                        SegmentedButton(
                                            selected = lb.urltestMode == mode,
                                            onClick = {
                                                store.updateLoadBalance(lb.copy(urltestMode = mode))
                                            },
                                            shape = SegmentedButtonDefaults.itemShape(
                                                index = i, count = UrltestMode.entries.size,
                                            ),
                                        ) { Text(mode.displayName) }
                                }
                                }
                                Text(
                                    if (lb.urltestMode == UrltestMode.LEAST_TEST) {
                                        "始终选用延迟最低的一个节点（上游行为）"
                                    } else {
                                        "在下方「节点池」大小的节点集合内轮询分摊流量（fork 扩展）"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        // 节点池大小 N（仅 round_robin 生效）
                        if (lb.urltestMode == UrltestMode.ROUND_ROBIN) {
                            item {
                                SbItem(
                                    title = "节点池大小（仅用 N 个节点）",
                                    subtitle = "${lb.pool} 个节点参与负载均衡",
                                    onClick = {
                                        editingText = Triple("节点池大小 N", lb.pool.toString()) { v ->
                                            v.toIntOrNull()?.takeIf { it >= 1 }
                                                ?.let { n -> store.updateLoadBalance(lb.copy(pool = n)) }
                                        }
                                    },
                                )
                            }
                            item {
                                SbItem(
                                    title = "pool_tolerance（毫秒）",
                                    subtitle = "${lb.poolTolerance}（0 = 保持池内节点存活；>0 = 每轮按延迟选最优 N 个）",
                                    onClick = {
                                        editingText = Triple("pool_tolerance（毫秒）", lb.poolTolerance.toString()) { v ->
                                            v.toIntOrNull()?.takeIf { it >= 0 }
                                                ?.let { n -> store.updateLoadBalance(lb.copy(poolTolerance = n)) }
                                        }
                                    },
                                )
                            }
                            item {
                                Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                    Text("粘性会话（sticky_hash）", style = MaterialTheme.typography.labelLarge)
                                    Spacer(Modifier.height(4.dp))
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        StickyHashKey.entries.forEach { key ->
                                            val selected = key in lb.stickyHash
                                            FilterChip(
                                                selected = selected,
                                                onClick = {
                                                    val next = if (selected) lb.stickyHash - key
                                                    else (lb.stickyHash - StickyHashKey.NONE) + key
                                                    store.updateLoadBalance(lb.copy(stickyHash = next))
                                                },
                                                label = { Text(key.displayName) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                        item {
                            SbSwitchItem(
                                title = "切换时中断已有连接",
                                checked = lb.interruptExistConnections,
                                onCheckedChange = { store.updateLoadBalance(lb.copy(interruptExistConnections = it)) },
                            )
                        }
                    }
                    SbSpacer()
                }
            }

            // ---- 订阅源 ----
            item {
                SbGroup(title = "订阅源") {
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
                                                subManager.refresh(sub)
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
                                            } finally {
                                                batchTestProgress = -1
                                            }
                                        }
                                    }
                                },
                                onEditNode = { editingNode = it },
                                onDeleteNode = { store.deleteProxyNode(it) },
                                onToggleNode = { node, enabled ->
                                    store.upsertProxyNode(node.copy(enabled = enabled, disabledReason = null))
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
                                            try {
                                                subscriptions.filter { it.enabled }.forEach { sub ->
                                                    refreshingId = sub.id
                                                    subManager.refresh(sub)
                                                }
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
                SbGroup(title = "独立节点（${standaloneNodes}）") {
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
    if (showConfigPreview) {
        // 进入预览时取完整快照，后台生成；关闭对话框会取消结果发布。
        var previewConfig by remember { mutableStateOf("正在生成配置…") }
        LaunchedEffect(Unit) {
            val snapshot = store.state.value
            previewConfig = try {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    SingBoxConfigGenerator.generate(snapshot)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                "生成失败: ${e.message}"
            }
        }
        ConfigPreviewDialog(
            config = previewConfig,
            onDismiss = { showConfigPreview = false },
        )
    }

    if (showModeDialog) {
        AlertDialog(
            onDismissRequest = { showModeDialog = false },
            title = { Text("负载均衡模式") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    LoadBalanceMode.entries.forEach { mode ->
                        Surface(
                            onClick = {
                                store.updateLoadBalance(lb.copy(mode = mode))
                                showModeDialog = false
                            },
                            shape = RoundedCornerShape(16.dp),
                            color = if (lb.mode == mode) MaterialTheme.colorScheme.primaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer,
                        ) {
                            Column(Modifier.padding(16.dp)) {
                                Text(mode.displayName, style = MaterialTheme.typography.titleSmall)
                                Text(
                                    when (mode) {
                                        LoadBalanceMode.LATENCY -> "urltest：始终选延迟最低的节点"
                                        LoadBalanceMode.BALANCED -> "urltest+tolerance：在可接受延迟内分摊节点"
                                        LoadBalanceMode.MANUAL -> "selector：手动切换出口"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showModeDialog = false }) { Text("关闭") } },
        )
    }

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

    if (showNodesPicker) {
        // 与生成器同口径解析节点 tag（避免显示名与配置 tag 不一致导致勾选无效）
        val nodeTags = proxyNodes.filter { it.enabled }
            .map { SingBoxConfigGenerator.nodeTagOf(it) }.distinct()
        AlertDialog(
            onDismissRequest = { showNodesPicker = false },
            title = { Text("参与负载均衡的节点") },
            text = {
                Column(
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("全部不选 = 使用全部启用节点", style = MaterialTheme.typography.bodySmall)
                    nodeTags.forEach { tag ->
                        FilterChip(
                            selected = lb.outbounds.isEmpty() || tag in lb.outbounds,
                            onClick = {
                                val current = if (lb.outbounds.isEmpty()) nodeTags else lb.outbounds
                                val next = if (tag in current) current - tag else current + tag
                                store.updateLoadBalance(
                                    lb.copy(outbounds = if (next.size == nodeTags.size) emptyList() else next),
                                )
                            },
                            label = { Text(tag) },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showNodesPicker = false }) { Text("完成") } },
        )
    }

    editingText?.let { (title, initialValue, onDone) ->
        TextEditDialog(
            title = title,
            initial = initialValue,
            onDismiss = { editingText = null },
            onDone = { onDone(it); editingText = null },
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
    VpnRuntimeState.Phase.Error ->
        if (message == VpnRuntimeState.STALE_MESSAGE) "状态未知，可重试" else "启动失败"
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
                    "↑ ${com.sbai.ui.monitor.formatSpeed(status.uplink)}   ↓ ${com.sbai.ui.monitor.formatSpeed(status.downlink)}",
                    style = MaterialTheme.typography.titleLarge,
                    color = colors.onPrimaryContainer,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("累计", style = MaterialTheme.typography.labelMedium, color = colors.onPrimaryContainer.copy(alpha = 0.75f))
                Text(
                    "↑ ${com.sbai.ui.monitor.formatBytes(status.uplinkTotal)}   ↓ ${com.sbai.ui.monitor.formatBytes(status.downlinkTotal)}",
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
        
        // 展开后显示该订阅的节点：节点启用开关 + 测速
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
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                row.node.name.ifBlank { "未命名节点" },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Text(
                                row.subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        // 节点启用/禁用
                        Switch(
                            checked = row.node.enabled,
                            onCheckedChange = { onToggleNode(row.node, it) },
                        )
                        IconButton(onClick = { onDeleteNode(row.node.id) }) {
                            Icon(Icons.Filled.Delete, contentDescription = "删除节点")
                        }
                        IconButton(onClick = { onEditNode(row.node) }) {
                            Icon(Icons.Filled.Edit, contentDescription = "编辑节点")
                        }
                    }
                }
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
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
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

@Composable
private fun ConfigPreviewDialog(config: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("sing-box 配置预览") },
        text = {
            OutlinedTextField(value = config, onValueChange = {}, readOnly = true, modifier = Modifier.fillMaxWidth(), minLines = 12)
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

@Composable
private fun TextEditDialog(title: String, initial: String, onDismiss: () -> Unit, onDone: (String) -> Unit) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(value = value, onValueChange = { value = it }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onDone(value) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------------------------------------------------------------------
// 节点列表过滤器枚举
// ---------------------------------------------------------------------------

/** 预计算好的节点行：subtitle 在缓存阶段算好，composable 内不再解析 JSON */
private data class NodeRow(val node: ProxyNode, val subtitle: String)

enum class NodeSortMode { NAME_ASC, LATENCY_ASC }

