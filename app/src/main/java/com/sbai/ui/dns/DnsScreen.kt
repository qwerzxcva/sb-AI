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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DnsScreen() {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val state by store.state.collectAsState()

    var tab by remember { mutableIntStateOf(0) }
    var editingServer by remember { mutableStateOf<DnsServer?>(null) }
    var editingGroup by remember { mutableStateOf<DnsGroup?>(null) }

    Scaffold(
        topBar = { TopAppBar(title = { Text("DNS 规则") }) },
        floatingActionButton = {
            Button(onClick = {
                if (tab == 0) editingServer = DnsServer() else editingGroup = DnsGroup()
            }) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Text(if (tab == 0) "添加 DNS" else "添加 DNS group")
            }
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
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (tab == 0) {
                    items(state.dnsServers, key = { it.id }) { server ->
                        DnsServerCard(
                            server = server,
                            onToggle = { store.upsertDnsServer(server.copy(enabled = !server.enabled)) },
                            onEdit = { editingServer = server },
                            onDelete = { store.deleteDnsServer(server.id) },
                        )
                    }
                    if (state.dnsServers.isEmpty()) {
                        item {
                            Text(
                                "暂无 DNS。添加后可在路由规则中引用，无需指定出口。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                } else {
                    items(state.dnsGroups, key = { it.id }) { group ->
                        DnsGroupCard(
                            group = group,
                            onEdit = { editingGroup = group },
                            onDelete = { store.deleteDnsGroup(group.id) },
                        )
                    }
                    if (state.dnsGroups.isEmpty()) {
                        item {
                            Text(
                                "暂无 DNS group。group 是一组 DNS 的命名集合，供路由规则引用。",
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                item { Spacer(Modifier.height(96.dp)) }
            }
        }
    }

    editingServer?.let { server ->
        DnsServerEditorDialog(
            initial = server,
            existingServers = state.dnsServers.map { it.tag },
            onDismiss = { editingServer = null },
            onSave = {
                store.upsertDnsServer(it)
                editingServer = null
            },
        )
    }

    editingGroup?.let { group ->
        DnsGroupEditorDialog(
            initial = group,
            serverTags = state.dnsServers.filter { it.enabled }.map { it.tag },
            onDismiss = { editingGroup = null },
            onSave = {
                store.upsertDnsGroup(it)
                editingGroup = null
            },
        )
    }
}

@Composable
private fun DnsServerCard(
    server: DnsServer,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(server.tag.ifBlank { "（未命名）" }, style = MaterialTheme.typography.titleSmall)
                Text(
                    "${server.type.wireName} · ${server.address.ifBlank { "—" }}" +
                        (server.detour?.let { " · 出口:$it" } ?: " · 无出口"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = server.enabled, onCheckedChange = { onToggle() })
            OutlinedButton(onClick = onEdit) { Text("编辑") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
        }
    }
}

@Composable
private fun DnsGroupCard(
    group: DnsGroup,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(group.name.ifBlank { "（未命名 group）" }, style = MaterialTheme.typography.titleSmall)
                Text(
                    group.serverTags.joinToString().ifBlank { "（空 group）" },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            OutlinedButton(onClick = onEdit) { Text("编辑") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DnsServerEditorDialog(
    initial: DnsServer,
    existingServers: List<String>,
    onDismiss: () -> Unit,
    onSave: (DnsServer) -> Unit,
) {
    var tag by remember { mutableStateOf(initial.tag) }
    var type by remember { mutableStateOf(initial.type) }
    var address by remember { mutableStateOf(initial.address) }
    var detour by remember { mutableStateOf(initial.detour ?: "") }
    var resolver by remember { mutableStateOf(initial.addressResolver ?: "") }
    var typeExpanded by remember { mutableStateOf(false) }
    var resolverExpanded by remember { mutableStateOf(false) }

    val needsAddress = type !in setOf(DnsServerType.LOCAL, DnsServerType.HOSTS, DnsServerType.FAKEIP)

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
                OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    label = { Text("tag（唯一标识）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    OutlinedTextField(
                        value = type.wireName,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        DnsServerType.entries.forEach { t ->
                            DropdownMenuItem(text = { Text(t.wireName) }, onClick = {
                                type = t; typeExpanded = false
                            })
                        }
                    }
                }

                if (needsAddress) {
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text("地址（如 223.5.5.5 / 8.8.8.8 / tls://1.1.1.1）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                OutlinedTextField(
                    value = detour,
                    onValueChange = { detour = it },
                    label = { Text("出口（可选，留空 = 不指定）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                ExposedDropdownMenuBox(expanded = resolverExpanded, onExpandedChange = { resolverExpanded = it }) {
                    OutlinedTextField(
                        value = resolver.ifBlank { "（不使用）" },
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("address_resolver（可选）") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(resolverExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = resolverExpanded, onDismissRequest = { resolverExpanded = false }) {
                        DropdownMenuItem(text = { Text("（不使用）") }, onClick = {
                            resolver = ""; resolverExpanded = false
                        })
                        existingServers.filter { it != tag }.forEach { s ->
                            DropdownMenuItem(text = { Text(s) }, onClick = {
                                resolver = s; resolverExpanded = false
                            })
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (tag.isBlank()) return@TextButton
                onSave(
                    initial.copy(
                        tag = tag.trim(),
                        type = type,
                        address = address.trim(),
                        detour = detour.ifBlank { null },
                        addressResolver = resolver.ifBlank { null },
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DnsGroupEditorDialog(
    initial: DnsGroup,
    serverTags: List<String>,
    onDismiss: () -> Unit,
    onSave: (DnsGroup) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var selected by remember { mutableStateOf(initial.serverTags.toSet()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.name.isBlank()) "添加 DNS group" else "编辑 DNS group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("group 名称（唯一标识）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Text("选择成员 DNS", style = MaterialTheme.typography.labelLarge)
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
