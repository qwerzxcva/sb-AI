package com.sbai.ui.dns

import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.DragIndicator
import androidx.compose.material.icons.filled.GroupWork
import androidx.compose.material.icons.filled.Rule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
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
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sbai.ui.components.BottomBarController
import com.sbai.data.DnsGroup
import com.sbai.data.DnsRule
import com.sbai.data.DnsServer
import com.sbai.data.DnsServerType
import com.sbai.data.RuleStore
import com.sbai.service.DnsRuleJsonCodec
import com.sbai.service.SingBoxConfigGenerator
import com.sbai.ui.components.BottomBarClearance
import com.sbai.ui.components.DragDropLazyColumn
import com.sbai.ui.components.FabBottomBarClearance
import com.sbai.ui.components.SbBadge
import com.sbai.ui.components.RestoreBottomBarOnDispose
import com.sbai.ui.components.SbGroup
import com.sbai.ui.components.SbItem
import com.sbai.ui.theme.LocalSbStyleTokens

private val DNS_QUERY_TYPES = listOf("A", "AAAA", "CNAME", "HTTPS", "TXT", "MX", "SOA")
private val IP_STRATEGIES = listOf("", "prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsScreen() {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val tokens = LocalSbStyleTokens.current

    // 字段级订阅：DNS 页只用到 5 个字段，全量订阅会因节点等无关变化重组本页。
    val dnsServers by remember(store) {
        store.state.map { it.dnsServers }.distinctUntilChanged()
    }.collectAsState(initial = store.state.value.dnsServers)
    val dnsGroups by remember(store) {
        store.state.map { it.dnsGroups }.distinctUntilChanged()
    }.collectAsState(initial = store.state.value.dnsGroups)
    val dnsRules by remember(store) {
        store.state.map { it.dnsRules }.distinctUntilChanged()
    }.collectAsState(initial = store.state.value.dnsRules)
    val routeRuleSets by remember(store) {
        store.state.map { it.routeRuleSets }.distinctUntilChanged()
    }.collectAsState(initial = store.state.value.routeRuleSets)
    val routeRules by remember(store) {
        store.state.map { it.routeRules }.distinctUntilChanged()
    }.collectAsState(initial = store.state.value.routeRules)

    var tab by remember { mutableIntStateOf(0) }
    var editingServer by remember { mutableStateOf<DnsServer?>(null) }
    var editingGroup by remember { mutableStateOf<DnsGroup?>(null) }
    var editingRule by remember { mutableStateOf<DnsRule?>(null) }
    // 路由规则自动推导的 DNS 规则（只读展示）——缓存，避免每次重组重算
    val autoRules by remember(dnsServers, dnsGroups, routeRules) {
        derivedStateOf {
            SingBoxConfigGenerator.autoDnsRulesFromRouteRules(dnsServers, dnsGroups, routeRules)
        }
    }

    // 编辑器全部为整页（二级页面），提前 return 覆盖列表页
    editingServer?.let { server ->
        DnsServerEditorDialog(
            initial = server,
            existingServers = dnsServers.map { it.tag },
            existingFakeipTag = dnsServers
                .firstOrNull { it.type == DnsServerType.FAKEIP && it.id != server.id }?.tag,
            onDismiss = { editingServer = null },
            onSave = { store.upsertDnsServer(it); editingServer = null },
            onDelete = if (server.tag.isNotBlank()) {
                { store.deleteDnsServer(server.id); editingServer = null }
            } else null,
        )
        return
    }

    editingGroup?.let { group ->
        DnsGroupEditorDialog(
            initial = group,
            serverTags = dnsServers.filter { it.enabled }.map { it.tag },
            onDismiss = { editingGroup = null },
            onSave = { store.upsertDnsGroup(it); editingGroup = null },
            onDelete = if (group.name.isNotBlank()) {
                { store.deleteDnsGroup(group.id); editingGroup = null }
            } else null,
        )
        return
    }

    editingRule?.let { rule ->
        DnsRuleEditorDialog(
            initial = rule,
            serverOptions = dnsServers.filter { it.enabled }.map { it.tag } +
                    dnsGroups.map { it.name },
            ruleSetTags = routeRuleSets.filter { it.enabled }.map { it.tag },
            onDismiss = { editingRule = null },
            onSave = { store.upsertDnsRule(it); editingRule = null },
            onDelete = if (rule.autoFromRouteRuleId == null && rule.name.isNotBlank()) {
                { store.deleteDnsRule(rule.id); editingRule = null }
            } else null,
        )
        return
    }

    Scaffold(
        floatingActionButton = {
            Box(Modifier.padding(bottom = FabBottomBarClearance)) {
                when (tab) {
                    0 -> ExtendedFloatingActionButton(
                        onClick = { editingServer = DnsServer() },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("添加 DNS") },
                    )
                    1 -> ExtendedFloatingActionButton(
                        onClick = { editingRule = DnsRule() },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("添加规则") },
                    )
                    else -> ExtendedFloatingActionButton(
                        onClick = { editingGroup = DnsGroup() },
                        icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                        text = { Text("添加 group") },
                    )
                }
            }
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("DNS（${dnsServers.size}）") })
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("规则（${dnsRules.size}+${autoRules.size}）") },
                )
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("group（${dnsGroups.size}）") })
            }

            when (tab) {
                0 -> DnsServersTab(
                    servers = dnsServers,
                    onToggle = { store.upsertDnsServer(it.copy(enabled = !it.enabled)) },
                    onEdit = { editingServer = it },
                    modifier = Modifier.padding(horizontal = tokens.screenHorizontalPadding),
                )
                1 -> DnsRulesTab(
                    manualRules = dnsRules,
                    autoRules = autoRules,
                    onToggle = { store.upsertDnsRule(it.copy(enabled = !it.enabled)) },
                    onEdit = { editingRule = it },
                    onDelete = { store.deleteDnsRule(it.id) },
                    onReorder = { ids -> store.reorderDnsRules(ids.map { it.toString() }) },
                    modifier = Modifier.padding(horizontal = tokens.screenHorizontalPadding),
                )
                else -> DnsGroupsTab(
                    groups = dnsGroups,
                    onEdit = { editingGroup = it },
                    modifier = Modifier.padding(horizontal = tokens.screenHorizontalPadding),
                )
            }
        }
    }

}

// ---------------------------------------------------------------------------
// Tab 0：DNS 服务器
// ---------------------------------------------------------------------------

@Composable
private fun DnsServersTab(
    servers: List<DnsServer>,
    onToggle: (DnsServer) -> Unit,
    onEdit: (DnsServer) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = BottomBarClearance)) {
        item { Spacer(Modifier.height(16.dp)) }
        item {
            SbGroup(title = "DNS 服务器") {
                servers.forEach { server ->
                    item {
                        SbItem(
                            title = server.tag.ifBlank { "（未命名）" },
                            subtitle = buildString {
                                append(server.type.wireName)
                                if (server.address.isNotBlank()) append(" · ${server.address}")
                                server.detour?.let { append(" · 出口:$it") } ?: append(" · 无出口")
                            },
                            icon = Icons.Filled.Dns,
                            onClick = { onEdit(server) },
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
                                    Switch(checked = server.enabled, onCheckedChange = { onToggle(server) })
                                }
                            },
                        )
                    }
                }
                if (servers.isEmpty()) {
                    item {
                        SbItem(
                            title = "暂无 DNS",
                            subtitle = "添加后可在路由规则 / DNS 规则中引用；出口可留空",
                            icon = Icons.Filled.Dns,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Tab 1：DNS 规则（手动可排序 + 自动只读）
// ---------------------------------------------------------------------------

@Composable
private fun DnsRulesTab(
    manualRules: List<DnsRule>,
    autoRules: List<DnsRule>,
    onToggle: (DnsRule) -> Unit,
    onEdit: (DnsRule) -> Unit,
    onDelete: (DnsRule) -> Unit,
    onReorder: (List<Any>) -> Unit,
    modifier: Modifier = Modifier,
) {
    DragDropLazyColumn(
        items = manualRules,
        keyOf = { it.id },
        onMove = { from, to ->
            val ids = manualRules.map { it.id }.toMutableList()
            if (from in ids.indices && to in ids.indices) {
                val moved = ids.removeAt(from)
                ids.add(to, moved)
                onReorder(ids)
            }
        },
        onDragEnd = onReorder,
        modifier = modifier,
        contentBottomPadding = BottomBarClearance,
        header = {
            Spacer(Modifier.height(16.dp))
            Text(
                "手动规则 · 越靠上优先级越高（长按拖动排序）",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        },
        footer = {
            if (manualRules.isEmpty()) {
                SbGroup(title = "") {
                    item {
                        SbItem(
                            title = "暂无手动规则",
                            subtitle = "点右下角「添加规则」；路由规则指定的 DNS 会自动生成规则（见下方）",
                            icon = Icons.Filled.Rule,
                        )
                    }
                }
            }
            Spacer(Modifier.height(20.dp))
            if (autoRules.isNotEmpty()) {
                Text(
                    "自动生成的规则（由路由规则推导，只读）",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
                autoRules.forEach { rule ->
                    AutoDnsRuleRow(rule)
                    Spacer(Modifier.height(6.dp))
                }
            }
            Spacer(Modifier.height(24.dp))
        },
    ) { _, rule, isDragging ->
        ManualDnsRuleRow(
            rule = rule,
            isDragging = isDragging,
            onToggle = { onToggle(rule) },
            onEdit = { onEdit(rule) },
            onDelete = { onDelete(rule) },
        )
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
private fun ManualDnsRuleRow(
    rule: DnsRule,
    isDragging: Boolean,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    SbGroup(title = "") {
        item {
            Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Filled.DragIndicator,
                        contentDescription = "长按拖动排序",
                        tint = if (isDragging) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            rule.name.ifBlank { dnsRuleSummary(rule) },
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 1,
                        )
                        Text(
                            "→ ${rule.server.ifBlank { "（未指定）" }}${if (rule.ipStrategy.isNotBlank()) " · ${rule.ipStrategy}" else ""}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                    }
                    Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
                }
                Text(
                    dnsRuleSummary(rule),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    modifier = Modifier.padding(start = 36.dp),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(start = 28.dp),
                ) {
                    if (rule.disableCache) SbBadge("禁缓存", MaterialTheme.colorScheme.tertiary)
                    rule.rewriteTtl?.let {
                        Spacer(Modifier.size(6.dp))
                        SbBadge("TTL=$it", MaterialTheme.colorScheme.tertiary)
                    }
                    Spacer(Modifier.weight(1f))
                    TextButton(onClick = onEdit) { Text("编辑") }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error)
                    }
                }
            }
        }
    }
}

@Composable
private fun AutoDnsRuleRow(rule: DnsRule) {
    SbGroup(title = "") {
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(rule.name, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                    Text(
                        "${dnsRuleSummary(rule)} → ${rule.server}" +
                            if (rule.ipStrategy.isNotBlank()) " · ${rule.ipStrategy}" else "",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                    )
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
// Tab 2：DNS group
// ---------------------------------------------------------------------------

@Composable
private fun DnsGroupsTab(
    groups: List<DnsGroup>,
    onEdit: (DnsGroup) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(modifier = modifier, contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = BottomBarClearance)) {
        item { Spacer(Modifier.height(16.dp)) }
        item {
            SbGroup(title = "DNS group") {
                groups.forEach { group ->
                    item {
                        SbItem(
                            title = group.name.ifBlank { "（未命名）" },
                            subtitle = group.serverTags.joinToString().ifBlank { "（空 group）" },
                            icon = Icons.Filled.GroupWork,
                            onClick = { onEdit(group) },
                        )
                    }
                }
                if (groups.isEmpty()) {
                    item {
                        SbItem(
                            title = "暂无 DNS group",
                            subtitle = "group 是一组 DNS 的命名集合，供路由/DNS 规则引用",
                            icon = Icons.Filled.GroupWork,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// DNS 服务器编辑器（含 ECS / ECH）
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsServerEditorDialog(
    initial: DnsServer,
    existingServers: List<String>,
    existingFakeipTag: String?,
    onDismiss: () -> Unit,
    onSave: (DnsServer) -> Unit,
    onDelete: (() -> Unit)?,
) {
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
    val supportsEch = type in setOf(DnsServerType.TLS, DnsServerType.HTTPS, DnsServerType.QUIC, DnsServerType.H3)

    // fakeIP 唯一性：sing-box 只允许一个 fakeip server；已存在其它 fakeip 时禁止再建
    val hasOtherFakeip = existingFakeipTag != null && existingFakeipTag != initial.tag

    // 整页编辑器（不再是弹窗）；拦截系统返回/侧滑回到 DNS 列表
    BackHandler(enabled = true) { BottomBarController.show(); onDismiss() }
    RestoreBottomBarOnDispose()

    fun doSave() {
        if (tag.isBlank()) return
        // fakeIP 唯一性：已存在其它 fakeIP 时禁止保存
        if (type == DnsServerType.FAKEIP && hasOtherFakeip) return
        onSave(
            initial.copy(
                tag = tag.trim(), type = type, address = address.trim(),
                detour = detour.ifBlank { null },
                addressResolver = resolver.ifBlank { null },
                clientSubnet = clientSubnet.ifBlank { null },
                echEnabled = echEnabled && supportsEch,
                echConfig = echConfig.ifBlank { null },
                inet4Range = inet4Range.trim(),
                inet6Range = inet6Range.trim(),
            ),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial.tag.isBlank()) "添加 DNS" else "编辑 DNS") },
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
                    TextButton(
                        onClick = { doSave() },
                        enabled = tag.isNotBlank() && !(type == DnsServerType.FAKEIP && hasOtherFakeip),
                    ) { Text("保存") }
                },
            )
        },
    ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(value = tag, onValueChange = { tag = it }, label = { Text("tag（唯一标识）") }, singleLine = true, modifier = Modifier.fillMaxWidth())

                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    OutlinedTextField(
                        value = type.wireName, onValueChange = {}, readOnly = true, label = { Text("类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        DnsServerType.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(t.wireName) }, onClick = { type = t; typeExpanded = false })
                        }
                    }
                }

                if (needsAddress) {
                    OutlinedTextField(
                        value = address, onValueChange = { address = it },
                        label = { Text("地址（如 223.5.5.5 / tls://1.1.1.1 / https://dns.google/dns-query）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = detour, onValueChange = { detour = it },
                    label = { Text("出口（可选，留空 = 不指定）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                ExposedDropdownMenuBox(expanded = resolverExpanded, onExpandedChange = { resolverExpanded = it }) {
                    OutlinedTextField(
                        value = resolver.ifBlank { "（不使用）" }, onValueChange = {}, readOnly = true,
                        label = { Text("address_resolver（可选）") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(resolverExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = resolverExpanded, onDismissRequest = { resolverExpanded = false }) {
                        DropdownMenuItem(text = { Text("（不使用）") }, onClick = { resolver = ""; resolverExpanded = false })
                        existingServers.filter { it != tag }.forEach { s ->
                            DropdownMenuItem(text = { Text(s) }, onClick = { resolver = s; resolverExpanded = false })
                        }
                    }
                }

                OutlinedTextField(
                    value = clientSubnet, onValueChange = { clientSubnet = it },
                    label = { Text("ECS client_subnet（可选，如 1.0.1.0/24 或 auto）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                if (supportsEch) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Switch(checked = echEnabled, onCheckedChange = { echEnabled = it })
                        Spacer(Modifier.size(8.dp))
                        Column {
                            Text("ECH（Encrypted Client Hello）", style = MaterialTheme.typography.bodyLarge)
                            Text("仅 tls/https/quic/h3 支持", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (echEnabled) {
                        OutlinedTextField(
                            value = echConfig, onValueChange = { echConfig = it },
                            label = { Text("ECH config（可选，PEM / echconfiglist）") },
                            modifier = Modifier.fillMaxWidth(), minLines = 2,
                        )
                    }
                } else if (echEnabled) {
                    Text("当前类型不支持 ECH", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                }

                // fakeIP 自定义段（仅 fakeip 类型）
                if (type == DnsServerType.FAKEIP) {
                    // fakeIP 唯一性：sing-box 只允许一个 fakeip server
                    if (hasOtherFakeip) {
                        Text(
                            "已存在 fakeIP「$existingFakeipTag」，sing-box 只允许一个 fakeIP。请先删除或改为编辑它。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    OutlinedTextField(
                        value = inet4Range, onValueChange = { inet4Range = it },
                        label = { Text("IPv4 段（可选，默认 10.0.0.0/8）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = inet6Range, onValueChange = { inet6Range = it },
                        label = { Text("IPv6 段（可选，默认 fc00::/18）") },
                        singleLine = true, modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "创建 fakeIP 后，路由规则页和 DNS 规则页会自动生成对应的 fakeIP 规则。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
    }
}

// ---------------------------------------------------------------------------
// DNS 规则编辑器
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DnsRuleEditorDialog(
    initial: DnsRule,
    serverOptions: List<String>,
    ruleSetTags: List<String>,
    onDismiss: () -> Unit,
    onSave: (DnsRule) -> Unit,
    onDelete: (() -> Unit)?,
) {
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

    // #7：整页编辑器（不再是弹窗）；拦截系统返回/侧滑回到 DNS 列表
    BackHandler(enabled = true) { BottomBarController.show(); onDismiss() }
    RestoreBottomBarOnDispose()

    fun doSave() {
        if (ruleAction == "route" && server.isBlank()) { error = "route 动作必须选择目标 DNS / group"; return }
        onSave(
            initial.copy(
                name = name.trim(),
                domains = domains.toLines(), domainSuffixes = suffixes.toLines(),
                domainKeywords = keywords.toLines(), domainRegexes = regexes.toLines(),
                ipCidrs = ipCidrs.toLines(), ruleSetTags = sets.toLines(),
                networks = networks.toLines(),
                ports = portsText.toLines().mapNotNull { it.toIntOrNull() },
                queryTypes = queryTypes.toList().sorted(),
                server = server,
                ipStrategy = ipStrategy,
                disableCache = disableCache,
                rewriteTtl = rewriteTtl.toIntOrNull(),
                clientSubnet = clientSubnet.ifBlank { null },
                action = ruleAction,
                rcode = rcode,
                answers = answers.toLines(),
                ns = ns.toLines(),
                extra = extra.toLines(),
                timeout = timeout.trim(),
            ),
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial.autoFromRouteRuleId != null) "DNS 规则（自动）" else if (initial.name.isBlank()) "添加 DNS 规则" else "编辑 DNS 规则") },
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
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (initial.autoFromRouteRuleId != null) {
                    Text(
                        "此规则由路由规则自动生成，只读；如需自定义请创建手动规则（手动规则优先级更高）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.tertiary,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = name, onValueChange = { name = it },
                        label = { Text("规则名称") }, singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    Spacer(Modifier.size(8.dp))
                    Button(onClick = { showJsonPaste = true }) {
                        Icon(Icons.Filled.ContentPaste, contentDescription = null)
                        Text("粘贴 JSON")
                    }
                }
                MultiLine("域名（一行一条）", domains) { domains = it }
                MultiLine("域名后缀（一行一条）", suffixes) { suffixes = it }
                MultiLine("域名关键词（一行一条）", keywords) { keywords = it }
                MultiLine("域名正则（一行一条）", regexes) { regexes = it }
                MultiLine("IP / CIDR（一行一条）", ipCidrs) { ipCidrs = it }
                MultiLine(
                    "规则集 tag（一行一条）${if (ruleSetTags.isNotEmpty()) "，已有：${ruleSetTags.joinToString()}" else ""}",
                    sets,
                ) { sets = it }
                MultiLine("network（一行一条，tcp/udp/icmp）", networks) { networks = it }
                MultiLine("端口（一行一个）", portsText) { portsText = it }

                Text("查询类型（可多选）", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DNS_QUERY_TYPES.forEach { qt ->
                        FilterChip(
                            selected = qt in queryTypes,
                            onClick = { queryTypes = if (qt in queryTypes) queryTypes - qt else queryTypes + qt },
                            label = { Text(qt) },
                        )
                    }
                }

                // 动作（sb-AI 基准）
                Text("动作", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    listOf("route" to "路由", "route-options" to "改写应答", "reject" to "拒绝", "pre-defined" to "预定义")
                        .forEachIndexed { i, (v, label) ->
                            SegmentedButton(
                                selected = ruleAction == v,
                                onClick = { ruleAction = v },
                                shape = SegmentedButtonDefaults.itemShape(index = i, count = 4),
                            ) { Text(label) }
                        }
                }

                if (ruleAction == "route") {
                    ExposedDropdownMenuBox(expanded = serverExpanded, onExpandedChange = { serverExpanded = it }) {
                        OutlinedTextField(
                            value = server.ifBlank { "（必选）" }, onValueChange = {}, readOnly = true,
                            label = { Text("目标 DNS / group") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(serverExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = serverExpanded, onDismissRequest = { serverExpanded = false }) {
                            serverOptions.forEach { opt ->
                                DropdownMenuItem(text = { Text(opt) }, onClick = { server = opt; serverExpanded = false })
                            }
                        }
                    }
                }

                if (ruleAction == "reject" || ruleAction == "route-options") {
                    Text("rcode", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("success", "refused", "formerror", "notimp", "nxdomain").forEach { rc ->
                            FilterChip(
                                selected = rcode == rc,
                                onClick = { rcode = if (rcode == rc) "" else rc },
                                label = { Text(rc) },
                            )
                        }
                    }
                }

                if (ruleAction == "route-options") {
                    MultiLine("覆盖应答 A 记录（一行一个 IPv4）", answers) { answers = it }
                    MultiLine("覆盖应答 NS 记录（一行一条）", ns) { ns = it }
                    MultiLine("覆盖应答 EXTRA 记录（一行一条）", extra) { extra = it }
                }

                if (ruleAction == "route" || ruleAction == "route-options") {
                    ExposedDropdownMenuBox(expanded = strategyExpanded, onExpandedChange = { strategyExpanded = it }) {
                        OutlinedTextField(
                            value = ipStrategy.ifBlank { "（不设置）" }, onValueChange = {}, readOnly = true,
                            label = { Text("IP 解析策略（可选）") },
                            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(strategyExpanded) },
                            modifier = Modifier.menuAnchor().fillMaxWidth(),
                        )
                        ExposedDropdownMenu(expanded = strategyExpanded, onDismissRequest = { strategyExpanded = false }) {
                            IP_STRATEGIES.forEach { s ->
                                DropdownMenuItem(
                                    text = { Text(if (s.isBlank()) "（不设置）" else s) },
                                    onClick = { ipStrategy = s; strategyExpanded = false },
                                )
                            }
                        }
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = disableCache, onCheckedChange = { disableCache = it })
                    Spacer(Modifier.size(8.dp))
                    Text("禁用缓存（disable_cache）")
                }
                OutlinedTextField(
                    value = rewriteTtl, onValueChange = { rewriteTtl = it },
                    label = { Text("rewrite_ttl（秒，可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = clientSubnet, onValueChange = { clientSubnet = it },
                    label = { Text("本条规则 ECS client_subnet（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = timeout, onValueChange = { timeout = it },
                    label = { Text("查询超时（如 4s，可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
    }

    if (showJsonPaste) {
        DnsJsonPasteDialog(
            onDismiss = { showJsonPaste = false },
            onApply = { text ->
                when (val r = DnsRuleJsonCodec.fromJson(text)) {
                    is DnsRuleJsonCodec.ParseResult.Success -> {
                        applyAll(r.rule.copy(id = initial.id, autoFromRouteRuleId = initial.autoFromRouteRuleId))
                        jsonError = null
                        showJsonPaste = false
                    }
                    is DnsRuleJsonCodec.ParseResult.Failure -> jsonError = r.message
                }
            },
            error = jsonError,
        )
    }
}

/** 粘贴 sing-box dns rule JSON 片段 */
@Composable
private fun DnsJsonPasteDialog(
    onDismiss: () -> Unit,
    onApply: (String) -> Unit,
    error: String?,
) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("粘贴 DNS 规则 JSON 片段") },
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        text = {
            Column {
                Text(
                    "只需 dns.rules 里的单个规则对象，例如：\n" +
                        "{\"domain_suffix\":[\"example.com\"],\"server\":\"dns-remote\"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(420.dp),
                    textStyle = MaterialTheme.typography.bodySmall.copy(
                        fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                    ),
                    maxLines = Int.MAX_VALUE,
                )
                error?.let {
                    Spacer(Modifier.height(6.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = { onApply(text) }) { Text("解析并回填") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun DnsGroupEditorDialog(
    initial: DnsGroup,
    serverTags: List<String>,
    onDismiss: () -> Unit,
    onSave: (DnsGroup) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var name by remember { mutableStateOf(initial.name) }
    var selected by remember { mutableStateOf(initial.serverTags.toSet()) }

    BackHandler(enabled = true) { BottomBarController.show(); onDismiss() }
    RestoreBottomBarOnDispose()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial.name.isBlank()) "添加 DNS group" else "编辑 DNS group") },
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
                    TextButton(onClick = {
                        if (name.isBlank()) return@TextButton
                        onSave(initial.copy(name = name.trim(), serverTags = selected.toList().sorted()))
                    }) { Text("保存") }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("group 名称") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Text("成员 DNS", style = MaterialTheme.typography.labelLarge)
            if (serverTags.isEmpty()) {
                Text("尚无可用 DNS，请先在「DNS」页添加。", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    serverTags.forEach { tag ->
                        FilterChip(
                            selected = tag in selected,
                            onClick = { selected = if (tag in selected) selected - tag else selected + tag },
                            label = { Text(tag) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MultiLine(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
}

private fun String.toLines(): List<String> =
    lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
