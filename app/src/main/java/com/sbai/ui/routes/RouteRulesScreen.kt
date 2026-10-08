package com.sbai.ui.routes

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.GroupWork
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sbai.data.AppState
import com.sbai.data.DnsGroup
import com.sbai.data.DnsRule
import com.sbai.data.DnsServer
import com.sbai.data.DnsServerType
import com.sbai.data.RouteRule
import com.sbai.data.RouteRuleSet
import com.sbai.data.RuleAction
import com.sbai.data.RuleLogic
import com.sbai.data.RuleSetType
import com.sbai.data.RuleStore
import com.sbai.service.DnsRuleJsonCodec
import com.sbai.service.RouteRuleJsonCodec
import com.sbai.service.SingBoxConfigGenerator
import com.sbai.ui.components.AppPickerDialog
import com.sbai.ui.components.BottomBarClearance
import com.sbai.ui.components.BottomBarController
import com.sbai.ui.components.DragDropLazyColumn
import com.sbai.ui.components.FabBottomBarClearance
import com.sbai.ui.components.RestoreBottomBarOnDispose
import com.sbai.ui.components.SbBadge
import com.sbai.ui.components.SbGroup
import com.sbai.ui.components.SbItem
import com.sbai.ui.theme.LocalSbStyleTokens
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

/** sb-AI kKnownNetworks：tcp / udp / icmp */
private val NETWORK_OPTIONS = listOf("tcp", "udp", "icmp")

/** sb-AI kKnownProtocols：L7 嗅探签名全集 */
private val PROTOCOL_OPTIONS = listOf(
    "bittorrent", "dns", "dtls", "http", "ntp", "quic", "rdp", "ssh", "stun", "tls",
)

private val DNS_QUERY_TYPES = listOf("A", "AAAA", "CNAME", "HTTPS", "TXT", "MX", "SOA")
private val IP_STRATEGIES = listOf("", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only")
private val ECH_CAPABLE = setOf(
    DnsServerType.TLS, DnsServerType.HTTPS, DnsServerType.QUIC, DnsServerType.H3,
)

/**
 * 路由 / DNS 融合页（替代独立 DnsScreen）：
 * - Pager 第 1 页「路由/DNS 规则」：上段路由规则（可拖拽），下段 DNS 规则（手动可拖拽 + 自动只读），两段标题分隔。
 * - Pager 第 2 页「DNS 设置」：DNS 服务器 + DNS group（原 DnsScreen Tab0/Tab2）。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun RouteRulesScreen() {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val tokens = LocalSbStyleTokens.current

    // 字段级订阅：本页只用 5 个字段，全量订阅会因节点等无关变化重组本页。
    val routeRules by remember(store) {
        store.state.map { it.routeRules }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.routeRules })
    val routeRuleSets by remember(store) {
        store.state.map { it.routeRuleSets }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.routeRuleSets })
    val dnsServers by remember(store) {
        store.state.map { it.dnsServers }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.dnsServers })
    val dnsGroups by remember(store) {
        store.state.map { it.dnsGroups }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.dnsGroups })
    val dnsRules by remember(store) {
        store.state.map { it.dnsRules }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.dnsRules })
    val resources by remember(store) {
        store.state.map { it.settings.resources }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.settings.resources })

    var tab by remember { mutableIntStateOf(0) }
    val pagerState = rememberPagerState(pageCount = { 2 })
    LaunchedEffect(pagerState.currentPage) { tab = pagerState.currentPage }
    LaunchedEffect(tab) { if (tab != pagerState.currentPage) pagerState.scrollToPage(tab) }

    var editingRouteRule by remember { mutableStateOf<RouteRule?>(null) }
    var editingRouteRuleSet by remember { mutableStateOf<RouteRuleSet?>(null) }
    var showRouteRuleSetManager by remember { mutableStateOf(false) }
    var editingDnsServer by remember { mutableStateOf<DnsServer?>(null) }
    var editingDnsGroup by remember { mutableStateOf<DnsGroup?>(null) }
    var editingDnsRule by remember { mutableStateOf<DnsRule?>(null) }
    var showResourcesManager by remember { mutableStateOf(false) }

    // 路由规则自动推导的 DNS 规则（只读展示）——缓存，避免每次重组重算
    val autoDnsRules by remember(dnsServers, dnsGroups, routeRules) {
        derivedStateOf {
            SingBoxConfigGenerator.autoDnsRulesFromRouteRules(dnsServers, dnsGroups, routeRules)
        }
    }
    // 路由规则自动推导的路由规则（fakeIP 联动，只读展示）
    val autoRouteRules by remember(routeRules, dnsServers, dnsGroups) {
        derivedStateOf {
            SingBoxConfigGenerator.autoRouteRules(
                AppState(dnsServers = dnsServers, dnsGroups = dnsGroups, routeRules = routeRules),
            )
        }
    }

    // 编辑器全部为整页（二级页面），提前 return 覆盖列表页
    editingRouteRule?.let { rule ->
        RouteRuleEditorDialog(
            initial = rule,
            dnsOptions = dnsServers.filter { it.enabled }.map { it.tag } + dnsGroups.map { it.name },
            ruleSetTags = routeRuleSets.filter { it.enabled }.map { it.tag },
            onDismiss = { editingRouteRule = null },
            onSave = { store.upsertRouteRule(it); editingRouteRule = null },
            onCreateRuleSet = { editingRouteRuleSet = RouteRuleSet() },
        )
        return
    }
    editingRouteRuleSet?.let { rs ->
        RuleSetEditorDialog(
            initial = rs,
            onDismiss = { editingRouteRuleSet = null },
            onSave = { store.upsertRuleSet(it); editingRouteRuleSet = null },
            onDelete = if (rs.tag.isNotBlank()) {
                { store.deleteRuleSet(rs.id); editingRouteRuleSet = null }
            } else null,
        )
        return
    }
    editingDnsServer?.let { server ->
        DnsServerEditorDialog(
            initial = server,
            existingServers = dnsServers.map { it.tag },
            existingFakeipTag = dnsServers
                .firstOrNull { it.type == DnsServerType.FAKEIP && it.id != server.id }?.tag,
            onDismiss = { editingDnsServer = null },
            onSave = { store.upsertDnsServer(it); editingDnsServer = null },
            onDelete = if (server.tag.isNotBlank()) {
                { store.deleteDnsServer(server.id); editingDnsServer = null }
            } else null,
        )
        return
    }
    editingDnsGroup?.let { group ->
        DnsGroupEditorDialog(
            initial = group,
            serverTags = dnsServers.filter { it.enabled }.map { it.tag },
            onDismiss = { editingDnsGroup = null },
            onSave = { store.upsertDnsGroup(it); editingDnsGroup = null },
            onDelete = if (group.name.isNotBlank()) {
                { store.deleteDnsGroup(group.id); editingDnsGroup = null }
            } else null,
        )
        return
    }
    editingDnsRule?.let { rule ->
        DnsRuleEditorDialog(
            initial = rule,
            serverOptions = dnsServers.filter { it.enabled }.map { it.tag } + dnsGroups.map { it.name },
            ruleSetTags = routeRuleSets.filter { it.enabled }.map { it.tag },
            onDismiss = { editingDnsRule = null },
            onSave = { store.upsertDnsRule(it); editingDnsRule = null },
            onDelete = if (rule.autoFromRouteRuleId == null && rule.name.isNotBlank()) {
                { store.deleteDnsRule(rule.id); editingDnsRule = null }
            } else null,
        )
        return
    }
    if (showResourcesManager) {
        ResourcesManagerScreen(onBack = { showResourcesManager = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("路由 / DNS") })
        },
        floatingActionButton = {
            Box(Modifier.padding(bottom = FabBottomBarClearance)) {
                when (tab) {
                    0 -> ExtendedFloatingActionButton(
                        onClick = { editingRouteRule = RouteRule() },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("添加路由规则") },
                    )
                    else -> ExtendedFloatingActionButton(
                        onClick = { editingDnsServer = DnsServer() },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("添加 DNS") },
                    )
                }
            }
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("路由/DNS 规则") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("DNS 设置") })
            }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { page ->
                when (page) {
                    0 -> PageRouteAndDnsRules(
                        routeRules, routeRuleSets, dnsRules, autoDnsRules, autoRouteRules,
                        dnsServers, dnsGroups,
                        onToggleRoute = { store.upsertRouteRule(it.copy(enabled = !it.enabled)) },
                        onEditRoute = { editingRouteRule = it },
                        onDeleteRoute = { store.deleteRouteRule(it.id) },
                        onReorderRoute = { ids -> store.reorderRouteRules(ids) },
                        onToggleDns = { store.upsertDnsRule(it.copy(enabled = !it.enabled)) },
                        onEditDns = { editingDnsRule = it },
                        onDeleteDns = { store.deleteDnsRule(it.id) },
                        onReorderDns = { ids -> store.reorderDnsRules(ids) },
                        onOpenRuleSetManager = { showRouteRuleSetManager = true },
                        stateResources = resources,
                        onOpenResourcesManager = { showResourcesManager = true },
                        tokens = tokens,
                    )
                    else -> PageDnsSettings(
                        dnsServers, dnsGroups,
                        onToggleServer = { store.upsertDnsServer(it.copy(enabled = !it.enabled)) },
                        onEditServer = { editingDnsServer = it },
                        onEditGroup = { editingDnsGroup = it },
                        tokens = tokens,
                    )
                }
            }
        }
    }

    if (showRouteRuleSetManager) {
        RuleSetManagerDialog(
            ruleSets = routeRuleSets,
            onDismiss = { showRouteRuleSetManager = false },
            onAdd = { editingRouteRuleSet = RouteRuleSet() },
            onEdit = { editingRouteRuleSet = it },
            onDelete = { store.deleteRuleSet(it.id) },
            onToggle = { store.upsertRuleSet(it.copy(enabled = !it.enabled)) },
        )
    }
}

// ---------------------------------------------------------------------------
// 第 1 页：路由规则（上）+ DNS 规则（下），两段各占一半、独立拖拽排序
// ---------------------------------------------------------------------------

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageRouteAndDnsRules(
    routeRules: List<RouteRule>,
    routeRuleSets: List<RouteRuleSet>,
    dnsRules: List<DnsRule>,
    autoDnsRules: List<DnsRule>,
    autoRouteRules: List<RouteRule>,
    dnsServers: List<DnsServer>,
    dnsGroups: List<DnsGroup>,
    onToggleRoute: (RouteRule) -> Unit,
    onEditRoute: (RouteRule) -> Unit,
    onDeleteRoute: (RouteRule) -> Unit,
    onReorderRoute: (List<String>) -> Unit,
    onToggleDns: (DnsRule) -> Unit,
    onEditDns: (DnsRule) -> Unit,
    onDeleteDns: (DnsRule) -> Unit,
    onReorderDns: (List<String>) -> Unit,
    onOpenRuleSetManager: () -> Unit,
    stateResources: List<com.sbai.data.Resource>,
    onOpenResourcesManager: () -> Unit,
    tokens: com.sbai.ui.theme.SbStyleTokens,
) {
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = tokens.screenHorizontalPadding),
    ) {
        Spacer(Modifier.height(8.dp))

        // ---- 上：路由规则 ----
        Text(
            "路由规则 · 越靠上优先级越高（长按卡片拖动排序）",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        DragDropLazyColumn(
            items = routeRules,
            keyOf = { it.id },
            onMove = { from, to ->
                val ids = routeRules.map { it.id }.toMutableList()
                if (from in ids.indices && to in ids.indices) {
                    val moved = ids.removeAt(from)
                    ids.add(to, moved)
                    onReorderRoute(ids)
                }
            },
            onDragEnd = { keys -> onReorderRoute(keys.map { it.toString() }) },
            modifier = Modifier.weight(1f),
            contentBottomPadding = 8.dp,
            header = {
                SbGroup(title = "") {
                    item {
                        SbItem(
                            title = "规则集（${routeRuleSets.size}）",
                            subtitle = "被路由规则按 tag 引用；本身不参与匹配顺序",
                            icon = Icons.Filled.Dataset,
                            onClick = onOpenRuleSetManager,
                        )
                    }
                    item {
                        SbItem(
                            title = "IP 列表资源（${stateResources.size}）",
                            subtitle = "CHINA_IP 等资源自动注入路由；管理更新源",
                            icon = Icons.Filled.Settings,
                            onClick = onOpenResourcesManager,
                        )
                    }
                    if (routeRules.isEmpty()) {
                        item {
                            SbItem(
                                title = "暂无路由规则",
                                subtitle = "点右下角「添加路由规则」；域名/IP 一行一条",
                            )
                        }
                    }
                    if (autoRouteRules.isNotEmpty()) {
                        item {
                            SbItem(
                                title = "自动 · fakeIP 段（只读）",
                                subtitle = autoRouteRules.first().ipCidrs.joinToString(),
                                trailing = { SbBadge("自动", MaterialTheme.colorScheme.tertiary) },
                            )
                        }
                    }
                }
            },
            footer = { Spacer(Modifier.height(12.dp)) },
        ) { index, rule, isDragging ->
            RouteRuleCard(index + 1, rule, isDragging,
                onToggle = { onToggleRoute(rule) },
                onEdit = { onEditRoute(rule) },
                onDelete = { onDeleteRoute(rule) })
        }

        HorizontalDivider(Modifier.padding(vertical = 8.dp))

        // ---- 下：DNS 规则 ----
        Text(
            "DNS 规则 · 越靠上优先级越高（长按卡片拖动排序）",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
        DragDropLazyColumn(
            items = dnsRules,
            keyOf = { it.id },
            onMove = { from, to ->
                val ids = dnsRules.map { it.id }.toMutableList()
                if (from in ids.indices && to in ids.indices) {
                    val moved = ids.removeAt(from)
                    ids.add(to, moved)
                    onReorderDns(ids)
                }
            },
            onDragEnd = { keys -> onReorderDns(keys.map { it.toString() }) },
            modifier = Modifier.weight(1f),
            contentBottomPadding = BottomBarClearance,
            header = {
                if (dnsRules.isEmpty()) {
                    SbGroup(title = "") {
                        item {
                            SbItem(
                                title = "暂无手动 DNS 规则",
                                subtitle = "路由规则指定的 DNS 会自动生成规则（见下方）",
                                icon = Icons.Filled.Rule,
                            )
                        }
                    }
                }
            },
            footer = {
                if (autoDnsRules.isNotEmpty()) {
                    Text(
                        "自动生成的规则（由路由规则推导，只读）",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                    autoDnsRules.forEach { rule ->
                        AutoDnsRuleRow(rule)
                        Spacer(Modifier.height(6.dp))
                    }
                }
                Spacer(Modifier.height(20.dp))
            },
        ) { _, rule, isDragging ->
            ManualDnsRuleRow(rule, isDragging,
                onToggle = { onToggleDns(rule) },
                onEdit = { onEditDns(rule) },
                onDelete = { onDeleteDns(rule) })
            Spacer(Modifier.height(8.dp))
        }
    }
}

// ---------------------------------------------------------------------------
// 第 2 页：DNS 服务器 + DNS group
// ---------------------------------------------------------------------------

@Composable
private fun PageDnsSettings(
    dnsServers: List<DnsServer>,
    dnsGroups: List<DnsGroup>,
    onToggleServer: (DnsServer) -> Unit,
    onEditServer: (DnsServer) -> Unit,
    onEditGroup: (DnsGroup) -> Unit,
    tokens: com.sbai.ui.theme.SbStyleTokens,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = tokens.screenHorizontalPadding),
        contentPadding = PaddingValues(bottom = BottomBarClearance),
    ) {
        item { Spacer(Modifier.height(16.dp)) }
        item {
            Text("DNS 服务器（${dnsServers.size}）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
        item {
            SbGroup(title = "") {
                dnsServers.forEach { server ->
                    item {
                        SbItem(
                            title = server.tag.ifBlank { "（未命名）" },
                            subtitle = buildString {
                                append(server.type.wireName)
                                if (server.address.isNotBlank()) append(" · ${server.address}")
                                server.detour?.let { append(" · 出口:$it") } ?: append(" · 无出口")
                            },
                            icon = Icons.Filled.Dns,
                            onClick = { onEditServer(server) },
                            trailing = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (server.echEnabled) {
                                        SbBadge("ECH", MaterialTheme.colorScheme.tertiary)
                                        Spacer(Modifier.size(6.dp))
                                    }
                                    server.clientSubnet?.takeIf { it.isNotBlank() }?.let {
                                        SbBadge("ECS", MaterialTheme.colorScheme.secondary)
                                        Spacer(Modifier.size(6.dp))
                                    }
                                    Switch(checked = server.enabled, onCheckedChange = { onToggleServer(server) })
                                }
                            },
                        )
                    }
                }
                if (dnsServers.isEmpty()) {
                    item {
                        SbItem(
                            title = "暂无 DNS 服务器",
                            subtitle = "添加后可在路由规则 / DNS 规则中引用；出口可留空",
                            icon = Icons.Filled.Dns,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
        item {
            Text("DNS Group（${dnsGroups.size}）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
            )
        }
        item {
            SbGroup(title = "") {
                dnsGroups.forEach { group ->
                    item {
                        SbItem(
                            title = group.name.ifBlank { "（未命名）" },
                            subtitle = group.serverTags.joinToString().ifBlank { "（空 group）" },
                            icon = Icons.Filled.GroupWork,
                            onClick = { onEditGroup(group) },
                        )
                    }
                }
                if (dnsGroups.isEmpty()) {
                    item {
                        SbItem(
                            title = "暂无 DNS Group",
                            subtitle = "group 是一组 DNS 的命名集合，供路由/DNS 规则引用",
                            icon = Icons.Filled.GroupWork,
                        )
                    }
                }
            }
        }
        item { Spacer(Modifier.height(24.dp)) }
    }
}

// ---------------------------------------------------------------------------
// 卡片 / 汇总函数（复用原逻辑）
// ---------------------------------------------------------------------------

@Composable
private fun RouteRuleCard(index: Int, rule: RouteRule, isDragging: Boolean,
    onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    SbGroup(title = "") {
        item {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.DragIndicator, contentDescription = "长按拖动排序",
                        tint = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("#$index", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.size(8.dp))
                    ActionBadge(rule.action)
                    Spacer(Modifier.size(8.dp))
                    Text(rule.name.ifBlank { ruleSummary(rule) }, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f), maxLines = 1)
                    Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
                }
                Text(ruleSummary(rule), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 3, modifier = Modifier.padding(start = 36.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 28.dp)) {
                    SbBadge(rule.logic.displayName, MaterialTheme.colorScheme.tertiary)
                    if (rule.invert) { Spacer(Modifier.size(6.dp)); SbBadge("invert", MaterialTheme.colorScheme.tertiary) }
                    rule.dnsTag?.let { Spacer(Modifier.size(6.dp)); SbBadge("DNS:$it", MaterialTheme.colorScheme.secondary) }
                    if (!rule.ipv4 || !rule.ipv6) { Spacer(Modifier.size(6.dp)); SbBadge(if (rule.ipv4) "仅IPv4" else "仅IPv6", MaterialTheme.colorScheme.secondary) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onEdit) { Text("编辑") }
                    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun ActionBadge(action: RuleAction) {
    val (label, color) = when (action) {
        RuleAction.ROUTE_PROXY -> "代理" to MaterialTheme.colorScheme.primary
        RuleAction.ROUTE_DIRECT -> "直连" to MaterialTheme.colorScheme.secondary
        RuleAction.REJECT -> "拦截" to MaterialTheme.colorScheme.error
        RuleAction.SNIFF -> "嗅探" to MaterialTheme.colorScheme.tertiary
        RuleAction.RESOLVE -> "解析" to MaterialTheme.colorScheme.tertiary
        RuleAction.HIJACK_DNS -> "劫持DNS" to MaterialTheme.colorScheme.tertiary
        RuleAction.ROUTE_OPTIONS -> "路由选项" to MaterialTheme.colorScheme.tertiary
    }
    SbBadge(label, color)
}

private fun ruleSummary(rule: RouteRule): String = buildList {
    if (rule.domains.isNotEmpty()) add("域名×${rule.domains.size}")
    if (rule.domainSuffixes.isNotEmpty()) add("后缀×${rule.domainSuffixes.size}")
    if (rule.domainKeywords.isNotEmpty()) add("关键词×${rule.domainKeywords.size}")
    if (rule.domainRegexes.isNotEmpty()) add("正则×${rule.domainRegexes.size}")
    if (rule.ipCidrs.isNotEmpty()) add("IP×${rule.ipCidrs.size}")
    if (rule.ruleSetTags.isNotEmpty()) add("规则集[${rule.ruleSetTags.joinToString()}]")
    if (rule.networks.isNotEmpty()) add("network:${rule.networks.joinToString()}")
    if (rule.protocols.isNotEmpty()) add("protocol:${rule.protocols.joinToString()}")
    if (rule.ports.isNotEmpty()) add("port:${rule.ports.joinToString()}")
}.joinToString(" ").ifBlank { "（空规则 = 匹配全部）" }

@Composable
private fun ManualDnsRuleRow(rule: DnsRule, isDragging: Boolean,
    onToggle: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    SbGroup(title = "") {
        item {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.DragIndicator, contentDescription = "长按拖动排序",
                        tint = if (isDragging) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(rule.name.ifBlank { dnsRuleSummary(rule) }, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                        Text("→ ${rule.server.ifBlank { "（未指定）" }}${if (rule.ipStrategy.isNotBlank()) " · ${rule.ipStrategy}" else ""}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
                }
                Text(dnsRuleSummary(rule), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, modifier = Modifier.padding(start = 36.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 28.dp)) {
                    if (rule.disableCache) SbBadge("禁缓存", MaterialTheme.colorScheme.tertiary)
                    rule.rewriteTtl?.let { Spacer(Modifier.size(6.dp)); SbBadge("TTL=$it", MaterialTheme.colorScheme.tertiary) }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onEdit) { Text("编辑") }
                    IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error) }
                }
            }
        }
    }
}

@Composable
private fun AutoDnsRuleRow(rule: DnsRule) {
    SbGroup(title = "") {
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(rule.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text("${dnsRuleSummary(rule)} → ${rule.server}" +
                        if (rule.ipStrategy.isNotBlank()) " · ${rule.ipStrategy}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                }
                SbBadge("自动", MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

private fun dnsRuleSummary(rule: DnsRule): String = buildList {
    if (rule.domains.isNotEmpty()) add("域名×${rule.domains.size}")
    if (rule.domainSuffixes.isNotEmpty()) add("后缀×${rule.domainSuffixes.size}")
    if (rule.domainKeywords.isNotEmpty()) add("关键词×${rule.domainKeywords.size}")
    if (rule.domainRegexes.isNotEmpty()) add("正则×${rule.domainRegexes.size}")
    if (rule.ruleSetTags.isNotEmpty()) add("规则集[${rule.ruleSetTags.joinToString()}]")
    if (rule.ipCidrs.isNotEmpty()) add("IP×${rule.ipCidrs.size}")
    if (rule.networks.isNotEmpty()) add("network:${rule.networks.joinToString()}")
    if (rule.ports.isNotEmpty()) add("port:${rule.ports.joinToString()}")
    if (rule.queryTypes.isNotEmpty()) add("type:${rule.queryTypes.joinToString()}")
}.joinToString(" ").ifBlank { "（匹配全部查询）" }

// ---------------------------------------------------------------------------
// 路由规则编辑器（二级页面，全量字段）
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RouteRuleEditorDialog(
    initial: RouteRule,
    dnsOptions: List<String>,
    ruleSetTags: List<String>,
    onDismiss: () -> Unit,
    onSave: (RouteRule) -> Unit,
    onCreateRuleSet: () -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var action by remember { mutableStateOf(initial.action) }
    var domains by remember { mutableStateOf(initial.domains.joinToString("\n")) }
    var suffixes by remember { mutableStateOf(initial.domainSuffixes.joinToString("\n")) }
    var keywords by remember { mutableStateOf(initial.domainKeywords.joinToString("\n")) }
    var regexes by remember { mutableStateOf(initial.domainRegexes.joinToString("\n")) }
    var ipCidrs by remember { mutableStateOf(initial.ipCidrs.joinToString("\n")) }
    var sets by remember { mutableStateOf(initial.ruleSetTags.joinToString("\n")) }
    var networks by remember { mutableStateOf(initial.networks.toSet()) }
    var protocols by remember { mutableStateOf(initial.protocols.toSet()) }
    var ports by remember { mutableStateOf(initial.ports.joinToString("\n")) }
    var portRanges by remember { mutableStateOf(initial.portRanges.joinToString("\n")) }
    var sourceIpCidrs by remember { mutableStateOf(initial.sourceIpCidrs.joinToString("\n")) }
    var sourcePorts by remember { mutableStateOf(initial.sourcePorts.joinToString("\n")) }
    var sourcePortRanges by remember { mutableStateOf(initial.sourcePortRanges.joinToString("\n")) }
    var packageNames by remember { mutableStateOf(initial.packageNames.joinToString("\n")) }
    var processNames by remember { mutableStateOf(initial.processNames.joinToString("\n")) }
    var processPaths by remember { mutableStateOf(initial.processPaths.joinToString("\n")) }
    var users by remember { mutableStateOf(initial.users.joinToString("\n")) }
    var userIds by remember { mutableStateOf(initial.userIds.joinToString("\n")) }
    var networkTypes by remember { mutableStateOf(initial.networkTypes.toSet()) }
    var wifiSsids by remember { mutableStateOf(initial.wifiSsids.joinToString("\n")) }
    var wifiBssids by remember { mutableStateOf(initial.wifiBssids.joinToString("\n")) }
    var inbounds by remember { mutableStateOf(initial.inbounds.joinToString("\n")) }
    var clashMode by remember { mutableStateOf(initial.clashMode) }
    var sourceIpIsPrivate by remember { mutableStateOf(initial.sourceIpIsPrivate) }
    var ipIsPrivate by remember { mutableStateOf(initial.ipIsPrivate) }
    var networkIsExpensive by remember { mutableStateOf(initial.networkIsExpensive) }
    var rejectMethod by remember { mutableStateOf(initial.rejectMethod) }
    var logic by remember { mutableStateOf(initial.logic) }
    var invert by remember { mutableStateOf(initial.invert) }
    var ipv4 by remember { mutableStateOf(initial.ipv4) }
    var ipv6 by remember { mutableStateOf(initial.ipv6) }
    var dnsTag by remember { mutableStateOf(initial.dnsTag ?: "") }
    var showAppPicker by remember { mutableStateOf(false) }
    var showJsonPaste by remember { mutableStateOf(false) }
    var jsonError by remember { mutableStateOf<String?>(null) }

    val isBlock = action == RuleAction.REJECT
    val hasDomains = listOf(domains, suffixes, keywords, regexes).any { it.isNotBlank() }
    val ipOnly = !hasDomains && (ipCidrs.isNotBlank() || sets.isNotBlank())

    fun applyAll(r: RouteRule) {
        name = r.name; action = r.action
        domains = r.domains.joinToString("\n"); suffixes = r.domainSuffixes.joinToString("\n")
        keywords = r.domainKeywords.joinToString("\n"); regexes = r.domainRegexes.joinToString("\n")
        ipCidrs = r.ipCidrs.joinToString("\n"); sets = r.ruleSetTags.joinToString("\n")
        networks = r.networks.toSet(); protocols = r.protocols.toSet()
        ports = r.ports.joinToString("\n"); portRanges = r.portRanges.joinToString("\n")
        sourceIpCidrs = r.sourceIpCidrs.joinToString("\n"); sourcePorts = r.sourcePorts.joinToString("\n")
        sourcePortRanges = r.sourcePortRanges.joinToString("\n")
        packageNames = r.packageNames.joinToString("\n"); processNames = r.processNames.joinToString("\n")
        processPaths = r.processPaths.joinToString("\n"); users = r.users.joinToString("\n")
        userIds = r.userIds.joinToString("\n"); networkTypes = r.networkTypes.toSet()
        wifiSsids = r.wifiSsids.joinToString("\n"); wifiBssids = r.wifiBssids.joinToString("\n")
        inbounds = r.inbounds.joinToString("\n"); clashMode = r.clashMode
        sourceIpIsPrivate = r.sourceIpIsPrivate; ipIsPrivate = r.ipIsPrivate
        networkIsExpensive = r.networkIsExpensive; rejectMethod = r.rejectMethod
        logic = r.logic; invert = r.invert; ipv4 = r.ipv4; ipv6 = r.ipv6
        dnsTag = r.dnsTag ?: ""
    }

    fun doSave() {
        onSave(initial.copy(
            name = name.trim(), action = action,
            domains = domains.toLines(), domainSuffixes = suffixes.toLines(),
            domainKeywords = keywords.toLines(), domainRegexes = regexes.toLines(),
            ipCidrs = ipCidrs.toLines(), ruleSetTags = sets.toLines(),
            networks = networks.toList().sorted(), protocols = protocols.toList().sorted(),
            ports = ports.toLines().mapNotNull { it.toIntOrNull() }, portRanges = portRanges.toLines(),
            sourceIpCidrs = sourceIpCidrs.toLines(), sourcePorts = sourcePorts.toLines().mapNotNull { it.toIntOrNull() },
            sourcePortRanges = sourcePortRanges.toLines(),
            packageNames = packageNames.toLines(), processNames = processNames.toLines(),
            processPaths = processPaths.toLines(), users = users.toLines(),
            userIds = userIds.toLines().mapNotNull { it.toIntOrNull() },
            networkTypes = networkTypes.toList().sorted(),
            wifiSsids = wifiSsids.toLines(), wifiBssids = wifiBssids.toLines(),
            inbounds = inbounds.toLines(), clashMode = clashMode.trim(),
            sourceIpIsPrivate = sourceIpIsPrivate, ipIsPrivate = ipIsPrivate,
            networkIsExpensive = networkIsExpensive, rejectMethod = rejectMethod,
            logic = logic, invert = invert, ipv4 = ipv4, ipv6 = ipv6,
            dnsTag = dnsTag.ifBlank { null },
        ))
    }

    BackHandler(enabled = true) { BottomBarController.show(); onDismiss() }
    RestoreBottomBarOnDispose()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial.name.isBlank() && initial.domains.isEmpty()) "添加路由规则" else "编辑路由规则") },
                navigationIcon = { IconButton(onClick = { BottomBarController.show(); onDismiss() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
                actions = { TextButton(onClick = { doSave() }) { Text("保存") } },
            )
        },
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("规则名称（可选）") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.size(8.dp))
                IconButton(onClick = { showJsonPaste = true }) { Icon(Icons.Filled.ContentPaste, contentDescription = "粘贴 JSON") }
            }
            Text("动作", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { RuleAction.entries.forEach { a -> FilterChip(selected = action == a, onClick = { action = a }, label = { Text(a.displayName) }) } }
            if (isBlock) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("拦截方式：", style = MaterialTheme.typography.labelLarge)
                    Spacer(Modifier.size(8.dp))
                    FilterChip(selected = rejectMethod == "default", onClick = { rejectMethod = "default" }, label = { Text("拒绝") })
                    Spacer(Modifier.size(6.dp))
                    FilterChip(selected = rejectMethod == "drop", onClick = { rejectMethod = "drop" }, label = { Text("丢弃") })
                }
            }
            EditorSection("域名") { MultiLineField("域名（一行一条）", domains) { domains = it }; MultiLineField("域名后缀（一行一条）", suffixes) { suffixes = it }; MultiLineField("域名关键词（一行一条）", keywords) { keywords = it }; MultiLineField("域名正则（一行一条）", regexes) { regexes = it } }
            EditorSection("目标 IP / 规则集") { MultiLineField("目标 IP / CIDR（一行一条）", ipCidrs) { ipCidrs = it }; MultiLineField("规则集 tag 或 URL（一行一条；URL 自动建远程规则集）", sets) { sets = it }; TextButton(onClick = onCreateRuleSet) { Text("＋ 新建规则集") }; Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = ipIsPrivate, onCheckedChange = { ipIsPrivate = it }); Text("目标 IP 是私有地址（ip_is_private）") } }
            EditorSection("源（发起方）") { MultiLineField("源 IP / CIDR（一行一条）", sourceIpCidrs) { sourceIpCidrs = it }; MultiLineField("源端口（一行一个）", sourcePorts) { sourcePorts = it }; MultiLineField("源端口段（一行一条，如 8000:9000）", sourcePortRanges) { sourcePortRanges = it }; Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = sourceIpIsPrivate, onCheckedChange = { sourceIpIsPrivate = it }); Text("源 IP 是私有地址") } }
            EditorSection("传输") {
                Text("network（可多选：tcp / udp / icmp）", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { NETWORK_OPTIONS.forEach { opt -> FilterChip(selected = opt in networks, onClick = { networks = if (opt in networks) networks - opt else networks + opt }, label = { Text(opt) }) } }
                Text("protocol（可多选，依赖 sniff）", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { PROTOCOL_OPTIONS.forEach { opt -> FilterChip(selected = opt in protocols, onClick = { protocols = if (opt in protocols) protocols - opt else protocols + opt }, label = { Text(opt) }) } }
                MultiLineField("目标端口（一行一个）", ports) { ports = it }
                MultiLineField("目标端口段（一行一条，如 8000:9000）", portRanges) { portRanges = it }
            }
            EditorSection("应用 / 进程") {
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("应用包名（${packageNames.toLines().size}）", style = MaterialTheme.typography.labelLarge)
                        Text("无需 root：通过 Android VPN API 解析连接归属 UID", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                        if (packageNames.isNotBlank()) Text(packageNames.toLines().take(3).joinToString(", ") + if (packageNames.toLines().size > 3) " …" else "", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
                    }
                    Button(onClick = { showAppPicker = true }) { Text("选择应用") }
                }
                Text("⚠ 以下进程级字段需要 root 才能在 Android 生效（无 root 时规则不会命中）：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                MultiLineField("进程名 process_name（需 root）", processNames) { processNames = it }
                MultiLineField("进程路径 process_path（需 root）", processPaths) { processPaths = it }
                MultiLineField("用户名 user（需 root）", users) { users = it }
                MultiLineField("用户 ID user_id（需 root）", userIds) { userIds = it }
                Text("无 root 请改用「应用包名」——Android 上包名即进程身份，效果等价。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            EditorSection("网络环境") {
                Text("网络类型（可多选）", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("wifi", "cellular", "ethernet").forEach { opt -> FilterChip(selected = opt in networkTypes, onClick = { networkTypes = if (opt in networkTypes) networkTypes - opt else networkTypes + opt }, label = { Text(opt) }) } }
                MultiLineField("WiFi SSID（一行一条）", wifiSsids) { wifiSsids = it }
                MultiLineField("WiFi BSSID（一行一条）", wifiBssids) { wifiBssids = it }
                MultiLineField("入站 tag（一行一条）", inbounds) { inbounds = it }
                OutlinedTextField(value = clashMode, onValueChange = { clashMode = it }, label = { Text("Clash 模式（可选，如 rule/global/direct）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = networkIsExpensive, onCheckedChange = { networkIsExpensive = it }); Text("计费网络（network_is_expensive）") }
            }
            EditorSection("逻辑运算") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) { RuleLogic.entries.forEachIndexed { i, l -> SegmentedButton(selected = logic == l, onClick = { logic = l }, shape = SegmentedButtonDefaults.itemShape(index = i, count = RuleLogic.entries.size)) { Text(if (l == RuleLogic.AND) "AND" else "OR") } } }
                Text(if (logic == RuleLogic.AND) "AND：上面所有字段类别都要满足（sing-box 单条 rule 的默认语义）" else "OR：各类别条件中任一类满足即命中", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = invert, onCheckedChange = { invert = it }); Text("invert（对整体取反）") }
            }
            EditorSection("IP 版本（影响 DNS 解析策略，默认全选）") {
                Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = ipv4, onCheckedChange = { ipv4 = it }); Text("IPv4"); Spacer(Modifier.padding(8.dp)); Checkbox(checked = ipv6, onCheckedChange = { ipv6 = it }); Text("IPv6") }
            }
            if (!isBlock && !ipOnly) {
                EditorSection("DNS 联动") { DnsDropdown(value = dnsTag, options = dnsOptions, onChange = { dnsTag = it }); Text("选择 DNS / DNS group 后将自动为本规则生成一条 DNS 规则；若同时取消 IPv4/IPv6 之一，也只生成一条合并规则。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            } else if (isBlock) {
                Text("拦截类规则不生成 DNS 联动规则。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                Text("纯 IP / 远程规则集规则不生成 DNS 联动规则。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }

    if (showJsonPaste) JsonPasteDialog(onDismiss = { showJsonPaste = false }, onApply = { text ->
        when (val r = RouteRuleJsonCodec.fromJson(text)) {
            is RouteRuleJsonCodec.ParseResult.Success -> { applyAll(r.rule.copy(id = initial.id)); jsonError = null; showJsonPaste = false }
            is RouteRuleJsonCodec.ParseResult.Failure -> jsonError = r.message
        }
    }, error = jsonError)
    if (showAppPicker) AppPickerDialog(title = "选择应用", selected = packageNames.toLines().toSet(), onDismiss = { showAppPicker = false }, onSave = { pkgs -> packageNames = pkgs.joinToString("\n"); showAppPicker = false })
}

@Composable
private fun EditorSection(title: String, content: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(vertical = 4.dp))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
    }
}

@Composable
private fun MultiLineField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsDropdown(value: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(value = value.ifBlank { "（不指定）" }, onValueChange = {}, readOnly = true, label = { Text("DNS / DNS group（可选）") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("（不指定）") }, onClick = { onChange(""); expanded = false })
            options.forEach { opt -> DropdownMenuItem(text = { Text(opt) }, onClick = { onChange(opt); expanded = false }) }
        }
    }
}

@Composable
private fun JsonPasteDialog(onDismiss: () -> Unit, onApply: (String) -> Unit, error: String?) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("编辑 / 粘贴规则 JSON") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), text = {
        Column {
            Text("可直接输入或粘贴 route.rules 里的单个规则对象（也支持含 rules 的数组 / 完整 route 配置）：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().height(220.dp), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            error?.let { Spacer(Modifier.height(6.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = { onApply(text) }, enabled = text.isNotBlank()) { Text("解析并回填") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

// ---------------------------------------------------------------------------
// 规则集
// ---------------------------------------------------------------------------

@Composable
private fun RuleSetManagerDialog(ruleSets: List<RouteRuleSet>, onDismiss: () -> Unit, onAdd: () -> Unit, onEdit: (RouteRuleSet) -> Unit, onDelete: (RouteRuleSet) -> Unit, onToggle: (RouteRuleSet) -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, title = { Text("规则集") }, text = {
        Column(modifier = Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("规则集是被路由规则引用的资源，本身没有匹配顺序；优先级由引用它的路由规则在列表中的位置决定。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            ruleSets.forEach { rs ->
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(rs.tag.ifBlank { "（未命名）" }, style = MaterialTheme.typography.titleSmall)
                        Text(buildString { append(if (rs.type == RuleSetType.REMOTE) "remote" else "local"); if (!rs.ipv4 || !rs.ipv6) append(if (rs.ipv4) " · 仅IPv4" else " · 仅IPv6") }, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = rs.enabled, onCheckedChange = { onToggle(rs) })
                    TextButton(onClick = { onEdit(rs) }) { Text("编辑") }
                    IconButton(onClick = { onDelete(rs) }) { Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error) }
                }
            }
            if (ruleSets.isEmpty()) Text("暂无规则集。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }, confirmButton = { TextButton(onClick = onAdd) { Icon(Icons.Filled.Add, contentDescription = null); Text("添加规则集") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("关闭") } })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleSetEditorDialog(initial: RouteRuleSet, onDismiss: () -> Unit, onSave: (RouteRuleSet) -> Unit, onDelete: (() -> Unit)?) {
    var tag by remember { mutableStateOf(initial.tag) }
    var type by remember { mutableStateOf(initial.type) }
    var url by remember { mutableStateOf(initial.url) }
    var localContent by remember { mutableStateOf(initial.localContent) }
    var detour by remember { mutableStateOf(initial.downloadDetour ?: "") }
    var ipv4 by remember { mutableStateOf(initial.ipv4) }
    var ipv6 by remember { mutableStateOf(initial.ipv6) }
    var typeExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = true) { BottomBarController.show(); onDismiss() }
    RestoreBottomBarOnDispose()

    fun doSave() {
        if (tag.isBlank()) { error = "tag 不能为空"; return }
        if (type == RuleSetType.LOCAL) {
            val ok = runCatching { Json.parseToJsonElement(localContent).jsonObject }.isSuccess
            if (localContent.isNotBlank() && !ok) { error = "本地规则集 JSON 无效"; return }
        }
        onSave(initial.copy(tag = tag.trim(), type = type, url = url.trim(), localContent = localContent, downloadDetour = detour.ifBlank { null }, ipv4 = ipv4, ipv6 = ipv6))
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (initial.tag.isBlank()) "添加规则集" else "编辑规则集") },
            navigationIcon = { IconButton(onClick = { BottomBarController.show(); onDismiss() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
            actions = {
                if (onDelete != null) { IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = { doSave() }) { Text("保存") }
            },
        )
    }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = tag, onValueChange = { tag = it }, label = { Text("tag（路由规则按此引用）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                OutlinedTextField(value = if (type == RuleSetType.REMOTE) "remote" else "local", onValueChange = {}, readOnly = true, label = { Text("类型") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                    DropdownMenuItem(text = { Text("remote（远程规则集，含 IP 规则集）") }, onClick = { type = RuleSetType.REMOTE; typeExpanded = false })
                    DropdownMenuItem(text = { Text("local（内联）") }, onClick = { type = RuleSetType.LOCAL; typeExpanded = false })
                }
            }
            if (type == RuleSetType.REMOTE) {
                OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = detour, onValueChange = { detour = it }, label = { Text("download_detour（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            } else {
                OutlinedTextField(value = localContent, onValueChange = { localContent = it }, label = { Text("规则集 JSON（source 格式）") }, modifier = Modifier.fillMaxWidth(), minLines = 4, placeholder = { Text("{\"version\":3,\"rules\":[...]}") })
            }
            Text("IP 版本（默认全选；取消任一项将生成 DNS 规则）", style = MaterialTheme.typography.labelLarge)
            Row(verticalAlignment = Alignment.CenterVertically) { Checkbox(checked = ipv4, onCheckedChange = { ipv4 = it }); Text("IPv4"); Spacer(Modifier.padding(8.dp)); Checkbox(checked = ipv6, onCheckedChange = { ipv6 = it }); Text("IPv6") }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }
}

// ---------------------------------------------------------------------------
// DNS 服务器编辑器
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsServerEditorDialog(initial: DnsServer, existingServers: List<String>, existingFakeipTag: String?,
    onDismiss: () -> Unit, onSave: (DnsServer) -> Unit, onDelete: (() -> Unit)?) {
    var tag by remember { mutableStateOf(initial.tag) }
    var type by remember { mutableStateOf(initial.type) }
    var address by remember { mutableStateOf(initial.address) }
    var detour by remember { mutableStateOf(initial.detour ?: "") }
    var resolver by remember { mutableStateOf(initial.addressResolver ?: "") }
    var clientSubnet by remember { mutableStateOf(initial.clientSubnet ?: "") }
    var echEnabled by remember { mutableStateOf(initial.echEnabled) }
    var echConfig by remember { mutableStateOf(initial.echConfig ?: "") }
    var inet4Range by remember { mutableStateOf(initial.inet4Range) }
    var inet6Range by remember { mutableStateOf(initial.inet6Range) }
    var typeExpanded by remember { mutableStateOf(false) }
    var resolverExpanded by remember { mutableStateOf(false) }

    val needsAddress = type !in setOf(DnsServerType.LOCAL, DnsServerType.HOSTS, DnsServerType.FAKEIP)
    val supportsEch = type in ECH_CAPABLE
    val hasOtherFakeip = existingFakeipTag != null && existingFakeipTag != initial.tag

    BackHandler(enabled = true) { BottomBarController.show(); onDismiss() }
    RestoreBottomBarOnDispose()

    fun doSave() {
        if (tag.isBlank()) return
        if (type == DnsServerType.FAKEIP && hasOtherFakeip) return
        onSave(initial.copy(tag = tag.trim(), type = type, address = address.trim(), detour = detour.ifBlank { null },
            addressResolver = resolver.ifBlank { null }, clientSubnet = clientSubnet.ifBlank { null },
            echEnabled = echEnabled && supportsEch, echConfig = echConfig.ifBlank { null },
            inet4Range = inet4Range.trim(), inet6Range = inet6Range.trim()))
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (initial.tag.isBlank()) "添加 DNS" else "编辑 DNS") },
            navigationIcon = { IconButton(onClick = { BottomBarController.show(); onDismiss() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
            actions = {
                if (onDelete != null) { IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = { doSave() }, enabled = tag.isNotBlank() && !(type == DnsServerType.FAKEIP && hasOtherFakeip)) { Text("保存") }
            },
        )
    }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = tag, onValueChange = { tag = it }, label = { Text("tag（唯一标识）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                OutlinedTextField(value = type.wireName, onValueChange = {}, readOnly = true, label = { Text("类型") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) { DnsServerType.entries.forEach { t -> DropdownMenuItem(text = { Text(t.wireName) }, onClick = { type = t; typeExpanded = false }) } }
            }
            if (needsAddress) OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("地址（如 223.5.5.5 / tls://1.1.1.1 / https://dns.google/dns-query）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = detour, onValueChange = { detour = it }, label = { Text("出口（可选，留空 = 不指定）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            ExposedDropdownMenuBox(expanded = resolverExpanded, onExpandedChange = { resolverExpanded = it }) {
                OutlinedTextField(value = resolver.ifBlank { "（不使用）" }, onValueChange = {}, readOnly = true, label = { Text("address_resolver（可选）") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(resolverExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                ExposedDropdownMenu(expanded = resolverExpanded, onDismissRequest = { resolverExpanded = false }) {
                    DropdownMenuItem(text = { Text("（不使用）") }, onClick = { resolver = ""; resolverExpanded = false })
                    existingServers.filter { it != tag }.forEach { s -> DropdownMenuItem(text = { Text(s) }, onClick = { resolver = s; resolverExpanded = false }) }
                }
            }
            OutlinedTextField(value = clientSubnet, onValueChange = { clientSubnet = it }, label = { Text("ECS client_subnet（可选，如 1.0.1.0/24 或 auto）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            if (supportsEch) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = echEnabled, onCheckedChange = { echEnabled = it })
                    Spacer(Modifier.size(8.dp))
                    Column { Text("ECH（Encrypted Client Hello）", style = MaterialTheme.typography.bodyLarge); Text("仅 tls/https/quic/h3 支持", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                if (echEnabled) OutlinedTextField(value = echConfig, onValueChange = { echConfig = it }, label = { Text("ECH config（可选，PEM / echconfiglist）") }, modifier = Modifier.fillMaxWidth(), minLines = 2)
            }
            if (type == DnsServerType.FAKEIP) {
                if (hasOtherFakeip) Text("已存在 fakeIP「$existingFakeipTag」，sing-box 只允许一个 fakeIP。请先删除或改为编辑它。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
                OutlinedTextField(value = inet4Range, onValueChange = { inet4Range = it }, label = { Text("IPv4 段（可选，默认 10.0.0.0/8）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(value = inet6Range, onValueChange = { inet6Range = it }, label = { Text("IPv6 段（可选，默认 fc00::/18）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

// ---------------------------------------------------------------------------
// DNS group 编辑器
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DnsGroupEditorDialog(initial: DnsGroup, serverTags: List<String>, onDismiss: () -> Unit,
    onSave: (DnsGroup) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(initial.name) }
    var selected by remember { mutableStateOf(initial.serverTags.toSet()) }

    BackHandler(enabled = true) { BottomBarController.show(); onDismiss() }
    RestoreBottomBarOnDispose()

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (initial.name.isBlank()) "添加 DNS group" else "编辑 DNS group") },
            navigationIcon = { IconButton(onClick = { BottomBarController.show(); onDismiss() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
            actions = {
                if (onDelete != null) { IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = { if (name.isNotBlank()) { onSave(initial.copy(name = name.trim(), serverTags = selected.toList().sorted())); onDismiss() } }) { Text("保存") }
            },
        )
    }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("group 名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("成员 DNS", style = MaterialTheme.typography.labelLarge)
            if (serverTags.isEmpty()) Text("尚无可用 DNS，请先添加。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            else FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { serverTags.forEach { tag -> FilterChip(selected = tag in selected, onClick = { selected = if (tag in selected) selected - tag else selected + tag }, label = { Text(tag) }) } }
        }
    }
}

// ---------------------------------------------------------------------------
// DNS 规则编辑器
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DnsRuleEditorDialog(initial: DnsRule, serverOptions: List<String>, ruleSetTags: List<String>,
    onDismiss: () -> Unit, onSave: (DnsRule) -> Unit, onDelete: (() -> Unit)?) {
    var name by remember { mutableStateOf(initial.name) }
    var domains by remember { mutableStateOf(initial.domains.joinToString("\n")) }
    var suffixes by remember { mutableStateOf(initial.domainSuffixes.joinToString("\n")) }
    var keywords by remember { mutableStateOf(initial.domainKeywords.joinToString("\n")) }
    var regexes by remember { mutableStateOf(initial.domainRegexes.joinToString("\n")) }
    var ipCidrs by remember { mutableStateOf(initial.ipCidrs.joinToString("\n")) }
    var sets by remember { mutableStateOf(initial.ruleSetTags.joinToString("\n")) }
    var networks by remember { mutableStateOf(initial.networks.joinToString("\n")) }
    var portsText by remember { mutableStateOf(initial.ports.joinToString("\n")) }
    var queryTypes by remember { mutableStateOf(initial.queryTypes.toSet()) }
    var server by remember { mutableStateOf(initial.server) }
    var ipStrategy by remember { mutableStateOf(initial.ipStrategy) }
    var disableCache by remember { mutableStateOf(initial.disableCache) }
    var rewriteTtl by remember { mutableStateOf(initial.rewriteTtl?.toString() ?: "") }
    var clientSubnet by remember { mutableStateOf(initial.clientSubnet ?: "") }
    var ruleAction by remember { mutableStateOf(initial.action.ifBlank { "route" }) }
    var rcode by remember { mutableStateOf(initial.rcode) }
    var answers by remember { mutableStateOf(initial.answers.joinToString("\n")) }
    var ns by remember { mutableStateOf(initial.ns.joinToString("\n")) }
    var extra by remember { mutableStateOf(initial.extra.joinToString("\n")) }
    var timeout by remember { mutableStateOf(initial.timeout) }
    var serverExpanded by remember { mutableStateOf(false) }
    var strategyExpanded by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showJsonPaste by remember { mutableStateOf(false) }
    var jsonError by remember { mutableStateOf<String?>(null) }

    fun applyAll(r: DnsRule) {
        name = r.name
        domains = r.domains.joinToString("\n"); suffixes = r.domainSuffixes.joinToString("\n")
        keywords = r.domainKeywords.joinToString("\n"); regexes = r.domainRegexes.joinToString("\n")
        ipCidrs = r.ipCidrs.joinToString("\n"); sets = r.ruleSetTags.joinToString("\n")
        networks = r.networks.joinToString("\n"); portsText = r.ports.joinToString("\n")
        queryTypes = r.queryTypes.toSet(); server = r.server
        ipStrategy = r.ipStrategy; disableCache = r.disableCache
        rewriteTtl = r.rewriteTtl?.toString() ?: ""
        clientSubnet = r.clientSubnet ?: ""
        ruleAction = r.action.ifBlank { "route" }; rcode = r.rcode
        answers = r.answers.joinToString("\n"); ns = r.ns.joinToString("\n")
        extra = r.extra.joinToString("\n"); timeout = r.timeout
    }

    BackHandler(enabled = true) { BottomBarController.show(); onDismiss() }
    RestoreBottomBarOnDispose()

    fun doSave() {
        if (ruleAction == "route" && server.isBlank()) { error = "route 动作必须选择目标 DNS / group"; return }
        onSave(initial.copy(name = name.trim(), domains = domains.toLines(), domainSuffixes = suffixes.toLines(),
            domainKeywords = keywords.toLines(), domainRegexes = regexes.toLines(),
            ipCidrs = ipCidrs.toLines(), ruleSetTags = sets.toLines(),
            networks = networks.toLines(), ports = portsText.toLines().mapNotNull { it.toIntOrNull() },
            queryTypes = queryTypes.toList().sorted(), server = server, ipStrategy = ipStrategy,
            disableCache = disableCache, rewriteTtl = rewriteTtl.toIntOrNull(),
            clientSubnet = clientSubnet.ifBlank { null }, action = ruleAction, rcode = rcode,
            answers = answers.toLines(), ns = ns.toLines(), extra = extra.toLines(), timeout = timeout.trim()))
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (initial.autoFromRouteRuleId != null) "DNS 规则（自动）" else if (initial.name.isBlank()) "添加 DNS 规则" else "编辑 DNS 规则") },
            navigationIcon = { IconButton(onClick = { BottomBarController.show(); onDismiss() }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回") } },
            actions = {
                if (onDelete != null) { IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error) } }
                TextButton(onClick = { doSave() }) { Text("保存") }
            },
        )
    }) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (initial.autoFromRouteRuleId != null) Text("此规则由路由规则自动生成，只读；如需自定义请创建手动规则（手动规则优先级更高）。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.tertiary)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("规则名称") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.size(8.dp))
                Button(onClick = { showJsonPaste = true }) { Icon(Icons.Filled.ContentPaste, contentDescription = null); Text("粘贴 JSON") }
            }
            MultiLineField("域名（一行一条）", domains) { domains = it }
            MultiLineField("域名后缀（一行一条）", suffixes) { suffixes = it }
            MultiLineField("域名关键词（一行一条）", keywords) { keywords = it }
            MultiLineField("域名正则（一行一条）", regexes) { regexes = it }
            MultiLineField("IP / CIDR（一行一条）", ipCidrs) { ipCidrs = it }
            MultiLineField("规则集 tag（一行一条）${if (ruleSetTags.isNotEmpty()) "，已有：${ruleSetTags.joinToString()}" else ""}", sets) { sets = it }
            MultiLineField("network（一行一条，tcp/udp/icmp）", networks) { networks = it }
            MultiLineField("端口（一行一个）", portsText) { portsText = it }
            Text("查询类型（可多选）", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { DNS_QUERY_TYPES.forEach { qt -> FilterChip(selected = qt in queryTypes, onClick = { queryTypes = if (qt in queryTypes) queryTypes - qt else queryTypes + qt }, label = { Text(qt) }) } }
            Text("动作", style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("route" to "路由", "route-options" to "改写应答", "reject" to "拒绝", "pre-defined" to "预定义")
                    .forEachIndexed { i, (v, label) -> SegmentedButton(selected = ruleAction == v, onClick = { ruleAction = v }, shape = SegmentedButtonDefaults.itemShape(index = i, count = 4)) { Text(label) } }
            }
            if (ruleAction == "route") {
                ExposedDropdownMenuBox(expanded = serverExpanded, onExpandedChange = { serverExpanded = it }) {
                    OutlinedTextField(value = server.ifBlank { "（必选）" }, onValueChange = {}, readOnly = true, label = { Text("目标 DNS / group") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(serverExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = serverExpanded, onDismissRequest = { serverExpanded = false }) { serverOptions.forEach { opt -> DropdownMenuItem(text = { Text(opt) }, onClick = { server = opt; serverExpanded = false }) } }
                }
            }
            if (ruleAction == "reject" || ruleAction == "route-options") {
                Text("rcode", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { listOf("success", "refused", "formerror", "notimp", "nxdomain").forEach { rc -> FilterChip(selected = rcode == rc, onClick = { rcode = if (rcode == rc) "" else rc }, label = { Text(rc) }) } }
            }
            if (ruleAction == "route-options") {
                MultiLineField("覆盖应答 A 记录（一行一个 IPv4）", answers) { answers = it }
                MultiLineField("覆盖应答 NS 记录（一行一条）", ns) { ns = it }
                MultiLineField("覆盖应答 EXTRA 记录（一行一条）", extra) { extra = it }
            }
            if (ruleAction == "route" || ruleAction == "route-options") {
                ExposedDropdownMenuBox(expanded = strategyExpanded, onExpandedChange = { strategyExpanded = it }) {
                    OutlinedTextField(value = ipStrategy.ifBlank { "（不设置）" }, onValueChange = {}, readOnly = true, label = { Text("IP 解析策略（可选）") }, trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(strategyExpanded) }, modifier = Modifier.menuAnchor().fillMaxWidth())
                    ExposedDropdownMenu(expanded = strategyExpanded, onDismissRequest = { strategyExpanded = false }) { IP_STRATEGIES.forEach { s -> DropdownMenuItem(text = { Text(if (s.isBlank()) "（不设置）" else s) }, onClick = { ipStrategy = s; strategyExpanded = false }) } }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) { Switch(checked = disableCache, onCheckedChange = { disableCache = it }); Spacer(Modifier.size(8.dp)); Text("禁用缓存（disable_cache）") }
            OutlinedTextField(value = rewriteTtl, onValueChange = { rewriteTtl = it }, label = { Text("rewrite_ttl（秒，可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = clientSubnet, onValueChange = { clientSubnet = it }, label = { Text("本条规则 ECS client_subnet（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = timeout, onValueChange = { timeout = it }, label = { Text("查询超时（如 4s，可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    if (showJsonPaste) DnsJsonPasteDialog(onDismiss = { showJsonPaste = false }, onApply = { text ->
        when (val r = DnsRuleJsonCodec.fromJson(text)) {
            is DnsRuleJsonCodec.ParseResult.Success -> { applyAll(r.rule.copy(id = initial.id, autoFromRouteRuleId = initial.autoFromRouteRuleId)); jsonError = null; showJsonPaste = false }
            is DnsRuleJsonCodec.ParseResult.Failure -> jsonError = r.message
        }
    }, error = jsonError)
}

@Composable
private fun DnsJsonPasteDialog(onDismiss: () -> Unit, onApply: (String) -> Unit, error: String?) {
    var text by remember { mutableStateOf("") }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("粘贴 DNS 规则 JSON 片段") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp), text = {
        Column {
            Text("只需 dns.rules 里的单个规则对象，例如：\n{\"domain_suffix\":[\"example.com\"],\"server\":\"dns-remote\"}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = text, onValueChange = { text = it }, modifier = Modifier.fillMaxWidth().height(220.dp), textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace))
            error?.let { Spacer(Modifier.height(6.dp)); Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }, confirmButton = { TextButton(onClick = { onApply(text) }, enabled = text.isNotBlank()) { Text("解析并回填") } }, dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } })
}

private fun String.toLines(): List<String> =
    lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
