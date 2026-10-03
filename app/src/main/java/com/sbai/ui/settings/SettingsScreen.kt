package com.sbai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sbai.data.AppSettings
import com.sbai.data.LogLevel
import com.sbai.data.RouteRuleSet
import com.sbai.data.RuleSetType
import com.sbai.data.RuleStore

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen() {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val state by store.state.collectAsState()
    val settings = state.settings

    var editingRuleSet by remember { mutableStateOf<RouteRuleSet?>(null) }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { TopAppBar(title = { Text("设置") }) }

        // ---- 通用 ----
        item { Text("通用", style = MaterialTheme.typography.titleMedium) }
        item {
            SettingSwitchRow(
                title = "IPv6 路由",
                subtitle = "TUN 同时承载 IPv6 流量",
                checked = settings.ipv6Route,
            ) { store.updateSettings(settings.copy(ipv6Route = it)) }
        }
        item {
            SettingSwitchRow(
                title = "自动检测网卡",
                subtitle = "auto_detect_interface",
                checked = settings.autoDetectInterface,
            ) { store.updateSettings(settings.copy(autoDetectInterface = it)) }
        }
        item {
            SettingSwitchRow(
                title = "严格路由",
                subtitle = "strict_route，防止泄漏",
                checked = settings.strictRoute,
            ) { store.updateSettings(settings.copy(strictRoute = it)) }
        }
        item {
            OutlinedTextField(
                value = settings.mtu.toString(),
                onValueChange = { v -> v.toIntOrNull()?.let { n -> store.updateSettings(settings.copy(mtu = n)) } },
                label = { Text("MTU") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        item {
            Text("日志级别", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                LogLevel.entries.take(5).forEachIndexed { i, level ->
                    SegmentedButton(
                        selected = settings.logLevel == level,
                        onClick = { store.updateSettings(settings.copy(logLevel = level)) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 5),
                    ) { Text(level.wireName) }
                }
            }
        }
        item {
            Text("DNS 策略（fallback）", style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                listOf("prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only").forEachIndexed { i, s ->
                    SegmentedButton(
                        selected = settings.dnsStrategy == s,
                        onClick = { store.updateSettings(settings.copy(dnsStrategy = s)) },
                        shape = SegmentedButtonDefaults.itemShape(index = i, count = 4),
                    ) { Text(s.removePrefix("prefer_").removePrefix("_only")) }
                }
            }
        }

        // ---- 规则集 ----
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("路由规则集（${state.routeRuleSets.size}）", style = MaterialTheme.typography.titleMedium)
                Button(onClick = { editingRuleSet = RouteRuleSet() }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Text("添加")
                }
            }
        }
        items(state.routeRuleSets, key = { it.id }) { rs ->
            RuleSetCard(
                rs = rs,
                onToggle = { store.upsertRuleSet(rs.copy(enabled = !rs.enabled)) },
                onEdit = { editingRuleSet = rs },
                onDelete = { store.deleteRuleSet(rs.id) },
            )
        }
        if (state.routeRuleSets.isEmpty()) {
            item {
                Text(
                    "规则集可被路由规则按 tag 引用；可勾选 IPv4/IPv6（默认全选），取消任一项将生成 DNS 规则。",
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }

    editingRuleSet?.let { rs ->
        RuleSetEditorDialog(
            initial = rs,
            onDismiss = { editingRuleSet = null },
            onSave = {
                store.upsertRuleSet(it)
                editingRuleSet = null
            },
        )
    }
}

@Composable
private fun SettingSwitchRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RuleSetCard(
    rs: RouteRuleSet,
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
                Text(rs.tag.ifBlank { "（未命名）" }, style = MaterialTheme.typography.titleSmall)
                Text(
                    buildString {
                        append(if (rs.type == RuleSetType.REMOTE) "remote" else "local")
                        if (!rs.ipv4 || !rs.ipv6) append(if (rs.ipv4) " · 仅IPv4" else " · 仅IPv6")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = rs.enabled, onCheckedChange = { onToggle() })
            OutlinedButton(onClick = onEdit) { Text("编辑") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RuleSetEditorDialog(
    initial: RouteRuleSet,
    onDismiss: () -> Unit,
    onSave: (RouteRuleSet) -> Unit,
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = tag,
                    onValueChange = { tag = it },
                    label = { Text("tag（路由规则按此引用）") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                ExposedDropdownMenuBox(expanded = typeExpanded, onExpandedChange = { typeExpanded = it }) {
                    OutlinedTextField(
                        value = if (type == RuleSetType.REMOTE) "remote" else "local",
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("类型") },
                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(typeExpanded) },
                        modifier = Modifier
                            .menuAnchor()
                            .fillMaxWidth(),
                    )
                    ExposedDropdownMenu(expanded = typeExpanded, onDismissRequest = { typeExpanded = false }) {
                        DropdownMenuItem(text = { Text("remote（远程规则集，含 IP 规则集）") }, onClick = {
                            type = RuleSetType.REMOTE; typeExpanded = false
                        })
                        DropdownMenuItem(text = { Text("local（内联）") }, onClick = {
                            type = RuleSetType.LOCAL; typeExpanded = false
                        })
                    }
                }

                if (type == RuleSetType.REMOTE) {
                    OutlinedTextField(
                        value = url,
                        onValueChange = { url = it },
                        label = { Text("URL") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = detour,
                        onValueChange = { detour = it },
                        label = { Text("download_detour（可选）") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    OutlinedTextField(
                        value = localContent,
                        onValueChange = { localContent = it },
                        label = { Text("规则集 JSON（source 格式）") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
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
                        tag = tag.trim(),
                        type = type,
                        url = url.trim(),
                        localContent = localContent,
                        downloadDetour = detour.ifBlank { null },
                        ipv4 = ipv4,
                        ipv6 = ipv6,
                    ),
                )
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
