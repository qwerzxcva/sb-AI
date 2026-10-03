package com.sbai.ui.home

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.VpnService
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Widgets
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
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
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
import com.sbai.data.LoadBalanceConfig
import com.sbai.data.LoadBalanceMode
import com.sbai.data.ProxyNode
import com.sbai.data.RuleStore
import com.sbai.data.StickyHashKey
import com.sbai.data.Subscription
import com.sbai.data.UrltestMode
import com.sbai.service.SbAiVpnService
import com.sbai.service.SbCommandClient
import com.sbai.service.SingBoxConfigGenerator
import com.sbai.service.SubscriptionManager
import com.sbai.ui.components.SbBadge
import com.sbai.ui.components.SbCollapsibleGroup
import com.sbai.ui.components.SbGroup
import com.sbai.ui.components.SbItem
import com.sbai.ui.components.SbSpacer
import com.sbai.ui.components.SbSwitchItem
import com.sbai.ui.theme.LocalSbStyleTokens
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val state by store.state.collectAsState()
    val status by SbAiVpnService.status.collectAsState()
    val commandStatus by SbCommandClient.status.collectAsState()
    val proxyGroups by SbCommandClient.groups.collectAsState()
    val coreConnected by SbCommandClient.connectedToService.collectAsState()
    val scope = rememberCoroutineScope()
    val tokens = LocalSbStyleTokens.current

    val subManager = remember { SubscriptionManager(store, context.applicationContext) }
    var refreshingId by remember { mutableStateOf<String?>(null) }

    var editingNode by remember { mutableStateOf<ProxyNode?>(null) }
    var editingSub by remember { mutableStateOf<Subscription?>(null) }
    var showConfigPreview by remember { mutableStateOf(false) }
    var showModeDialog by remember { mutableStateOf(false) }
    var showNodesPicker by remember { mutableStateOf(false) }
    var editingText by remember { mutableStateOf<Triple<String, String, (String) -> Unit>?>(null) }
    var importResult by remember { mutableStateOf<String?>(null) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) startVpn(context)
    }

    val running = status is SbAiVpnService.ServiceStatus.Running
    val lb = state.loadBalance

    // 进程隔离后：UI 进程自建 CommandClient 连接 :core 进程的 CommandServer（unix socket 跨进程），
    // 以 connectedToService 作为「内核是否在跑」的真源（StateFlow 不跨进程共享）。
    LaunchedEffect(Unit) {
        SbCommandClient.connect()
    }
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
                            statusText(status),
                            style = MaterialTheme.typography.bodyMedium,
                            color = when (status) {
                                is SbAiVpnService.ServiceStatus.Running -> MaterialTheme.colorScheme.primary
                                is SbAiVpnService.ServiceStatus.Error -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                        if (coreRunning) {
                            Text(
                                "↑ ${com.sbai.ui.monitor.formatSpeed(commandStatus.uplink)} · " +
                                    "↓ ${com.sbai.ui.monitor.formatSpeed(commandStatus.downlink)}",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.secondary,
                            )
                        }
                    }
                    // 大号启动按钮
                    Surface(
                        onClick = {
                            when {
                                coreRunning -> stopVpn(context)
                                status is SbAiVpnService.ServiceStatus.Starting ||
                                    status is SbAiVpnService.ServiceStatus.Stopping -> Unit
                                else -> {
                                    val intent = VpnService.prepare(context)
                                    if (intent != null) vpnPermissionLauncher.launch(intent) else startVpn(context)
                                }
                            }
                        },
                        shape = RoundedCornerShape(tokens.groupCornerRadius),
                        color = if (coreRunning) MaterialTheme.colorScheme.errorContainer
                        else MaterialTheme.colorScheme.primaryContainer,
                        modifier = Modifier.size(72.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                Icons.Filled.PowerSettingsNew,
                                contentDescription = if (coreRunning) "停止" else "启动",
                                tint = if (coreRunning) MaterialTheme.colorScheme.onErrorContainer
                                else MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(32.dp),
                            )
                        }
                    }
                }
                SbSpacer()
            }

            // ---- 实时流量卡（ClashFest 首页观感；运行中显示） ----
            if (coreRunning) {
                item {
                    HomeTrafficCard(commandStatus)
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
                        // 选点模式（LxBox §208）
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
                    state.subscriptions.forEach { sub ->
                        item {
                            SubscriptionCard(
                                sub = sub,
                                refreshing = refreshingId == sub.id,
                                onRefresh = {
                                    refreshingId = sub.id
                                    scope.launch {
                                        subManager.refresh(sub)
                                        refreshingId = null
                                    }
                                },
                                onClick = { editingSub = sub },
                            )
                        }
                    }
                    item {
                        SbItem(
                            title = "添加订阅源",
                            subtitle = "支持 vless / vmess / trojan / ss / hysteria2 分享链接",
                            icon = Icons.Filled.Add,
                            onClick = { editingSub = Subscription() },
                        )
                    }
                    if (state.subscriptions.size > 1) {
                        item {
                            SbItem(
                                title = "全部更新",
                                icon = Icons.Filled.Refresh,
                                onClick = {
                                    scope.launch {
                                        state.subscriptions.filter { it.enabled }.forEach { sub ->
                                            refreshingId = sub.id
                                            subManager.refresh(sub)
                                        }
                                        refreshingId = null
                                    }
                                },
                            )
                        }
                    }
                }
                SbSpacer()
            }

            // ---- 节点 ----
            item {
                SbGroup(title = "节点（${state.proxyNodes.size}）") {
                    state.proxyNodes.take(20).forEach { node ->
                        item {
                            SbItem(
                                title = node.name.ifBlank { "未命名节点" },
                                subtitle = node.outboundJson.nodeSummary(),
                                icon = Icons.Filled.Widgets,
                                onClick = { editingNode = node },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Switch(
                                            checked = node.enabled,
                                            onCheckedChange = { store.upsertProxyNode(node.copy(enabled = !node.enabled)) },
                                        )
                                        IconButton(onClick = { store.deleteProxyNode(node.id) }) {
                                            Icon(Icons.Filled.Delete, contentDescription = "删除")
                                        }
                                    }
                                },
                            )
                        }
                    }
                    if (state.proxyNodes.size > 20) {
                        item { SbItem(title = "… 共 ${state.proxyNodes.size} 个节点", subtitle = "显示前 20 个") }
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
                            subtitle = "解析分享链接（vless/vmess/trojan/ss/hysteria2）",
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
                Spacer(Modifier.height(120.dp))
            }
        }
    }

    // ---- 对话框 ----
    editingSub?.let { sub ->
        SubscriptionEditorDialog(
            initial = sub,
            onDismiss = { editingSub = null },
            onSave = { store.upsertSubscription(it); editingSub = null },
            onDelete = if (sub.url.isNotBlank()) {
                { store.deleteSubscription(sub.id); editingSub = null }
            } else null,
        )
    }

    if (showConfigPreview) {
        ConfigPreviewDialog(
            config = runCatching { SingBoxConfigGenerator.generate(state) }.getOrElse { "生成失败: ${it.message}" },
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

    if (showNodesPicker) {
        // 与生成器同口径解析节点 tag（避免显示名与配置 tag 不一致导致勾选无效）
        val nodeTags = state.proxyNodes.filter { it.enabled }
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
private fun HomeTrafficCard(status: com.sbai.service.SbCommandClient.DashboardStatus) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.primaryContainer,
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
    refreshing: Boolean,
    onRefresh: () -> Unit,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val used = sub.trafficUpload + sub.trafficDownload
    val hasTraffic = sub.trafficTotal > 0

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(sub.name.ifBlank { sub.url }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                Text(
                    sub.lastError?.let { "错误: $it" }
                        ?: "${sub.nodeCount} 节点 · ${formatTime(sub.lastUpdatedAt)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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

        if (hasTraffic) {
            Spacer(Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { (used.toFloat() / sub.trafficTotal.toFloat()).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(6.dp),
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

private fun String.nodeSummary(): String = runCatching {
    val obj = Json.parseToJsonElement(this).jsonObject
    val type = obj["type"]?.toString()?.trim('"').orEmpty()
    val server = obj["server"]?.toString()?.trim('"').orEmpty()
    val port = obj["server_port"]?.toString().orEmpty()
    "$type · $server:$port"
}.getOrDefault("")

private fun clipboardText(context: Context): String? = runCatching {
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
}.getOrNull()

private fun startVpn(context: Context) {
    val intent = Intent(context, SbAiVpnService::class.java).setAction(SbAiVpnService.ACTION_START)
    ContextCompat.startForegroundService(context, intent)
}

private fun stopVpn(context: Context) {
    val intent = Intent(context, SbAiVpnService::class.java).setAction(SbAiVpnService.ACTION_STOP)
    context.startService(intent)
}

// ---------------------------------------------------------------------------
// 对话框
// ---------------------------------------------------------------------------

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
    var urlTestAfterUpdate by remember { mutableStateOf(initial.urlTestAfterUpdate) }
    var removeUnavailable by remember { mutableStateOf(initial.removeUnavailable) }
    var sortByLatency by remember { mutableStateOf(initial.sortByLatency) }
    var detour by remember { mutableStateOf(initial.detour) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.url.isBlank()) "添加订阅源" else "订阅源") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
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

                // ---- 节点后处理（Throne SubscriptionOptions 基准） ----
                Text("节点后处理", style = MaterialTheme.typography.labelLarge)
                SubOptionSwitch("去重（同名节点保留第一个）", removeDuplicates) { removeDuplicates = it }
                SubOptionSwitch("去除不安全节点（明文/无加密）", removeInsecure) { removeInsecure = it }
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
        },
        confirmButton = {
            TextButton(onClick = {
                val u = url.trim()
                if (!u.startsWith("https://") && !u.startsWith("http://")) {
                    error = "请输入 http/https 订阅地址"; return@TextButton
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
                        urlTestAfterUpdate = urlTestAfterUpdate,
                        removeUnavailable = removeUnavailable && urlTestAfterUpdate,
                        sortByLatency = sortByLatency && urlTestAfterUpdate,
                        detour = detour,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = {
            Row {
                if (onDelete != null) {
                    TextButton(onClick = onDelete) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
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
