package com.sbai.ui.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
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
import com.sbai.data.LoadBalanceMode
import com.sbai.data.ProxyNode
import com.sbai.data.RuleStore
import com.sbai.data.StickyHashKey
import com.sbai.data.UrltestMode
import com.sbai.service.SingBoxConfigGenerator
import com.sbai.ui.components.BottomBarClearance
import com.sbai.ui.components.BottomBarController
import com.sbai.ui.components.RestoreBottomBarOnDispose
import com.sbai.ui.components.SbGroup
import com.sbai.ui.components.SbItem
import com.sbai.ui.components.SbSpacer
import com.sbai.ui.components.SbSwitchItem
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 负载均衡设置页（全屏二级页）。
 *
 * 从首页迁出：首页只保留节点选择/订阅/代理组等高频操作，
 * 负载均衡的参数（测速 URL、间隔、tolerance、节点池、粘性会话…）
 * 属于一次性调优项，放设置里更合适。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsLoadBalanceScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val state by store.state.collectAsState()
    val lb = state.loadBalance

    var editingText by remember { mutableStateOf<Triple<String, String, (String) -> Unit>?>(null) }
    var showNodesPicker by remember { mutableStateOf(false) }
    var showConfigPreview by remember { mutableStateOf(false) }

    editingText?.let { (title, initial, apply) ->
        TextEditDialog(
            title = title,
            initial = initial,
            onDismiss = { editingText = null },
            onConfirm = { v -> apply(v); editingText = null },
        )
    }

    BackHandler(enabled = true) { BottomBarController.show(); onBack() }
    RestoreBottomBarOnDispose()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("负载均衡") },
                navigationIcon = {
                    IconButton(onClick = { BottomBarController.show(); onBack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = BottomBarClearance),
        ) {
            item { Spacer(Modifier.height(16.dp)) }

            // ---- 总开关与模式 ----
            item {
                SbGroup(title = "代理出口") {
                    item {
                        SbSwitchItem(
                            title = "启用负载均衡",
                            subtitle = if (lb.enabled) lb.mode.displayName else "关闭",
                            checked = lb.enabled,
                        ) { store.updateLoadBalance(lb.copy(enabled = it)) }
                    }
                    if (lb.enabled) {
                        item {
                            SbItem(
                                title = "模式",
                                subtitle = lb.mode.displayName,
                                onClick = {
                                    val next = when (lb.mode) {
                                        LoadBalanceMode.LATENCY -> LoadBalanceMode.BALANCED
                                        LoadBalanceMode.BALANCED -> LoadBalanceMode.MANUAL
                                        LoadBalanceMode.MANUAL -> LoadBalanceMode.LATENCY
                                    }
                                    store.updateLoadBalance(lb.copy(mode = next))
                                },
                            )
                        }
                        item {
                            SbItem(
                                title = "参与节点",
                                subtitle = if (lb.outbounds.isEmpty()) "全部节点" else lb.outbounds.joinToString(),
                                onClick = { showNodesPicker = true },
                            )
                        }
                    }
                    // 自动模式独立于负载均衡：关掉负载均衡后仍可单独开启自动优选
                    item {
                        SbSwitchItem(
                            title = "自动模式",
                            subtitle = "自动优选延迟最低的出口（可与负载均衡搭配，也可单独使用）",
                            checked = lb.autoEnabled,
                        ) { store.updateLoadBalance(lb.copy(autoEnabled = it)) }
                    }
                    item {
                        SbItem(
                            title = "配置预览",
                            subtitle = "查看生成的 sing-box 配置",
                            onClick = { showConfigPreview = true },
                        )
                    }
                }
                SbSpacer()
            }

            if (lb.enabled) {
                // ---- 高级参数 ----
                item {
                    SbGroup(title = "高级参数") {
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
                        // 选点模式
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("选点模式", style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.height(4.dp))
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    UrltestMode.entries.forEachIndexed { i, mode ->
                                        SegmentedButton(
                                            selected = lb.urltestMode == mode,
                                            onClick = { store.updateLoadBalance(lb.copy(urltestMode = mode)) },
                                            shape = SegmentedButtonDefaults.itemShape(index = i, count = UrltestMode.entries.size),
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
                        // 节点池（仅 round_robin 生效）
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
                                subtitle = "interrupt_exist_connections",
                                checked = lb.interruptExistConnections,
                            ) { store.updateLoadBalance(lb.copy(interruptExistConnections = it)) }
                        }
                    }
                    SbSpacer()
                }
            }
        }
    }

    // ---- 参与节点选择 ----
    if (showNodesPicker) {
        val allNodes = state.proxyNodes.filter { it.enabled && it.outboundJson.isNotBlank() }
        AlertDialog(
            onDismissRequest = { showNodesPicker = false },
            title = { Text("参与节点") },
            text = {
                LazyColumn {
                    items(allNodes.size) { i ->
                        val node = allNodes[i]
                        val selected = lb.outbounds.isEmpty() || nodeTag(node) in lb.outbounds
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                        ) {
                            Checkbox(
                                checked = selected,
                                onCheckedChange = { checked ->
                                    val current = if (lb.outbounds.isEmpty()) allNodes.mapNotNull(::nodeTag).toSet() else lb.outbounds.toSet()
                                    val tag = nodeTag(node) ?: return@Checkbox
                                    val next = if (checked) current + tag else current - tag
                                    store.updateLoadBalance(
                                        lb.copy(outbounds = if (next.size == allNodes.size) emptyList() else next.toList()),
                                    )
                                },
                            )
                            Text(node.name.ifBlank { nodeTag(node) ?: "（未命名）" })
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showNodesPicker = false }) { Text("完成") } },
        )
    }

    // ---- 配置预览 ----
    if (showConfigPreview) {
        val preview = remember(state) { SingBoxConfigGenerator.generate(state) }
        AlertDialog(
            onDismissRequest = { showConfigPreview = false },
            title = { Text("配置预览") },
            text = {
                Column {
                    Text(
                        "以下是当前配置生成的 sing-box JSON（含负载均衡出站组）。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = preview,
                        onValueChange = {},
                        readOnly = true,
                        modifier = Modifier.fillMaxWidth().height(320.dp),
                        textStyle = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { showConfigPreview = false }) { Text("关闭") } },
        )
    }
}

private fun nodeTag(node: ProxyNode): String? = runCatching {
    val element = kotlinx.serialization.json.Json.parseToJsonElement(node.outboundJson)
    element.jsonObject["tag"]?.let { it as kotlinx.serialization.json.JsonPrimitive }?.content
}.getOrNull()

@Composable
private fun TextEditDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = if (title.contains("毫秒") || title.contains("N")) KeyboardType.Number else KeyboardType.Text,
                ),
            )
        },
        confirmButton = { Button(onClick = { onConfirm(text) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

