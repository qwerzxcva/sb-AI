package com.sbai.ui.routes

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Dataset
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.sbai.data.RouteRule
import com.sbai.data.RouteRuleSet
import com.sbai.data.RuleAction
import com.sbai.data.RuleLogic
import com.sbai.data.RuleSetType
import com.sbai.data.RuleStore
import com.sbai.ui.components.SbBadge
import com.sbai.ui.components.SbGroup
import com.sbai.ui.components.SbItem
import com.sbai.ui.components.SbSpacer
import com.sbai.ui.theme.LocalSbStyleTokens

private val NETWORK_OPTIONS = listOf("tcp", "udp")
private val PROTOCOL_OPTIONS = listOf("http", "tls", "quic", "dns", "bittorrent", "stun", "ssh")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RouteRulesScreen() {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val state by store.state.collectAsState()
    val tokens = LocalSbStyleTokens.current

    var editingRule by remember { mutableStateOf<RouteRule?>(null) }
    var editingRuleSet by remember { mutableStateOf<RouteRuleSet?>(null) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { editingRule = RouteRule() },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text("添加规则") },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = tokens.screenHorizontalPadding),
        ) {
            item { Spacer(Modifier.height(16.dp)) }

            // ---- 规则集（一行一条） ----
            item {
                SbGroup(title = "规则集") {
                    state.routeRuleSets.forEach { rs ->
                        item {
                            SbItem(
                                title = rs.tag.ifBlank { "（未命名）" },
                                subtitle = buildString {
                                    append(if (rs.type == RuleSetType.REMOTE) "remote" else "local")
                                    if (rs.type == RuleSetType.REMOTE && rs.url.isNotBlank()) append(" · ${rs.url.take(40)}")
                                },
                                icon = Icons.Filled.Dataset,
                                onClick = { editingRuleSet = rs },
                                trailing = {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        if (!rs.ipv4 || !rs.ipv6) {
                                            SbBadge(
                                                if (rs.ipv4) "仅IPv4" else "仅IPv6",
                                                MaterialTheme.colorScheme.tertiary,
                                            )
                                            Spacer(Modifier.size(8.dp))
                                        }
                                        Switch(
                                            checked = rs.enabled,
                                            onCheckedChange = { store.upsertRuleSet(rs.copy(enabled = !rs.enabled)) },
                                        )
                                    }
                                },
                            )
                        }
                    }
                    item {
                        SbItem(
                            title = "添加规则集",
                            subtitle = "本地 / 远程（含 IP 规则集），可勾选 IPv4/IPv6",
                            icon = Icons.Filled.Add,
                            onClick = { editingRuleSet = RouteRuleSet() },
                        )
                    }
                }
                SbSpacer()
            }

            // ---- 路由规则 ----
            item {
                SbGroup(title = "路由规则（按顺序匹配）") {
                    state.routeRules.forEachIndexed { index, rule ->
                        item {
                            RouteRuleRow(
                                index = index + 1,
                                rule = rule,
                                onToggle = { store.upsertRouteRule(rule.copy(enabled = !rule.enabled)) },
                                onEdit = { editingRule = rule },
                                onDelete = { store.deleteRouteRule(rule.id) },
                                onMoveUp = { store.moveRouteRule(rule.id, up = true) },
                                onMoveDown = { store.moveRouteRule(rule.id, up = false) },
                            )
                        }
                    }
                    if (state.routeRules.isEmpty()) {
                        item {
                            SbItem(
                                title = "暂无路由规则",
                                subtitle = "点右下角「添加规则」创建；域名/IP 一行一条",
                                icon = Icons.Filled.Rule,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(112.dp))
            }
        }
    }

    editingRule?.let { rule ->
        RouteRuleEditorDialog(
            initial = rule,
            dnsOptions = state.dnsServers.filter { it.enabled }.map { it.tag } + state.dnsGroups.map { it.name },
            ruleSetTags = state.routeRuleSets.filter { it.enabled }.map { it.tag },
            onDismiss = { editingRule = null },
            onSave = { store.upsertRouteRule(it); editingRule = null },
        )
    }

    editingRuleSet?.let { rs ->
        RuleSetEditorDialog(
            initial = rs,
            onDismiss = { editingRuleSet = null },
            onSave = { store.upsertRuleSet(it); editingRuleSet = null },
            onDelete = if (rs.tag.isNotBlank()) {
                { store.deleteRuleSet(rs.id); editingRuleSet = null }
            } else null,
        )
    }
}

@Composable
private fun RouteRuleRow(
    index: Int,
    rule: RouteRule,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "#$index",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.size(10.dp))
            ActionBadge(rule.action)
            Spacer(Modifier.size(8.dp))
            Text(
                rule.name.ifBlank { ruleSummary(rule) },
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f),
                maxLines = 1,
            )
            Switch(checked = rule.enabled, onCheckedChange = { onToggle() })
        }
        Text(
            ruleSummary(rule),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (rule.logic != RuleLogic.SINGLE) {
                SbBadge(rule.logic.wireName ?: "", MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.size(6.dp))
            }
            if (rule.invert) {
                SbBadge("invert", MaterialTheme.colorScheme.tertiary)
                Spacer(Modifier.size(6.dp))
            }
            rule.dnsTag?.let {
                SbBadge("DNS:$it", MaterialTheme.colorScheme.secondary)
                Spacer(Modifier.size(6.dp))
            }
            if (!rule.ipv4 || !rule.ipv6) {
                SbBadge(if (rule.ipv4) "仅IPv4" else "仅IPv6", MaterialTheme.colorScheme.secondary)
            }
            Spacer(Modifier.weight(1f))
            IconButton(onClick = onMoveUp) { Icon(Icons.Filled.ArrowUpward, contentDescription = "上移") }
            IconButton(onClick = onMoveDown) { Icon(Icons.Filled.ArrowDownward, contentDescription = "下移") }
            TextButton(onClick = onEdit) { Text("编辑") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除", tint = MaterialTheme.colorScheme.error) }
        }
    }
}

@Composable
private fun ActionBadge(action: RuleAction) {
    val (label, color) = when (action) {
        RuleAction.PROXY -> "代理" to MaterialTheme.colorScheme.primary
        RuleAction.DIRECT -> "直连" to MaterialTheme.colorScheme.secondary
        RuleAction.BLOCK -> "拦截" to MaterialTheme.colorScheme.error
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
}.joinToString(" ").ifBlank { "（空规则 = 匹配全部）" }

// ---------------------------------------------------------------------------
// 路由规则编辑器
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun RouteRuleEditorDialog(
    initial: RouteRule,
    dnsOptions: List<String>,
    ruleSetTags: List<String>,
    onDismiss: () -> Unit,
    onSave: (RouteRule) -> Unit,
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
    var logic by remember { mutableStateOf(initial.logic) }
    var invert by remember { mutableStateOf(initial.invert) }
    var ipv4 by remember { mutableStateOf(initial.ipv4) }
    var ipv6 by remember { mutableStateOf(initial.ipv6) }
    var dnsTag by remember { mutableStateOf(initial.dnsTag ?: "") }

    val isBlock = action == RuleAction.BLOCK
    val hasDomains = listOf(domains, suffixes, keywords, regexes).any { it.isNotBlank() }
    val ipOnly = !hasDomains && (ipCidrs.isNotBlank() || sets.isNotBlank())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank() && initial.domains.isEmpty()) "添加路由规则" else "编辑路由规则") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("规则名称（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                Text("动作", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    RuleAction.entries.forEachIndexed { i, a ->
                        SegmentedButton(
                            selected = action == a,
                            onClick = { action = a },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = RuleAction.entries.size),
                        ) {
                            Text(
                                when (a) {
                                    RuleAction.PROXY -> "代理"
                                    RuleAction.DIRECT -> "直连"
                                    RuleAction.BLOCK -> "拦截"
                                },
                            )
                        }
                    }
                }

                MultiLineField("域名（一行一条）", domains) { domains = it }
                MultiLineField("域名后缀（一行一条）", suffixes) { suffixes = it }
                MultiLineField("域名关键词（一行一条）", keywords) { keywords = it }
                MultiLineField("域名正则（一行一条）", regexes) { regexes = it }
                MultiLineField("IP / CIDR（一行一条）", ipCidrs) { ipCidrs = it }
                MultiLineField(
                    "规则集 tag（一行一条）${if (ruleSetTags.isNotEmpty()) "，已有：${ruleSetTags.joinToString()}" else ""}",
                    sets,
                ) { sets = it }

                Text("network（可多选）", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    NETWORK_OPTIONS.forEach { opt ->
                        FilterChip(
                            selected = opt in networks,
                            onClick = { networks = if (opt in networks) networks - opt else networks + opt },
                            label = { Text(opt) },
                        )
                    }
                }

                Text("protocol（可多选，依赖 sniff）", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PROTOCOL_OPTIONS.forEach { opt ->
                        FilterChip(
                            selected = opt in protocols,
                            onClick = { protocols = if (opt in protocols) protocols - opt else protocols + opt },
                            label = { Text(opt) },
                        )
                    }
                }

                Text("逻辑运算", style = MaterialTheme.typography.labelLarge)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    RuleLogic.entries.forEachIndexed { i, l ->
                        SegmentedButton(
                            selected = logic == l,
                            onClick = { logic = l },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = RuleLogic.entries.size),
                        ) { Text(l.wireName ?: "单条件") }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = invert, onCheckedChange = { invert = it })
                    Text("invert（取反）")
                }

                Text("IP 版本（影响 DNS 解析策略，默认全选）", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = ipv4, onCheckedChange = { ipv4 = it })
                    Text("IPv4")
                    Spacer(Modifier.padding(8.dp))
                    Checkbox(checked = ipv6, onCheckedChange = { ipv6 = it })
                    Text("IPv6")
                }

                if (!isBlock && !ipOnly) {
                    DnsDropdown(value = dnsTag, options = dnsOptions, onChange = { dnsTag = it })
                    Text(
                        "选择 DNS / DNS group 后将自动为本规则生成一条 DNS 规则；" +
                            "若同时取消 IPv4/IPv6 之一，也只生成一条合并规则。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else if (isBlock) {
                    Text("拦截类规则不生成 DNS 联动规则。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    Text("纯 IP / 远程规则集规则不生成 DNS 联动规则。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    initial.copy(
                        name = name.trim(),
                        action = action,
                        domains = domains.toLines(),
                        domainSuffixes = suffixes.toLines(),
                        domainKeywords = keywords.toLines(),
                        domainRegexes = regexes.toLines(),
                        ipCidrs = ipCidrs.toLines(),
                        ruleSetTags = sets.toLines(),
                        networks = networks.toList().sorted(),
                        protocols = protocols.toList().sorted(),
                        logic = logic,
                        invert = invert,
                        ipv4 = ipv4,
                        ipv6 = ipv6,
                        dnsTag = dnsTag.ifBlank { null },
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------------------------------------------------------------------
// 规则集编辑器（含 IPv4/IPv6 勾选）
// ---------------------------------------------------------------------------

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleSetEditorDialog(
    initial: RouteRuleSet,
    onDismiss: () -> Unit,
    onSave: (RouteRuleSet) -> Unit,
    onDelete: (() -> Unit)?,
) {
    var tag by remember { mutableStateOf(initial.tag) }
    var type by remember { mutableStateOf(initial.type) }
    var url by remember { mutableStateOf(initial.url) }
    var localContent by remember { mutableStateOf(initial.localContent) }
    var detour by remember { mutableStateOf(initial.downloadDetour ?: "") }
    var ipv4 by remember { mutableStateOf(initial.ipv4) }
    var ipv6 by remember { mutableStateOf(initial.ipv6) }
    var typeExpanded by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.tag.isBlank()) "添加规则集" else "编辑规则集") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = tag, onValueChange = { tag = it },
                    label = { Text("tag（路由规则按此引用）") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    OutlinedTextField(
                        value = if (type == RuleSetType.REMOTE) "remote" else "local",
                        onValueChange = {}, readOnly = true, label = { Text("类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                        modifier = Modifier.menuAnchor().fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        DropdownMenuItem(text = { Text("remote（远程规则集，含 IP 规则集）") }, onClick = { type = RuleSetType.REMOTE; typeExpanded = false })
                        DropdownMenuItem(text = { Text("local（内联）") }, onClick = { type = RuleSetType.LOCAL; typeExpanded = false })
                    }
                }

                if (type == RuleSetType.REMOTE) {
                    OutlinedTextField(value = url, onValueChange = { url = it }, label = { Text("URL") }, modifier = Modifier.fillMaxWidth())
                    OutlinedTextField(value = detour, onValueChange = { detour = it }, label = { Text("download_detour（可选）") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                } else {
                    OutlinedTextField(
                        value = localContent, onValueChange = { localContent = it },
                        label = { Text("规则集 JSON（source 格式）") }, modifier = Modifier.fillMaxWidth(), minLines = 4,
                        placeholder = { Text("{\"version\":3,\"rules\":[...]}") },
                    )
                }

                Text("IP 版本（默认全选；取消任一项将生成 DNS 规则）", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = ipv4, onCheckedChange = { ipv4 = it })
                    Text("IPv4")
                    Spacer(Modifier.padding(8.dp))
                    Checkbox(checked = ipv6, onCheckedChange = { ipv6 = it })
                    Text("IPv6")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (tag.isBlank()) return@TextButton
                onSave(
                    initial.copy(
                        tag = tag.trim(), type = type, url = url.trim(),
                        localContent = localContent,
                        downloadDetour = detour.ifBlank { null },
                        ipv4 = ipv4, ipv6 = ipv6,
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
private fun MultiLineField(label: String, value: String, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, modifier = Modifier.fillMaxWidth(), minLines = 2)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsDropdown(value: String, options: List<String>, onChange: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = value.ifBlank { "（不指定）" },
            onValueChange = {}, readOnly = true,
            label = { Text("DNS / DNS group（可选）") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("（不指定）") }, onClick = { onChange(""); expanded = false })
            options.forEach { opt ->
                DropdownMenuItem(text = { Text(opt) }, onClick = { onChange(opt); expanded = false })
            }
        }
    }
}

private fun String.toLines(): List<String> =
    lines().map { it.trim() }.filter { it.isNotEmpty() }.distinct()
