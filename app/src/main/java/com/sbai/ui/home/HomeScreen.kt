package com.sbai.ui.home

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import com.sbai.data.ProxyNode
import com.sbai.data.RuleStore
import com.sbai.service.SbAiVpnService
import com.sbai.service.SingBoxConfigGenerator

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val state by store.state.collectAsState()
    val status by SbAiVpnService.status.collectAsState()

    var showNodeEditor by remember { mutableStateOf<ProxyNode?>(null) }
    var showConfigPreview by remember { mutableStateOf(false) }

    val vpnPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            startVpn(context)
        }
    }

    Scaffold(
        topBar = { TopAppBar(title = { Text("sb-AI") }) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = {
                    when (status) {
                        is SbAiVpnService.ServiceStatus.Running -> stopVpn(context)
                        else -> {
                            val intent = VpnService.prepare(context)
                            if (intent != null) {
                                vpnPermissionLauncher.launch(intent)
                            } else {
                                startVpn(context)
                            }
                        }
                    }
                },
                icon = {
                    Icon(
                        if (status is SbAiVpnService.ServiceStatus.Running) Icons.Filled.Stop else Icons.Filled.PlayArrow,
                        contentDescription = null,
                    )
                },
                text = {
                    Text(
                        when (status) {
                            is SbAiVpnService.ServiceStatus.Running -> "停止"
                            is SbAiVpnService.ServiceStatus.Starting -> "启动中…"
                            is SbAiVpnService.ServiceStatus.Stopping -> "停止中…"
                            is SbAiVpnService.ServiceStatus.Error -> "启动（上次失败）"
                            else -> "启动"
                        },
                    )
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                StatusCard(status)
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("节点（${state.proxyNodes.size}）", style = MaterialTheme.typography.titleMedium)
                    Row {
                        OutlinedButton(onClick = { showConfigPreview = true }) { Text("预览配置") }
                        Spacer(Modifier.padding(4.dp))
                        Button(onClick = { showNodeEditor = ProxyNode() }) {
                            Icon(Icons.Filled.Add, contentDescription = null)
                            Text("添加")
                        }
                    }
                }
            }

            items(state.proxyNodes, key = { it.id }) { node ->
                NodeCard(
                    node = node,
                    onToggle = { store.upsertProxyNode(node.copy(enabled = !node.enabled)) },
                    onEdit = { showNodeEditor = node },
                    onDelete = { store.deleteProxyNode(node.id) },
                )
            }

            if (state.proxyNodes.isEmpty()) {
                item {
                    Text(
                        "暂无节点。点击「添加」粘贴 sing-box outbound JSON。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    showNodeEditor?.let { node ->
        NodeEditorDialog(
            initial = node,
            onDismiss = { showNodeEditor = null },
            onSave = {
                store.upsertProxyNode(it)
                showNodeEditor = null
            },
        )
    }

    if (showConfigPreview) {
        ConfigPreviewDialog(
            config = runCatching { SingBoxConfigGenerator.generate(state) }
                .getOrElse { "生成失败: ${it.message}" },
            onDismiss = { showConfigPreview = false },
        )
    }
}

@Composable
private fun StatusCard(status: SbAiVpnService.ServiceStatus) {
    val (text, color) = when (status) {
        is SbAiVpnService.ServiceStatus.Running ->
            "运行中" to MaterialTheme.colorScheme.primary
        is SbAiVpnService.ServiceStatus.Starting ->
            "启动中…" to MaterialTheme.colorScheme.tertiary
        is SbAiVpnService.ServiceStatus.Stopping ->
            "停止中…" to MaterialTheme.colorScheme.tertiary
        is SbAiVpnService.ServiceStatus.Error ->
            "错误: ${status.message}" to MaterialTheme.colorScheme.error
        else -> "已停止" to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("服务状态", style = MaterialTheme.typography.labelMedium)
            Spacer(Modifier.height(4.dp))
            Text(text, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = color)
        }
    }
}

@Composable
private fun NodeCard(
    node: ProxyNode,
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
                Text(node.name.ifBlank { "未命名节点" }, style = MaterialTheme.typography.titleSmall)
                Text(
                    node.outboundJson.take(80),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            Switch(checked = node.enabled, onCheckedChange = { onToggle() })
            IconButton(onClick = onEdit) { Text("✎") }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, contentDescription = "删除") }
        }
    }
}

@Composable
private fun NodeEditorDialog(
    initial: ProxyNode,
    onDismiss: () -> Unit,
    onSave: (ProxyNode) -> Unit,
) {
    var name by remember { mutableStateOf(initial.name) }
    var json by remember { mutableStateOf(initial.outboundJson) }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (initial.outboundJson.isBlank()) "添加节点" else "编辑节点") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("名称") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = json,
                    onValueChange = { json = it },
                    label = { Text("sing-box outbound JSON") },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 4,
                    placeholder = { Text("{\"type\":\"vless\",\"tag\":\"...\",...}") },
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val parsed = runCatching {
                    Json.parseToJsonElement(json).jsonObject
                }.getOrElse {
                    error = "JSON 无效: ${it.message}"
                    return@TextButton
                }
                if (parsed["type"] == null || parsed["tag"] == null) {
                    error = "outbound 必须包含 type 与 tag"
                    return@TextButton
                }
                onSave(initial.copy(name = name, outboundJson = json))
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

@Composable
private fun ConfigPreviewDialog(config: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("sing-box 配置预览") },
        text = {
            OutlinedTextField(
                value = config,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier.fillMaxWidth(),
                minLines = 12,
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}

private fun startVpn(context: android.content.Context) {
    val intent = Intent(context, SbAiVpnService::class.java).setAction(SbAiVpnService.ACTION_START)
    ContextCompat.startForegroundService(context, intent)
}

private fun stopVpn(context: android.content.Context) {
    val intent = Intent(context, SbAiVpnService::class.java).setAction(SbAiVpnService.ACTION_STOP)
    context.startService(intent)
}
