package com.sbai.ui.settings

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Height
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.NetworkCheck
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Preview
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.Http
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Balance
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.sbai.data.AppState
import com.sbai.data.ConfigProfile
import com.sbai.data.LogLevel
import com.sbai.data.OverridePriority
import com.sbai.data.PerAppProxyMode
import com.sbai.data.RuleStore
import com.sbai.data.TunStack
import com.sbai.service.BackupManager
import com.sbai.service.SingBoxConfigGenerator
import com.sbai.data.ThemeMode
import com.sbai.ui.components.AppPickerDialog
import com.sbai.ui.components.SbGroup
import com.sbai.ui.components.SbItem
import com.sbai.ui.components.SbSpacer
import com.sbai.ui.components.SbSwitchItem
import com.sbai.ui.theme.LocalSbStyleTokens
import kotlinx.serialization.json.Json

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(onOpenDnsAdvanced: () -> Unit = {}, onOpenRouteResources: () -> Unit = {}) {
    val context = LocalContext.current
    val store = remember { RuleStore.get(context) }
    val state by store.state.collectAsState()
    val settings = state.settings
    val tokens = LocalSbStyleTokens.current

    var showAppPicker by remember { mutableStateOf(false) }
    var showCustomConfigEditor by remember { mutableStateOf(false) }
    var showLoadBalance by remember { mutableStateOf(false) }
    var showOverridePreview by remember { mutableStateOf(false) }
    var backupMessage by remember { mutableStateOf<String?>(null) }
    var editingText by remember { mutableStateOf<Triple<String, String, (String) -> Unit>?>(null) }
    var splitDomainsText by remember { mutableStateOf("") }
    var showProfileDialog by remember { mutableStateOf(false) }
    var profileNameInput by remember { mutableStateOf("") }
    var editingProfile by remember { mutableStateOf<ConfigProfile?>(null) }
    // ---- 资源管理编辑状态 ----
    // ---- 备份导入模式选择（merge / replace） ----
    var pendingImport by remember { mutableStateOf<AppState?>(null) }

    val backupJson = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/json"),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            context.contentResolver.openOutputStream(uri)?.use { out ->
                out.write(backupJson.encodeToString(AppState.serializer(), state).toByteArray())
            }
            backupMessage = "已导出配置"
        }.onFailure { backupMessage = "导出失败: ${it.message}" }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        runCatching {
            val text = context.contentResolver.openInputStream(uri)?.use { input ->
                // 上限 8MB，防止超大/畸形文件撑爆内存
                val buf = ByteArray(8192)
                val sb = StringBuilder()
                var total = 0
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    total += n
                    if (total > MAX_BACKUP_BYTES) error("备份文件超过 8MB，已中止")
                    sb.append(String(buf, 0, n, Charsets.UTF_8))
                }
                sb.toString()
            } ?: error("空文件")
            val imported = backupJson.decodeFromString(AppState.serializer(), text)
            // 不立即应用，先弹窗让用户选「合并导入」还是「覆盖导入」
            pendingImport = imported
        }.onFailure { backupMessage = "导入失败: ${it.message}" }
    }

    Scaffold { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = tokens.screenHorizontalPadding),
        ) {
            item { Spacer(Modifier.height(16.dp)) }

            // ---- 外观 ----
            item {
                SbGroup(title = "外观") {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text("主题", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(8.dp))
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                ThemeMode.entries.forEachIndexed { i, mode ->
                                    SegmentedButton(
                                        selected = settings.themeMode == mode,
                                        onClick = { store.updateSettings(settings.copy(themeMode = mode)) },
                                        shape = SegmentedButtonDefaults.itemShape(index = i, count = ThemeMode.entries.size),
                                    ) { Text(mode.displayName) }
                                }
                            }
                        }
                    }
                    item {
                        SbSwitchItem(
                            title = "Material You 动态取色",
                            subtitle = "跟随系统壁纸配色（Android 12+）",
                            icon = Icons.Filled.Palette,
                            checked = settings.dynamicColor,
                        ) { store.updateSettings(settings.copy(dynamicColor = it)) }
                    }
                }
                SbSpacer()
            }

            // ---- 负载均衡（全屏二级页）----
            item {
                SbGroup(title = "代理出口") {
                    item {
                        SbItem(
                            title = "负载均衡",
                            subtitle = if (state.loadBalance.enabled) {
                                "已开启 · ${state.loadBalance.mode.displayName}"
                            } else {
                                "关闭"
                            },
                            icon = Icons.Filled.Balance,
                            onClick = { showLoadBalance = true },
                        )
                    }
                }
                SbSpacer()
            }

            // ---- 内核 ----
            item {
                SbGroup(title = "内核") {
                    item {
                        SbSwitchItem(
                            title = "IPv6 路由",
                            subtitle = "TUN 同时承载 IPv6 流量",
                            icon = Icons.Filled.NetworkCheck,
                            checked = settings.ipv6Route,
                        ) { store.updateSettings(settings.copy(ipv6Route = it)) }
                    }
                    item {
                        SbSwitchItem(
                            title = "自动检测网卡",
                            subtitle = "auto_detect_interface",
                            icon = Icons.Filled.Route,
                            checked = settings.autoDetectInterface,
                        ) { store.updateSettings(settings.copy(autoDetectInterface = it)) }
                    }
                    item {
                        SbSwitchItem(
                            title = "严格路由",
                            subtitle = "strict_route，防止流量泄漏",
                            icon = Icons.Filled.Security,
                            checked = settings.strictRoute,
                        ) { store.updateSettings(settings.copy(strictRoute = it)) }
                    }
                    item {
                        SbSwitchItem(
                            title = "系统 HTTP 代理下发",
                            subtitle = "platform.http_proxy（Android 10+）：尊重系统代理的应用走本地代理，不进隧道",
                            icon = Icons.Filled.Http,
                            checked = settings.tunHttpProxyEnabled,
                        ) { store.updateSettings(settings.copy(tunHttpProxyEnabled = it)) }
                    }
                    item {
                        Column(Modifier.padding(16.dp)) {
                            OutlinedTextField(
                                value = settings.mtu.toString(),
                                onValueChange = { v -> v.toIntOrNull()?.let { n -> store.updateSettings(settings.copy(mtu = n)) } },
                                label = { Text("MTU") },
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    // TUN 网络栈：只保留 tsbAI / mixed / system / gVisor 四种
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("TUN 网络栈", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(8.dp))
                            val stackOptions = listOf(
                                com.sbai.data.TunStack.TSB_AI,
                                com.sbai.data.TunStack.MIXED,
                                com.sbai.data.TunStack.SYSTEM,
                                com.sbai.data.TunStack.GVISOR,
                            )
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                stackOptions.forEachIndexed { i, stack ->
                                    SegmentedButton(
                                        selected = settings.tunStack == stack,
                                        onClick = { store.updateSettings(settings.copy(tunStack = stack)) },
                                        shape = SegmentedButtonDefaults.itemShape(index = i, count = stackOptions.size),
                                    ) { Text(stack.displayName) }
                                }
                            }
                            val isGoStack = settings.tunStack == com.sbai.data.TunStack.TSB_AI
                            Text(
                                when {
                                    isGoStack ->
                                        "tsbAI = sb-lxf 内核的 TUN 栈，合并了 Go 栈实现（splice/GSO/multi_queue/oom-killer），内存占用低。"
                                    else ->
                                        "mixed = TCP 走 system / UDP 走 gVisor；system = 纯系统栈最省内存；gVisor = 用户态栈兼容性最好。"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            if (isGoStack) {
                                Spacer(Modifier.height(8.dp))
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Switch(
                                        checked = settings.tunMultiQueue,
                                        onCheckedChange = { store.updateSettings(settings.copy(tunMultiQueue = it)) },
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    Column(Modifier.weight(1f)) {
                                        Text(
                                            "multi_queue（多队列网络引擎）",
                                            style = MaterialTheme.typography.bodyMedium,
                                        )
                                        Text(
                                            "Linux 多队列网络引擎，配合 tsbAI/go 栈提升吞吐（仅 Linux 生效）",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                                // TUN 空闲挂起（SPEC 020；内核需支持 lx.wg.idle_suspend，否则 no-op）
                                Spacer(Modifier.height(12.dp))
                                Text("TUN 空闲挂起", style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "无流量超过阈值后挂起 TUN，进一步省电；需 sb-lxf 内核支持（Leadaxe 扩展），旧内核下为 no-op。单位：分钟。",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                Spacer(Modifier.height(6.dp))
                                Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp)) {
                                    OutlinedTextField(
                                        value = (settings.idleSuspendDuration / 60000L).toString(),
                                        onValueChange = { txt ->
                                            val m = txt.toLongOrNull() ?: 0L
                                            store.updateSettings(settings.copy(idleSuspendDuration = m * 60000L))
                                        },
                                        label = { Text("挂起阈值（分钟，0=关）") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    )
                                    Spacer(Modifier.width(8.dp))
                                    OutlinedTextField(
                                        value = (settings.idleTeardownDuration / 60000L).toString(),
                                        onValueChange = { txt ->
                                            val m = txt.toLongOrNull() ?: 0L
                                            store.updateSettings(settings.copy(idleTeardownDuration = m * 60000L))
                                        },
                                        label = { Text("拆除阈值（分钟）") },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f),
                                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    )
                                }
                            }
                        }
                    }
                    // TUN 地址段自定义（sb-AI tun_address / tun_address6）
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("TUN 地址段", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = settings.tunAddress,
                                onValueChange = { store.updateSettings(settings.copy(tunAddress = it.trim())) },
                                label = { Text("IPv4 段（如 172.18.0.1/30）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = settings.tunAddress6,
                                onValueChange = { store.updateSettings(settings.copy(tunAddress6 = it.trim())) },
                                label = { Text("IPv6 段（如 fdfe:dcba:9876::1/126）") },
                                singleLine = true,
                                enabled = settings.ipv6Route,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                    // 默认出口（route.final）与 DNS 解析策略（dns.strategy）
                    // 这两项生成器一直在用，但此前没有 UI 入口，用户只能改备份文件
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text("默认出口（route.final）", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "未命中任何路由规则时的出口；留空 = 自动跟随代理入口。填 direct 可让默认流量直连。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            OutlinedTextField(
                                value = settings.finalOutbound,
                                onValueChange = { store.updateSettings(settings.copy(finalOutbound = it.trim())) },
                                label = { Text("出口 tag（留空 = 自动）") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Spacer(Modifier.height(12.dp))
                            Text("DNS 解析策略（dns.strategy）", style = MaterialTheme.typography.labelLarge)
                            Text(
                                "域名解析时优先使用的地址族。prefer_ipv4 兼容性最好；双栈场景可用 prefer_ipv6。",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("prefer_ipv4", "prefer_ipv6", "ipv4_only", "ipv6_only").forEach { opt ->
                                    FilterChip(
                                        selected = settings.dnsStrategy == opt,
                                        onClick = { store.updateSettings(settings.copy(dnsStrategy = opt)) },
                                        label = { Text(opt) },
                                    )
                                }
                            }
                        }
                    }
                }
                SbSpacer()
            }

            // ---- DNS 高级：已并入「路由 → DNS → 高级」，这里只留跳转入口 ----
            item {
                SbGroup(title = "DNS 高级") {
                    item {
                        SbItem(
                            title = "Fallback / fakeIP 过滤 / nameserver-policy",
                            subtitle = "这些设置已并入 路由 → DNS → 高级 Tab，点此前往",
                            icon = Icons.Filled.Dns,
                            onClick = onOpenDnsAdvanced,
                        )
                    }
                }
                SbSpacer()
            }
            // ---- DPI 硬化：TLS 分片（参考 LxBox 016）----
            item {
                SbGroup(title = "DPI 硬化") {
                    item {
                        SbSwitchItem(
                            title = "TLS 记录分片",
                            subtitle = "把握手拆成多个 TLS record（推荐先试这个）",
                            icon = Icons.Filled.Security,
                            checked = settings.tlsRecordFragment,
                        ) { store.updateSettings(settings.copy(tlsRecordFragment = it)) }
                    }
                    item {
                        SbSwitchItem(
                            title = "TLS 分片",
                            subtitle = "把 ClientHello 拆成小 TCP 段，绕过 DPI",
                            icon = Icons.Filled.ContentCut,
                            checked = settings.tlsFragment,
                        ) { store.updateSettings(settings.copy(tlsFragment = it)) }
                    }
                    item {
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            OutlinedTextField(
                                value = settings.tlsFragmentFallbackDelay,
                                onValueChange = { store.updateSettings(settings.copy(tlsFragmentFallbackDelay = it.trim())) },
                                label = { Text("回退延迟（如 500ms）") },
                                singleLine = true,
                                enabled = settings.tlsFragment,
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                SbSpacer()
            }

            // ---- 日志 ----
            item {
                SbGroup(title = "日志") {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text("日志级别", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(8.dp))
                            // 5 个级别用 segmented 按钮会挤爆（debug 文字被截断），改用 FlowRow chips
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                LogLevel.entries.forEach { level ->
                                    FilterChip(
                                        selected = settings.logLevel == level,
                                        onClick = { store.updateSettings(settings.copy(logLevel = level)) },
                                        label = { Text(level.wireName) },
                                    )
                                }
                            }
                        }
                    }
                }
                SbSpacer()
            }

            // ---- 分应用代理 ----
            item {
                SbGroup(title = "分应用代理") {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text("模式", style = MaterialTheme.typography.labelLarge)
                            Spacer(Modifier.height(8.dp))
                            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                PerAppProxyMode.entries.forEachIndexed { i, mode ->
                                    SegmentedButton(
                                        selected = settings.perAppProxy.mode == mode,
                                        onClick = {
                                            store.updateSettings(
                                                settings.copy(perAppProxy = settings.perAppProxy.copy(mode = mode)),
                                            )
                                        },
                                        shape = SegmentedButtonDefaults.itemShape(index = i, count = PerAppProxyMode.entries.size),
                                    ) {
                                        Text(
                                            when (mode) {
                                                PerAppProxyMode.OFF -> "关闭"
                                                PerAppProxyMode.INCLUDE -> "白名单"
                                                PerAppProxyMode.EXCLUDE -> "黑名单"
                                            },
                                        )
                                    }
                                }
                            }
                        }
                    }
                    item {
                        SbItem(
                            title = "应用名单（${settings.perAppProxy.packages.size}）",
                            subtitle = if (settings.perAppProxy.mode == PerAppProxyMode.OFF) "先选择模式"
                            else "选择走代理 / 绕过的应用",
                            icon = Icons.Filled.Apps,
                            onClick = { showAppPicker = true },
                        )
                    }
                }
                SbSpacer()
            }

            // ---- China IP 资源：已并入「路由 → 规则集」管理，这里只留跳转入口 ----
            item {
                SbGroup(title = "China IP 资源") {
                    item {
                        SbItem(
                            title = "China IP 列表（${settings.resources.size}）",
                            subtitle = "已并入 路由 → 规则集，命中这些 IP 的连接直连",
                            icon = Icons.Filled.Dns,
                            onClick = onOpenRouteResources,
                        )
                    }
                }
                SbSpacer()
            }

            // ---- 配置覆盖 ----
            item {
                SbGroup(title = "配置覆盖") {
                    item {
                        SbSwitchItem(
                            title = "启用配置覆盖",
                            subtitle = "导入完整 sing-box JSON，与 UI 生成的配置合并",
                            icon = Icons.Filled.Code,
                            checked = settings.configOverride.enabled,
                        ) { enabled ->
                            store.updateSettings(
                                settings.copy(
                                    configOverride = settings.configOverride.copy(
                                        enabled = enabled,
                                        json = if (enabled && settings.configOverride.json.isBlank()) {
                                            OVERRIDE_SAMPLE
                                        } else {
                                            settings.configOverride.json
                                        },
                                    ),
                                ),
                            )
                        }
                    }
                    if (settings.configOverride.enabled) {
                        item {
                            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                                Text("优先级", style = MaterialTheme.typography.labelLarge)
                                Spacer(Modifier.height(4.dp))
                                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                                    OverridePriority.entries.forEachIndexed { i, p ->
                                        SegmentedButton(
                                            selected = settings.configOverride.priority == p,
                                            onClick = {
                                                store.updateSettings(
                                                    settings.copy(
                                                        configOverride = settings.configOverride.copy(priority = p),
                                                    ),
                                                )
                                            },
                                            shape = SegmentedButtonDefaults.itemShape(
                                                index = i, count = OverridePriority.entries.size,
                                            ),
                                        ) { Text(p.displayName) }
                                }
                                }
                                Text(
                                    if (settings.configOverride.priority == OverridePriority.UI_HIGHEST) {
                                        "UI 层最高：导入的 JSON 只能补充 UI 没有生成的字段和没有添加的数组项"
                                    } else {
                                        "导入 JSON 最高：覆盖 UI 生成的同名字段（按 tag/name 合并数组）"
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        item {
                            SbItem(
                                title = "编辑导入的 JSON",
                                subtitle = if (settings.configOverride.json.isBlank()) "未填写"
                                else "已填写（${settings.configOverride.json.length} 字符）",
                                icon = Icons.Filled.EditNote,
                                onClick = { showCustomConfigEditor = true },
                            )
                        }
                        item {
                            SbItem(
                                title = "查看最终合并结果",
                                subtitle = "预览 UI 配置 + 导入 JSON 合并后的 sing-box 配置",
                                icon = Icons.Filled.Preview,
                                onClick = { showOverridePreview = true },
                            )
                        }
                        item {
                            val jsonStr = settings.configOverride.json.trim()
                            // JSON 校验按内容缓存：SettingsScreen 观察整个 AppState，
                            // 节点延迟等无关变化会让这里每次重组都把整份 JSON 重解析一遍。
                            val isValid = remember(jsonStr) {
                                jsonStr.isEmpty() || runCatching {
                                    kotlinx.serialization.json.Json.parseToJsonElement(jsonStr)
                                }.isSuccess
                            }
                            SbItem(
                                title = "JSON 校验",
                                subtitle = if (isValid) "格式正确" else "格式错误",
                                icon = if (isValid) Icons.Filled.CheckCircle else Icons.Filled.Error,
                                iconTint = if (isValid) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                                onClick = {
                                    if (!isValid) editingText = Triple("JSON 格式错误", "请检查 JSON 格式后重新粘贴", {})
                                },
                            )
                        }
                    }
                }
                SbSpacer()
            }

            // ---- 多配置 Profiles（多配置基准） ----
            item {
                SbGroup(title = "配置快照（${state.profiles.size}）") {
                    item {
                        SbItem(
                            title = "保存当前配置为快照",
                            subtitle = if (state.activeProfileId.isEmpty()) "当前未处于快照模式" else "当前在快照: ${state.profiles.find { it.id == state.activeProfileId }?.name}",
                            icon = Icons.Filled.Save,
                            onClick = {
                                profileNameInput = ""
                                showProfileDialog = true
                            },
                        )
                    }
                    if (state.profiles.isNotEmpty()) {
                        item {
                            Column(Modifier.padding(horizontal = 8.dp)) {
                                state.profiles.forEach { profile ->
                                    val isActive = state.activeProfileId == profile.id
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 6.dp)
                                            .background(
                                                if (isActive) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.3f)
                                                else MaterialTheme.colorScheme.surfaceContainerHighest,
                                                RoundedCornerShape(8.dp),
                                            )
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                profile.name,
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = if (isActive) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                                            )
                                            Text(
                                                java.text.SimpleDateFormat("MM-dd HH:mm", java.util.Locale.getDefault()).format(java.util.Date(profile.createdAt)),
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                            androidx.compose.material3.FilledTonalButton(
                                                onClick = {
                                                    editingProfile = profile
                                                    profileNameInput = profile.name
                                                    showProfileDialog = true
                                                },
                                                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                                            ) {
                                                Text("重命名", style = MaterialTheme.typography.labelSmall)
                                            }
                                            if (!isActive) {
                                                androidx.compose.material3.OutlinedButton(
                                                    onClick = { store.activateProfile(profile.id) },
                                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                                                ) {
                                                    Text("加载", style = MaterialTheme.typography.labelSmall)
                                                }
                                            }
                                            androidx.compose.material3.OutlinedButton(
                                                onClick = { store.deleteProfile(profile.id) },
                                                contentPadding = androidx.compose.foundation.layout.PaddingValues(8.dp),
                                            ) {
                                                Text("删除", style = MaterialTheme.typography.labelSmall)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                SbSpacer()
            }

            // ---- 订阅身份（sb-AI SubscriptionIdentity 基准） ----
            item {
                SbGroup(title = "订阅身份") {
                    item {
                        SbItem(
                            title = "全局 User-Agent",
                            subtitle = settings.subscriptionUserAgent.ifBlank { "默认 sb-AI/<版本>" },
                            icon = Icons.Filled.Badge,
                            onClick = {
                                editingText = Triple("全局 User-Agent", settings.subscriptionUserAgent) { v ->
                                    store.updateSettings(settings.copy(subscriptionUserAgent = v.trim()))
                                }
                            },
                        )
                    }
                    item {
                        SbSwitchItem(
                            title = "发送 HWID + 设备信息",
                            subtitle = "x-hwid / x-device-os / x-ver-os / x-device-model（Remnawave 设备限制面板用）",
                            icon = Icons.Filled.Fingerprint,
                            checked = settings.subscriptionSendHwid,
                        ) { enabled ->
                            // 开启时若 hwid 为空则懒生成 UUIDv4
                            val hwid = if (enabled && settings.subscriptionHwid.isBlank()) {
                                java.util.UUID.randomUUID().toString()
                            } else settings.subscriptionHwid
                            store.updateSettings(
                                settings.copy(subscriptionSendHwid = enabled, subscriptionHwid = hwid),
                            )
                        }
                    }
                    if (settings.subscriptionSendHwid) {
                        item {
                            SbItem(
                                title = "HWID（x-hwid）",
                                subtitle = settings.subscriptionHwid.ifBlank { "（开启时自动生成）" },
                                icon = Icons.Filled.VpnKey,
                                onClick = {
                                    editingText = Triple("HWID（留空则重新生成）", settings.subscriptionHwid) { v ->
                                        val newHwid = v.trim().ifBlank { java.util.UUID.randomUUID().toString() }
                                        store.updateSettings(settings.copy(subscriptionHwid = newHwid))
                                    }
                                },
                            )
                        }
                        item {
                            SbItem(
                                title = "设备型号（x-device-model）",
                                subtitle = settings.subscriptionDeviceModel.ifBlank { android.os.Build.MODEL },
                                icon = Icons.Filled.PhoneAndroid,
                                onClick = {
                                    editingText = Triple("x-device-model", settings.subscriptionDeviceModel) { v ->
                                        store.updateSettings(settings.copy(subscriptionDeviceModel = v.trim()))
                                    }
                                },
                            )
                        }
                        item {
                            SbItem(
                                title = "系统版本（x-ver-os）",
                                subtitle = settings.subscriptionVerOs.ifBlank { android.os.Build.VERSION.RELEASE },
                                icon = Icons.Filled.Badge,
                                onClick = {
                                    editingText = Triple("x-ver-os", settings.subscriptionVerOs) { v ->
                                        store.updateSettings(settings.copy(subscriptionVerOs = v.trim()))
                                    }
                                },
                            )
                        }
                    }
                }
                SbSpacer()
            }

            // ---- 备份与恢复 ----
            item {
                SbGroup(title = "备份与恢复") {
                    item {
                        SbItem(
                            title = "导出配置",
                            subtitle = "全部规则 / DNS / 节点 / 设置 → JSON 文件",
                            icon = Icons.Filled.Save,
                            onClick = { exportLauncher.launch("sb-ai-backup.json") },
                        )
                    }
                    item {
                        SbItem(
                            title = "导入配置",
                            subtitle = "从 JSON 文件恢复（可选合并或覆盖）",
                            icon = Icons.Filled.Restore,
                            onClick = { importLauncher.launch(arrayOf("application/json", "text/*")) },
                        )
                    }
                    backupMessage?.let { msg ->
                        item { SbItem(title = "结果", subtitle = msg) }
                    }
                }
                SbSpacer()
            }

            // ---- 启动 ----
            item {
                SbGroup(title = "启动") {
                    item {
                        SbSwitchItem(
                            title = "开机自启",
                            subtitle = "需已授予 VPN 权限；启动 VPN 服务",
                            icon = Icons.Filled.PowerSettingsNew,
                            checked = settings.autoStartOnBoot,
                        ) { store.updateSettings(settings.copy(autoStartOnBoot = it)) }
                    }
                }
                SbSpacer()
            }

            // ---- 关于 ----
            item {
                SbGroup(title = "关于") {
                    item {
                        SbItem(
                            title = "sb-AI",
                            subtitle = "基于 sing-box 内核，含 AWG2/XHTTP/balancer 扩展\nUI 风格参考 Kototoro；功能参考多个开源项目\n不集成 Root / Magisk 功能",
                            icon = Icons.Filled.Info,
                        )
                    }
                    item {
                        SbItem(
                            title = "开源许可",
                            subtitle = "GPL-3.0（与上游保持一致）",
                            icon = Icons.Filled.Article,
                        )
                    }
                }
                Spacer(Modifier.height(120.dp))
            }
        }
    }

    if (showAppPicker) {
        AppPickerDialog(
            title = "选择应用",
            selected = settings.perAppProxy.packages.toSet(),
            onDismiss = { showAppPicker = false },
            onSave = { pkgs ->
                store.updateSettings(settings.copy(perAppProxy = settings.perAppProxy.copy(packages = pkgs.sorted())))
                showAppPicker = false
            },
        )
    }

    if (showCustomConfigEditor) {
        CustomConfigEditorDialog(
            initial = settings.configOverride.json.ifBlank { OVERRIDE_SAMPLE },
            onDismiss = { showCustomConfigEditor = false },
            onSave = { text ->
                store.updateSettings(
                    settings.copy(configOverride = settings.configOverride.copy(json = text)),
                )
                showCustomConfigEditor = false
            },
        )
    }

    // 负载均衡全屏二级页（从首页迁出）
    if (showLoadBalance) {
 SettingsLoadBalanceScreen(onBack = { showLoadBalance = false })
    }

    if (showOverridePreview) {
        val merged = runCatching { SingBoxConfigGenerator.generate(state) }
            .getOrElse { "合并失败: ${it.message}" }
        AlertDialog(
            onDismissRequest = { showOverridePreview = false },
            title = { Text("合并后的 sing-box 配置") },
            text = {
                OutlinedTextField(
                    value = merged, onValueChange = {}, readOnly = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 300.dp),
                    textStyle = MaterialTheme.typography.bodySmall,
                )
            },
            confirmButton = { TextButton(onClick = { showOverridePreview = false }) { Text("关闭") } },
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

    if (showProfileDialog) {
        var name by remember { mutableStateOf(profileNameInput) }
        AlertDialog(
            onDismissRequest = { showProfileDialog = false },
            title = { Text(if (editingProfile != null) "重命名快照" else "保存快照") },
            text = {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("快照名称") }, singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (name.trim().isEmpty()) return@TextButton
                    if (editingProfile != null) {
                        store.update { s ->
                            s.copy(profiles = s.profiles.map { p -> if (p.id == editingProfile!!.id) p.copy(name = name.trim()) else p })
                        }
                    } else {
                        store.createProfile(name.trim(), state)
                    }
                    showProfileDialog = false
                }) { Text("确定") }
            },
            dismissButton = { TextButton(onClick = { showProfileDialog = false }) { Text("取消") } },
        )
    }

    // 备份导入模式选择（merge / replace）
    if (pendingImport != null) {
        val imported = pendingImport!!
        AlertDialog(
            onDismissRequest = { pendingImport = null },
            title = { Text("导入配置") },
            text = {
                Column {
                    Text("选择导入方式：")
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "• 合并：把文件中的规则/节点/订阅追加到现有配置（不删除现有项，同名 id 保留现有）\n" +
                            "• 覆盖：用文件内容整体替换当前全部配置",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    store.replaceAll(imported)
                    backupMessage = "已覆盖导入配置"
                    pendingImport = null
                }) { Text("覆盖导入") }
            },
            dismissButton = {
                TextButton(onClick = {
                    store.replaceAll(BackupManager.merge(state, imported))
                    backupMessage = "已合并导入配置"
                    pendingImport = null
                }) { Text("合并导入") }
            },
        )
    }
}

/** 覆盖示例：常用「UI 没有生成的补充项」写法 */
private const val OVERRIDE_SAMPLE = """{
  "log": { "level": "info" },
  "ntp": { "enabled": true, "server": "ntp.aliyun.com" },
  "experimental": {
    "clash_api": { "external_controller": "127.0.0.1:9090" }
  },
  "outbounds": [
    { "type": "direct", "tag": "warp-direct" }
  ]
}"""

private const val MAX_BACKUP_BYTES = 8 * 1024 * 1024

@Composable
private fun SettingsTextEditDialog(
    title: String,
    initial: String,
    onDismiss: () -> Unit,
    onDone: (String) -> Unit,
) {
    var value by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = value, onValueChange = { value = it },
                singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { TextButton(onClick = { onDone(value) }) { Text("确定") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}

// ---------------------------------------------------------------------------
// 分应用代理：应用选择器
// ---------------------------------------------------------------------------

// ---------------------------------------------------------------------------
// 自定义配置编辑器
// ---------------------------------------------------------------------------
// 自定义配置编辑器
// ---------------------------------------------------------------------------

@Composable
private fun CustomConfigEditorDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("自定义 sing-box 配置") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 280.dp),
                    minLines = 14,
                    textStyle = MaterialTheme.typography.bodySmall,
                    isError = error != null,
                )
                error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                runCatching { Json.parseToJsonElement(text) }
                    .onSuccess { onSave(text) }
                    .onFailure { error = "JSON 无效: ${it.message}" }
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("取消") } },
    )
}
