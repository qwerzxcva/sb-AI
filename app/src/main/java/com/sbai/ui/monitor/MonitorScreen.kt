package com.sbai.ui.monitor

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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sbai.service.SbCommandClient
import com.sbai.ui.components.SbGroup
import com.sbai.ui.components.SbItem
import com.sbai.ui.components.SbSpacer
import com.sbai.ui.theme.LocalSbStyleTokens

/** 监控页：内核状态 / 实时日志（libbox CommandClient 通道） */
@Composable
fun MonitorScreen() {
    val context = LocalContext.current
    val tokens = LocalSbStyleTokens.current
    var tab by remember { mutableIntStateOf(0) }

    val status by SbCommandClient.status.collectAsState()
    val logs by SbCommandClient.logs.collectAsState()
    val connected by SbCommandClient.connectedToService.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = tokens.screenHorizontalPadding),
    ) {
        Spacer(Modifier.height(16.dp))
        TabRow(selectedTabIndex = tab) {
            Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("状态") })
            Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("日志（${logs.size}）") })
        }

        if (tab == 0) {
            LazyColumn {
                item { Spacer(Modifier.height(16.dp)) }
                item {
                    SbGroup(title = "内核状态") {
                        item {
                            SbItem(title = "连接", subtitle = "入站 ${status.connectionsIn} · 出站 ${status.connectionsOut}", trailing = {
                                SbStatBadge(if (connected) "已连接" else "未连接", connected)
                            })
                        }
                        item {
                            SbItem(title = "实时速率", subtitle = "↑ ${formatSpeed(status.uplink)} · ↓ ${formatSpeed(status.downlink)}")
                        }
                        item {
                            SbItem(title = "累计流量", subtitle = "↑ ${formatBytes(status.uplinkTotal)} · ↓ ${formatBytes(status.downlinkTotal)}")
                        }
                        item {
                            SbItem(title = "内存", subtitle = formatBytes(status.memory))
                        }
                    }
                }
                item {
                    if (!connected) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "服务未运行时此处无数据。启动 VPN 后自动连接内核。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item { Spacer(Modifier.height(112.dp)) }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        if (connected) "内核日志（最多保留 500 条）" else "服务未运行，无内核日志",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { SbCommandClient.clearLogsLocal() }, enabled = logs.isNotEmpty()) {
                        Icon(Icons.Filled.ClearAll, contentDescription = "清空")
                    }
                }
                val listState = rememberLazyListState()
                LaunchedEffect(logs.size) {
                    if (logs.isNotEmpty()) listState.animateScrollToItem(logs.size - 1)
                }
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(logs, key = { it.hashCode() + it.length }) { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = when {
                                // libbox 日志级别：panic=0 fatal=1 error=2 warn=3 info=4 debug=5 trace=6
                                line.startsWith("[0]") || line.startsWith("[1]") || line.startsWith("[2]") ->
                                    MaterialTheme.colorScheme.error
                                line.startsWith("[3]") -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                        )
                    }
                    item { Spacer(Modifier.height(112.dp)) }
                }
            }
        }
    }
}

@Composable
private fun SbStatBadge(text: String, ok: Boolean) {
    val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var v = bytes.toDouble()
    var i = -1
    do {
        v /= 1024
        i++
    } while (v >= 1024 && i < units.lastIndex)
    return "%.1f %s".format(v, units[i])
}

internal fun formatSpeed(bytesPerSec: Long): String =
    if (bytesPerSec <= 0) "0 B/s" else formatBytes(bytesPerSec) + "/s"
