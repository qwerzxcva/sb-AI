package com.sbai.ui.balance

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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sbai.data.LoadBalanceMode
import com.sbai.data.RuleStore

/**
 * 负载均衡页（参考 LxBox 的负载均衡模式）：
 *  - 延迟优选：urltest(tolerance=0)
 *  - 均衡负载：urltest(tolerance>0)，节点间分摊
 *  - 手动切换：selector
 *  - “自动”模式：在 LB 组之上再套一层自动优选，可与 LB 模式搭配
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun LoadBalanceScreen() {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val state by store.state.collectAsState()
    val lb = state.loadBalance

    val nodeTags = state.proxyNodes.filter { it.enabled }
        .map { it.name.ifBlank { it.id } }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            TopAppBar(title = { Text("负载均衡") })
        }

        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("启用负载均衡", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "对整个代理出口应用负载均衡策略",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = lb.enabled,
                        onCheckedChange = { store.updateLoadBalance(lb.copy(enabled = it)) },
                    )
                }
            }
        }

        if (lb.enabled) {
            item {
                Text("模式", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    LoadBalanceMode.entries.forEachIndexed { i, mode ->
                        SegmentedButton(
                            selected = lb.mode == mode,
                            onClick = { store.updateLoadBalance(lb.copy(mode = mode)) },
                            shape = SegmentedButtonDefaults.itemShape(index = i, count = LoadBalanceMode.entries.size),
                        ) { Text(mode.displayName) }
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    when (lb.mode) {
                        LoadBalanceMode.LATENCY -> "urltest(tolerance=0)：始终选择延迟最低的节点"
                        LoadBalanceMode.BALANCED -> "urltest(tolerance>0)：在可接受延迟内分摊节点，避免频繁切换"
                        LoadBalanceMode.MANUAL -> "selector：手动在组内切换出口"
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("自动模式", style = MaterialTheme.typography.titleSmall)
                        Text(
                            "在负载均衡组之上再自动优选；与负载均衡模式搭配使用",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(
                        checked = lb.autoEnabled,
                        onCheckedChange = { store.updateLoadBalance(lb.copy(autoEnabled = it)) },
                    )
                }
            }

            if (lb.mode != LoadBalanceMode.MANUAL) {
                item {
                    NumberField(
                        label = "测速 URL",
                        value = lb.checkUrl,
                        keyboardType = KeyboardType.Uri,
                    ) { v -> store.updateLoadBalance(lb.copy(checkUrl = v)) }
                }
                item {
                    NumberField(
                        label = "测速间隔（秒）",
                        value = lb.intervalSeconds.toString(),
                        keyboardType = KeyboardType.Number,
                    ) { v -> v.toIntOrNull()?.let { n -> store.updateLoadBalance(lb.copy(intervalSeconds = n)) } }
                }
            }

            if (lb.mode == LoadBalanceMode.BALANCED) {
                item {
                    NumberField(
                        label = "tolerance（毫秒，延迟差在此范围内不切换）",
                        value = lb.toleranceMs.toString(),
                        keyboardType = KeyboardType.Number,
                    ) { v -> v.toIntOrNull()?.let { n -> store.updateLoadBalance(lb.copy(toleranceMs = n)) } }
                }
                item {
                    NumberField(
                        label = "idle_timeout（秒）",
                        value = lb.idleTimeoutSeconds.toString(),
                        keyboardType = KeyboardType.Number,
                    ) { v -> v.toIntOrNull()?.let { n -> store.updateLoadBalance(lb.copy(idleTimeoutSeconds = n)) } }
                }
            }

            if (lb.mode != LoadBalanceMode.MANUAL) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text("中断已存在连接", style = MaterialTheme.typography.titleSmall)
                            Text(
                                "切换节点时断开旧连接",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = lb.interruptExistConnections,
                            onCheckedChange = { store.updateLoadBalance(lb.copy(interruptExistConnections = it)) },
                        )
                    }
                }
            }

            item {
                Text("参与节点（留空 = 全部代理节点）", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(8.dp))
                if (nodeTags.isEmpty()) {
                    Text(
                        "暂无启用节点，请先在首页添加节点。",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                }
            }
        }

        item { Spacer(Modifier.height(32.dp)) }
    }
}

@Composable
private fun NumberField(
    label: String,
    value: String,
    keyboardType: KeyboardType,
    onChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth(),
    )
}
