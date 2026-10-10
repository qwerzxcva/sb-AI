package com.sbai.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material.icons.filled.Router
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.sbai.data.LoadBalanceMode
import com.sbai.data.RuleStore
import com.sbai.data.StickyHashKey
import com.sbai.data.UrltestMode
import com.sbai.service.SingBoxConfigGenerator
import com.sbai.ui.components.SbCollapsibleGroup
import com.sbai.ui.components.SbGroup
import com.sbai.ui.components.SbItem
import com.sbai.ui.components.SbSpacer
import com.sbai.ui.components.SbSwitchItem
import com.sbai.ui.theme.LocalSbStyleTokens
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext

/**
 * 负载均衡（设置二级页）。
 * 首页只保留出站模式与订阅区「自动模式」；总开关、模式、参与节点、
 * 配置预览与高级参数全部在此页。
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsLoadBalanceScreen(navController: NavHostController) {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val tokens = LocalSbStyleTokens.current
    val loadBalance by remember(store) {
        store.state.map { it.loadBalance }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.loadBalance })
    val proxyNodes by remember(store) {
        store.state.map { it.proxyNodes }.distinctUntilChanged()
    }.collectAsState(initial = remember(store) { store.state.value.proxyNodes })
    val lb = loadBalance

    var showConfigPreview by remember { mutableStateOf(false) }
    var showModeDialog by remember { mutableStateOf(false) }
    var showNodesPicker by remember { mutableStateOf(false) }
    var editingText by remember { mutableStateOf<Triple<String, String, (String) -> Unit>?>(null) }

    Scaffold(
        topBar = { SettingsSubTopBar(navController, "负载均衡") },
        containerColor = Color.Transparent,
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = tokens.screenHorizontalPadding),
        ) {
            item { Spacer(Modifier.height(16.dp)) }
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
            item { Spacer(Modifier.height(80.dp)) }
        }
    }

    if (showConfigPreview) {
        var previewConfig by remember { mutableStateOf("正在生成配置…") }
        LaunchedEffect(Unit) {
            val snapshot = store.state.value
            previewConfig = try {
                withContext(Dispatchers.Default) {
                    SingBoxConfigGenerator.generate(snapshot)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                "生成失败: ${e.message}"
            }
        }
        LoadBalanceConfigPreviewDialog(
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

    if (showNodesPicker) {
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
        SettingsTextEditDialog(
            title = title,
            initial = initialValue,
            onDismiss = { editingText = null },
            onDone = { onDone(it); editingText = null },
        )
    }
}

@Composable
private fun LoadBalanceConfigPreviewDialog(config: String, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("sing-box 配置预览") },
        text = {
            OutlinedTextField(
                value = config,
                onValueChange = {},
                readOnly = true,
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 240.dp),
                minLines = 12,
            )
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭") } },
    )
}
