package com.sbai.ui.dns

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
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.GroupWork
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.sbai.data.DnsGroup
import com.sbai.data.DnsServer
import com.sbai.data.DnsServerType
import com.sbai.data.RuleStore
import com.sbai.ui.components.SbBadge
import com.sbai.ui.components.SbGroup
import com.sbai.ui.components.SbItem
import com.sbai.ui.theme.LocalSbStyleTokens

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsScreen() {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val state by store.state.collectAsState()
    val tokens = LocalSbStyleTokens.current

    var tab by remember { mutableIntStateOf(0) }
    var editingServer by remember { mutableStateOf<DnsServer?>(null) }
    var editingGroup by remember { mutableStateOf<DnsGroup?>(null) }

    Scaffold(
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { if (tab == 0) editingServer = DnsServer() else editingGroup = DnsGroup() },
                icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                text = { Text(if (tab == 0) "添加 DNS" else "添加 group") },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("DNS（${state.dnsServers.size}）") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("DNS group（${state.dnsGroups.size}）") })
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = tokens.screenHorizontalPadding),
            ) {
                item { Spacer(Modifier.height(16.dp)) }

                if (tab == 0) {
                    item {
                        SbGroup(title = "DNS 服务器") {
                            state.dnsServers.forEach { server ->
                                item {
                                    SbItem(
                                        title = server.tag.ifBlank { "（未命名）" },
                                        subtitle = buildString {
                                            append(server.type.wireName)
                                            if (server.address.isNotBlank()) append(" · ${server.address}")
                                            server.detour?.let { append(" · 出口:$it") } ?: append(" · 无出口")
                                        },
                                        icon = Icons.Filled.Dns,
                                        onClick = { editingServer = server },
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
                                                Switch(
                                                    checked = server.enabled,
                                                    onCheckedChange = { store.upsertDnsServer(server.copy(enabled = !server.enabled)) },
                                                )
                                            }
                                        },
                                    )
                                }
                            }
                            if (state.dnsServers.isEmpty()) {
                                item {
                                    SbItem(
                                        title = "暂无 DNS",
                                        subtitle = "添加后可在路由规则中引用；出口可留空",
                                        icon = Icons.Filled.Dns,
                                    )
                                }
                            }
                        }
                    }
                } else {
                    item {
                        SbGroup(title = "DNS group") {
                            state.dnsGroups.forEach { group ->
                                item {
                                    SbItem(
                                        title = group.name.ifBlank { "（未命名）" },
                                        subtitle = group.serverTags.joinToString().ifBlank { "（空 group）" },
                                        icon = Icons.Filled.GroupWork,
                                        onClick = { editingGroup = group },
                                    )
                                }
                            }
                            if (state.dnsGroups.isEmpty()) {
                                item {
                                    SbItem(
                                        title = "暂无 DNS group",
                                        subtitle = "group 是一组 DNS 的命名集合，供路由规则引用",
                                        icon = Icons.Filled.GroupWork,
                                    )
                                }
                            }
                        }
                    }
                }

                item { Spacer(Modifier.height(112.dp)) }
            }
        }
    }

    editingServer?.let { server ->
        DnsServerEditorDialog(
            initial = server,
            existingServers = state.dnsServers.map { it.tag },
            onDismiss = { editingServer = null },
            onSave = { store.upsertDnsServer(it); editingServer = null },
            onDelete = if (server.tag.isNotBlank()) {
                { store.deleteDnsServer(server.id); editingServer = null }
            } else null,
        )
    }

    editingGroup?.let { group ->
        DnsGroupEditorDialog(
            initial = group,
            serverTags = state.dnsServers.filter { it.enabled }.map { it.tag },
            onDismiss = { editingGroup = null },
            onSave = { store.upsertDnsGroup(it); editingGroup = null },
            onDelete = if (group.name.isNotBlank()) {
                { store.deleteDnsGroup(group.id); editingGroup = null }
            } else null,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsServerEditorDialog(
    initial: DnsServer,
    existingServers: List<String>,
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
    var typeExpanded by remember { mutableStateOf(false) }
    var resolverExpanded by remember { mutableStateOf(false) }

    val needsAddress = type !in setOf(DnsServerType.LOCAL, DnsServerType.HOSTS, DnsServerType.FAKEIP)
    val supportsEch = type in setOf(DnsServerType.TLS, DnsServerType.HTTPS, DnsServerType.QUIC, DnsServerType.H3)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.tag.isBlank()) "添加 DNS" else "编辑 DNS") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
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

                // ECS
                OutlinedTextField(
                    value = clientSubnet, onValueChange = { clientSubnet = it },
                    label = { Text("ECS client_subnet（可选，如 1.0.1.0/24 或 auto）") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )

                // ECH
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
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (tag.isBlank()) return@TextButton
                onSave(
                    initial.copy(
                        tag = tag.trim(), type = type, address = address.trim(),
                        detour = detour.ifBlank { null },
                        addressResolver = resolver.ifBlank { null },
                        clientSubnet = clientSubnet.ifBlank { null },
                        echEnabled = echEnabled && supportsEch,
                        echConfig = echConfig.ifBlank { null },
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

@OptIn(ExperimentalLayoutApi::class)
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

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) "添加 DNS group" else "编辑 DNS group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
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
        },
        confirmButton = {
            TextButton(onClick = {
                if (name.isBlank()) return@TextButton
                onSave(initial.copy(name = name.trim(), serverTags = selected.toList().sorted()))
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
