package com.sbai.ui.monitor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ClearAll
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudOff
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.sbai.service.SbCommandClient
import com.sbai.service.VpnRuntimeState
import com.sbai.ui.components.BottomBarClearance
import com.sbai.ui.components.SbGroup
import com.sbai.ui.components.SbItem
import com.sbai.ui.theme.LocalSbStyleTokens

/** 监控页：内核状态 / 实时日志 / 连接（libbox CommandClient 通道） */
@Composable
fun MonitorScreen() {
    val context = LocalContext.current
    val tokens = LocalSbStyleTokens.current
    var tab by remember { mutableIntStateOf(0) }

    val status by SbCommandClient.status.collectAsState()
    val logs by SbCommandClient.logs.collectAsState()
    val connections by SbCommandClient.connections.collectAsState()
    val connected by SbCommandClient.connectedToService.collectAsState()
    // 跨进程遥测快照（UI 主进程通过共享文件读取 :core 发布的 CommandClient 数据）。
    // 刷新时机：进入页面时 + tab 切换时 + 每 3s 轮询（覆盖冷启动时文件尚未落盘的窗口）。
    var telemetry by remember { mutableStateOf<VpnRuntimeState.TelemetrySnapshot?>(null) }
    LaunchedEffect(Unit) {
        VpnRuntimeState.refreshTelemetry(context)
        telemetry = VpnRuntimeState.telemetry.value
        while (true) {
            kotlinx.coroutines.delay(3_000L)
            VpnRuntimeState.refreshTelemetry(context)
            telemetry = VpnRuntimeState.telemetry.value
        }
    }
    LaunchedEffect(tab) { VpnRuntimeState.refreshTelemetry(context); telemetry = VpnRuntimeState.telemetry.value }
    var query by remember { mutableStateOf("") }

    Box(modifier = Modifier.fillMaxSize()) {
        // 背景渐变，避免一片死黑
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(
                    androidx.compose.ui.graphics.Brush.verticalGradient(
                        listOf(
                            MaterialTheme.colorScheme.surfaceContainerHigh,
                            MaterialTheme.colorScheme.surface.copy(alpha = 0f),
                        ),
                    ),
                ),
        )

        // 全白色背景（用户要求）
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = tokens.screenHorizontalPadding),
        ) {
            Spacer(Modifier.height(16.dp))
            Text("监控", style = MaterialTheme.typography.headlineLarge)
            Text(
                if (connected) "sing-box 内核实时状态" else "启动 VPN 后显示内核数据",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))

            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("状态") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("连接（${connections.size.coerceAtLeast(telemetry?.connections ?: 0)}）") })
                Tab(selected = tab == 2, onClick = { tab = 2 }, text = { Text("日志（${logs.size.coerceAtLeast(telemetry?.logTail?.size ?: 0)}）") })
            }

        if (tab == 0) {
            LazyColumn {
                item { Spacer(Modifier.height(16.dp)) }

                // 连接状态大卡（有色彩层次，避免「黑的要死」）
                // 跨进程时：telemetry != null → 显示共享快照（含 uptime/publicIp/connections）；否则 fallback 到 CommandClient
                item {
                    StatusHeroCard(
                        connected = connected || telemetry != null,
                        status = telemetry?.toStatus() ?: status,
                        uptimeSec = telemetry?.uptimeSec,
                        publicIp = telemetry?.publicIp,
                        loadingHint = telemetry == null && !connected,
                    )
                    Spacer(Modifier.height(16.dp))
                }

                // 实时流量卡（跨进程时优先用共享快照的流量）
                item {
                    TrafficCard(status = telemetry?.toStatus() ?: status)
                    Spacer(Modifier.height(16.dp))
                }

                // sb-AI 统计页核心：按路由规则聚合流量
                item {
                    TrafficByRuleCard(connections = connections)
                    Spacer(Modifier.height(16.dp))
                }

                // 按出口聚合
                item {
                    TrafficByOutboundCard(connections = connections)
                    Spacer(Modifier.height(16.dp))
                }

                item {
                    val effStatus = telemetry?.toStatus() ?: status
                    val connCount = (telemetry?.connections ?: 0).coerceAtLeast(status.connectionsIn + status.connectionsOut)
                    SbGroup(title = "内核详情") {
                        item {
                            SbItem(title = "连接数", subtitle = "活跃 ${connCount} · 出站 ${effStatus.connectionsOut}")
                        }
                        item {
                            SbItem(title = "内存占用", subtitle = formatBytes(effStatus.memory))
                        }
                        item {
                            SbItem(title = "累计流量", subtitle = "↑ ${formatBytes(effStatus.uplinkTotal)} · ↓ ${formatBytes(effStatus.downlinkTotal)}")
                        }
                    }
                }
                item {
                    if (!connected && telemetry == null) {
                        Spacer(Modifier.height(16.dp))
                        Text(
                            "服务未运行时此处无数据。启动 VPN 后自动连接内核。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                item { Spacer(Modifier.height(BottomBarClearance)) }
            }
        } else if (tab == 1) {
            // ---- 连接列表（三大代理交集功能，sb-AI connections_screen 基准）----
            val filtered = remember(connections, query) {
                if (query.isBlank()) connections
                else connections.filter {
                    it.domain.contains(query, true) || it.destination.contains(query, true) ||
                        it.outbound.contains(query, true) || it.processPath.contains(query, true) ||
                        it.rule.contains(query, true)
                }
            }
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("搜索域名 / 出口 / 进程 / 规则") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(
                        onClick = { SbCommandClient.closeAllConnections() },
                        enabled = connected && connections.isNotEmpty(),
                    ) {
                        Icon(Icons.Filled.Close, contentDescription = "关闭全部连接")
                    }
                }
                val tele = telemetry
                if (!connected) {
                    if (!tele?.connList.isNullOrEmpty()) {
                        // 跨进程：主进程拿不到逐条关闭通道，但能看 :core 发布的连接列表快照
                        Text(
                            "跨进程遥测（最近 ${tele!!.connList.size} 条，完整控制需进程内通道）",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(8.dp))
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(6.dp),
                            modifier = Modifier.fillMaxSize(),
                        ) {
                            items(tele!!.connList, key = { it.id }) { c ->
                                TelemetryConnectionCard(entry = c)
                            }
                            item { Spacer(Modifier.height(112.dp)) }
                        }
                    } else {
                        Text(
                            "服务未运行，无连接数据。",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (filtered.isEmpty()) {
                    Text(
                        if (connections.isEmpty()) "当前无活跃连接。" else "无匹配连接。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxSize(),
                    ) {
                        items(filtered, key = { it.id }) { c ->
                            ConnectionCard(
                                entry = c,
                                onClose = { SbCommandClient.closeConnection(c.id) },
                            )
                        }
                        item { Spacer(Modifier.height(112.dp)) }
                    }
                }
            }
        } else {
            Column(Modifier.fillMaxSize()) {
                val teleLogs = telemetry?.logTail.orEmpty()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val effLogCount = logs.size.coerceAtLeast(teleLogs.size)
                    Text(
                        when {
                            connected -> "内核日志（最多保留 500 条）"
                            effLogCount > 0 -> "跨进程遥测日志（最近 ${effLogCount} 条）"
                            else -> "服务未运行，无内核日志"
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { SbCommandClient.clearLogsLocal() }, enabled = logs.isNotEmpty()) {
                        Icon(Icons.Filled.ClearAll, contentDescription = "清空")
                    }
                }
                // 跨进程回退：主进程 in-process CommandClient 无数据时，用共享快照的 logTail
                val effLogs: List<SbCommandClient.LogLine> =
                    if (logs.isNotEmpty()) logs
                    else teleLogs.mapIndexed { i, t -> SbCommandClient.LogLine(i.toLong(), t) }
                val listState = rememberLazyListState()
                LaunchedEffect(effLogs.size) {
                    if (effLogs.isNotEmpty()) listState.animateScrollToItem(effLogs.size - 1)
                }
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    modifier = Modifier.fillMaxSize(),
                ) {
                    items(effLogs, key = { it.seq }) { line ->
                        Text(
                            line.text,
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = when {
                                // libbox 日志级别：panic=0 fatal=1 error=2 warn=3 info=4 debug=5 trace=6
                                line.text.startsWith("[0]") || line.text.startsWith("[1]") || line.text.startsWith("[2]") ->
                                    MaterialTheme.colorScheme.error
                                line.text.startsWith("[3]") -> MaterialTheme.colorScheme.tertiary
                                line.text.startsWith("[4]") -> MaterialTheme.colorScheme.onSurface
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
}

@Composable
private fun SbStatBadge(text: String, ok: Boolean) {
    val color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
    Text(text, style = MaterialTheme.typography.labelMedium, color = color)
}

@Composable
private fun ConnectionCard(entry: SbCommandClient.ConnectionEntry, onClose: () -> Unit) {
    val title = entry.domain.ifBlank { entry.destination }.ifBlank { "(unknown)" }
    val app = entry.processPath.substringAfterLast('/').ifBlank { entry.userName }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                MaterialTheme.shapes.small,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                Text(
                    buildString {
                        append(entry.network.uppercase())
                        if (entry.protocol.isNotBlank()) append(" · ${entry.protocol}")
                        if (app.isNotBlank()) append(" · $app")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(onClick = onClose) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "关闭连接",
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (entry.outbound.isNotBlank()) {
                Text(
                    "→ ${entry.outbound}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (entry.rule.isNotBlank()) {
                Text(
                    "rule: ${entry.rule}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "↑${formatBytes(entry.uplinkTotal)} ↓${formatBytes(entry.downlinkTotal)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
}

/**
 * 跨进程连接卡片：渲染 :core 发布的 [VpnRuntimeState.ConnBrief] 快照。
 * 与 [ConnectionCard] 布局一致，但无关闭按钮（主进程无法向 :core 下发 close 命令），
 * 用于监控页"连接"tab 的跨进程回退展示，使 connList 数据真正被消费（非死代码）。
 */
@Composable
private fun TelemetryConnectionCard(entry: VpnRuntimeState.ConnBrief) {
    val title = entry.domain.ifBlank { entry.destination }.ifBlank { "(unknown)" }
    val app = entry.processPath.substringAfterLast('/').ifBlank { "" }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceContainer,
                MaterialTheme.shapes.small,
            )
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                )
                if (app.isNotBlank()) {
                    Text(
                        app,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (entry.outbound.isNotBlank()) {
                Text(
                    "→ ${entry.outbound}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (entry.rule.isNotBlank()) {
                Text(
                    "rule: ${entry.rule}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
            }
            Text(
                "↑${formatBytes(entry.uplinkTotal)} ↓${formatBytes(entry.downlinkTotal)}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.secondary,
            )
        }
    }
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

internal fun formatUptime(sec: Long): String = buildString {
    val s = sec.coerceAtLeast(0L)
    if (s >= 3600) {
        append(s / 3600).append("h")
        append((s % 3600) / 60).append("m")
    } else if (s >= 60) {
        append(s / 60).append("m")
        append(s % 60).append("s")
    } else {
        append(s).append("s")
    }
}

/** 把共享遥测快照转回 DashboardStatus（供 StatusHeroCard / TrafficCard / 首页流量卡复用，无需重写组件）。 */
internal fun VpnRuntimeState.TelemetrySnapshot?.toStatus(): SbCommandClient.DashboardStatus = when {
    this == null -> SbCommandClient.DashboardStatus()
    else -> SbCommandClient.DashboardStatus(
        memory = this.memory,
        connectionsIn = 0,
        connectionsOut = 0,
        uplink = this.uplink,
        downlink = this.downlink,
        uplinkTotal = this.uplinkTotal,
        downlinkTotal = this.downlinkTotal,
        connected = true,
    )
}

// ---------------------------------------------------------------------------
// sb-AI 统计页：按规则 / 按出口聚合流量
// ---------------------------------------------------------------------------

private data class TrafficAgg(
    val key: String,
    val count: Int,
    val up: Long,
    val down: Long,
)

private fun aggregate(
    connections: List<SbCommandClient.ConnectionEntry>,
    keyOf: (SbCommandClient.ConnectionEntry) -> String,
): List<TrafficAgg> = connections
    .groupBy { keyOf(it).ifBlank { "（未匹配）" } }
    .map { (k, list) ->
        TrafficAgg(
            key = k,
            count = list.size,
            up = list.sumOf { it.uplinkTotal },
            down = list.sumOf { it.downlinkTotal },
        )
    }
    .sortedByDescending { it.up + it.down }

@Composable
private fun TrafficByRuleCard(connections: List<SbCommandClient.ConnectionEntry>) {
    AggregationCard(
        title = "按路由规则统计",
        subtitle = "${connections.size} 条活跃连接",
        rows = aggregate(connections) { it.rule },
        emptyText = "暂无连接数据",
    )
}

@Composable
private fun TrafficByOutboundCard(connections: List<SbCommandClient.ConnectionEntry>) {
    AggregationCard(
        title = "按出口统计",
        subtitle = "各出口承载的流量",
        rows = aggregate(connections) { it.outbound },
        emptyText = "暂无连接数据",
    )
}

@Composable
private fun AggregationCard(
    title: String,
    subtitle: String,
    rows: List<TrafficAgg>,
    emptyText: String,
) {
    val colors = MaterialTheme.colorScheme
    val maxTotal = rows.maxOfOrNull { it.up + it.down }?.coerceAtLeast(1) ?: 1

    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainerLow,
        tonalElevation = 2.dp,
    ) {
        Column(Modifier.padding(18.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, color = colors.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            Spacer(Modifier.height(14.dp))

            if (rows.isEmpty()) {
                Text(emptyText, style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            } else {
                rows.take(8).forEach { row ->
                    Column(Modifier.padding(vertical = 6.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                row.key,
                                style = MaterialTheme.typography.bodyMedium,
                                color = colors.onSurface,
                                modifier = Modifier.weight(1f),
                                maxLines = 1,
                            )
                            Text(
                                "${row.count} 条",
                                style = MaterialTheme.typography.labelSmall,
                                color = colors.onSurfaceVariant,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        // 双向占比条：上传 tertiary、下载 primary
                        val upRatio = row.up.toFloat() / maxTotal
                        val downRatio = row.down.toFloat() / maxTotal
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .background(colors.surfaceContainerHighest, CircleShape),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(upRatio.coerceIn(0f, 1f))
                                    .height(6.dp)
                                    .background(colors.tertiary, CircleShape),
                            )
                        }
                        Spacer(Modifier.height(2.dp))
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .background(colors.surfaceContainerHighest, CircleShape),
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(downRatio.coerceIn(0f, 1f))
                                    .height(6.dp)
                                    .background(colors.primary, CircleShape),
                            )
                        }
                        Spacer(Modifier.height(3.dp))
                        Text(
                            "↑ ${formatBytes(row.up)}    ↓ ${formatBytes(row.down)}",
                            style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 状态大卡 / 流量卡（ClashFest 首页观感：有色彩层次，避免「黑的要死」）
// ---------------------------------------------------------------------------

@Composable
private fun StatusHeroCard(
    connected: Boolean,
    status: SbCommandClient.DashboardStatus,
    uptimeSec: Long? = null,
    publicIp: String? = "",
    loadingHint: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    // 与首页等其他页面保持一致：使用 surfaceContainer，避免 primaryContainer 造成视觉反差
    val bg = colors.surfaceContainer
    val fg = colors.onSurface
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        color = bg,
        contentColor = fg,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    if (connected) "内核已连接" else "内核未连接",
                    style = MaterialTheme.typography.headlineSmall,
                    color = fg,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    buildString {
                        append(if (connected) "sing-box 运行中 · " else "启动 VPN 后自动连接")
                        if (uptimeSec != null && uptimeSec > 0) append("运行 ${formatUptime(uptimeSec)}")
                        if (publicIp?.isNotBlank() == true) append(" · IP: $publicIp")
                        if (loadingHint) append("（共享数据加载中…）")
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = fg.copy(alpha = 0.8f),
                )
            }
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .background(
                        if (connected) colors.primary else colors.outlineVariant,
                        CircleShape,
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (connected) Icons.Filled.CheckCircle else Icons.Filled.CloudOff,
                    contentDescription = null,
                    tint = if (connected) colors.onPrimary else colors.onSurfaceVariant,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

@Composable
private fun TrafficCard(status: SbCommandClient.DashboardStatus) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        color = colors.surfaceContainer,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TrafficColumn(
                label = "上传",
                speed = formatSpeed(status.uplink),
                total = formatBytes(status.uplinkTotal),
                icon = Icons.Filled.ArrowUpward,
                tint = colors.tertiary,
            )
            TrafficColumn(
                label = "下载",
                speed = formatSpeed(status.downlink),
                total = formatBytes(status.downlinkTotal),
                icon = Icons.Filled.ArrowDownward,
                tint = colors.secondary,
            )
        }
    }
}

@Composable
private fun TrafficColumn(
    label: String,
    speed: String,
    total: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    tint: Color,
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        Spacer(Modifier.height(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(speed, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text("累计 $total", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
